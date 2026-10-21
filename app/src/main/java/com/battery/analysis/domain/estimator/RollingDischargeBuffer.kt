package com.battery.analysis.domain.estimator

import android.content.Context
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import com.battery.analysis.model.PowerDischargePoint
import java.io.File
import kotlin.math.abs

/**
 * 线性向前滑动放电时序缓冲区实体（Rolling Discharge Window Result）。
 *
 * @property samples 满足滑动窗口时间跨度的真实放电物理采样点序列 [List<PowerSample>]
 * @property effectiveDurationMs 窗口内累计覆盖的真实有效物理做功时间（毫秒）
 * @property avgPowerWatts 窗口内梯形微积分求得的物理加权平均放电功率（单位：W）
 * @property dischargeRatePercentPerHour 窗口内折算的放电速率（单位：%/h，电量未变化或时长极短时为 null）
 * @property startBatteryLevel 窗口起始电量百分比
 * @property endBatteryLevel 窗口截止电量百分比
 * @property screenOnDurationMs 窗口内累计覆盖的真实有效亮屏物理做功时间（毫秒）
 */
data class RollingDischargeWindow(
    val samples: List<PowerSample>,
    val effectiveDurationMs: Long,
    val avgPowerWatts: Float,
    val dischargeRatePercentPerHour: Float?,
    val startBatteryLevel: Int,
    val endBatteryLevel: Int,
    val screenOnDurationMs: Long = 0L
)

/**
 * 线性向前滑动放电环形缓冲区管理器（Rolling Discharge Buffer Manager）。
 * 针对电池剩余续航预测与动态放电速度评估，彻底打破“拔电插拔强制清零（$T=0$）”带来的冷启动真空与预测跳变问题。
 *
 * 核心物理与计量学原则：
 * 1. 放电时序连续性（Continuity）：维护独立于单次拔电会话的跨周期放电滑动记忆，
 *    设备插电充电时，放电缓冲区安全挂起保持记忆（绝不物理删除清空）；拔电后新放电样本无缝追加；
 * 2. 充电状态物理隔离：严格仅收录真实物理放电采样点，充电状态（CHARGING）数据 100% 物理隔离拦截，绝不污染放电模型；
 * 3. 多维度独立时间轴：支持沿亮屏时间轴（Screen-On）、息屏待机时间轴（Screen-Off）以及全局放电时间轴
 *    从当前刷新时刻向历史线性回溯 5min, 10min, 20min, 30min, 60min, 120min，
 *    使预测模型在拔电第 1 秒即可汲取成熟的物理做功均值（如 0.86W），实现零震荡高置信度预测。
 */
class RollingDischargeBuffer internal constructor(private val baseDir: File?) {

    /**
     * 应用程序主构造函数，从应用上下文中获取私有数据存储目录。
     *
     * @param context 应用程序上下文
     */
    constructor(context: Context) : this(context.applicationContext.filesDir)

    companion object {
        private const val FILE_NAME = "rolling_discharge_samples.json"
        private const val TMP_FILE_NAME = "rolling_discharge_samples.json.tmp"

        /** 滑动缓冲区挂钟物理时间保留上限（毫秒）：保留最近 48 小时，确保跨夜整夜充电（8~12小时）后拔电瞬间放电记忆完好保留 */
        private const val MAX_WALL_DURATION_MS = 48 * 3600_000L

        /** 兼容旧代码的静态访问别名，指向 [MAX_WALL_DURATION_MS] */
        private const val MAX_BUFFER_DURATION_MS = MAX_WALL_DURATION_MS

        /** 滑动缓冲区采样点数量硬上限，防止内存膨胀（20000 留足安全余量） */
        private const val MAX_SAMPLES_COUNT = 20000

        @Volatile
        private var instance: RollingDischargeBuffer? = null

        /**
         * 获取线性滑动放电缓冲区单例实体。
         *
         * @param context 应用程序上下文
         * @return 单例实例 [RollingDischargeBuffer]
         */
        fun getInstance(context: Context): RollingDischargeBuffer {
            return instance ?: synchronized(this) {
                instance ?: RollingDischargeBuffer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 获取线性滑动放电缓冲区单例实体（若尚未初始化则返回 null）。
         *
         * @return 单例实例 [RollingDischargeBuffer] 或 null
         */
        fun getInstanceOrNull(): RollingDischargeBuffer? {
            return instance
        }

        /**
         * 供单元测试或离线计算初始化的测试专用构建方法。
         *
         * @param testDir 存放持久化数据的测试目录 [File]
         * @return 初始化后的单例实体 [RollingDischargeBuffer]
         */
        fun initForTesting(testDir: File): RollingDischargeBuffer {
            return synchronized(this) {
                RollingDischargeBuffer(testDir).also { instance = it }
            }
        }

        /**
         * 重置单例状态（供单元测试清理环境）。
         */
        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }

    /** 内存中常驻的放电采样点时序列表（按时间戳严格单调递增） */
    private val bufferSamples = mutableListOf<PowerDischargePoint>()
    private var isLoaded = false

    /**
     * 确保本地持久化的滑动放电采样点已加载至内存缓冲区。
     */
    @Synchronized
    private fun ensureLoaded() {
        if (isLoaded) return
        isLoaded = true
        val targetFile = baseDir?.let { File(it, FILE_NAME) } ?: return
        if (!targetFile.exists() || !targetFile.canRead()) return

        try {
            val jsonStr = targetFile.readText(Charsets.UTF_8)
            if (jsonStr.isNotEmpty()) {
                val jsonArray = org.json.JSONArray(jsonStr)
                bufferSamples.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val p = PowerDischargePoint(
                        timestamp = obj.optLong("ts", 0L),
                        elapsedHours = obj.optDouble("elapsed", 0.0).toFloat(),
                        batteryLevel = obj.optInt("lvl", 0),
                        voltageVolts = obj.optDouble("volt", 0.0).toFloat(),
                        temperature = obj.optDouble("temp", 0.0).toFloat(),
                        powerWatts = obj.optDouble("pwr", 0.0).toFloat(),
                        isScreenOn = obj.optBoolean("screenOn", false),
                        packageName = obj.optString("pkg").takeIf { it.isNotEmpty() }
                    )
                    if (p.powerWatts.isFinite() && p.powerWatts > 0f) {
                        bufferSamples.add(p)
                    }
                }
                bufferSamples.sortBy { it.timestamp }
            }
        } catch (_: Exception) {}
    }

    /**
     * 向滑动缓冲区追加最新的物理放电采样点。
     * 具备充电状态守卫与浮点数有限性拦截：仅收纳净放电（功率有限值且 > 0 且处于非充电状态）的有效物理数据，
     * 自动执行时间跨度裁剪，剔除超过 [MAX_WALL_DURATION_MS] 的陈旧数据。
     *
     * @param point 最新的物理放电采样点 [PowerDischargePoint]
     * @param isCharging 当前电池是否处于充电状态
     */
    @Synchronized
    fun addSample(point: PowerDischargePoint, isCharging: Boolean) {
        if (isCharging || !point.powerWatts.isFinite() || point.powerWatts <= 0f) {
            return
        }
        ensureLoaded()

        val lastPoint = bufferSamples.lastOrNull()
        // 500ms 防抖，避免同一毫秒重复写入
        if (lastPoint != null && (point.timestamp - lastPoint.timestamp) < 500L) {
            return
        }

        bufferSamples.add(point)

        // 缓冲区容量修剪（按时间跨度与点数上限滑动裁剪）
        trimBufferIfNeeded()
    }

    /**
     * 批量导入历史有效放电时序采样点集（用于冷启动或草稿初始化时无缝继承历史放电轨迹）。
     *
     * @param points 待合流的物理采样点集合 [List<PowerDischargePoint>]
     */
    @Synchronized
    fun appendSamplesBatch(points: List<PowerDischargePoint>) {
        if (points.isEmpty()) return
        ensureLoaded()

        val validPoints = points.filter { it.powerWatts.isFinite() && it.powerWatts > 0f }
        if (validPoints.isEmpty()) return

        val existingTimestamps = bufferSamples.map { it.timestamp }.toHashSet()
        for (p in validPoints) {
            if (!existingTimestamps.contains(p.timestamp)) {
                bufferSamples.add(p)
                existingTimestamps.add(p.timestamp)
            }
        }
        bufferSamples.sortBy { it.timestamp }
        trimBufferIfNeeded()
    }

    /**
     * 沿亮屏放电时序向前线性回溯指定目标跨度的滑动窗口做功切片。
     * 严格仅累计真实有效亮屏（isScreenOn == true）且放电的物理采样点。
     *
     * @param targetDurationMs 期望回溯的目标物理做功时长（毫秒，如 5min=300000L, 30min=1800000L, 60min=3600000L）
     * @return 提取并计算完成的滑动窗口实体 [RollingDischargeWindow]，数据不足时按实际可用最大跨度返回或 null
     */
    @Synchronized
    fun extractRollingScreenOnWindow(targetDurationMs: Long): RollingDischargeWindow? {
        ensureLoaded()
        return extractRollingWindowInternal(
            targetDurationMs = targetDurationMs,
            filterScreenOn = true,
            filterScreenOff = false,
            baselineSleepWatts = null
        )
    }

    /**
     * 沿息屏待机放电时序向前线性回溯指定目标跨度的滑动窗口做功切片。
     * 严格仅累计真实有效息屏待机（isScreenOn == false）且放电的物理采样点。
     *
     * @param targetDurationMs 期望回溯的目标物理做功时长（毫秒）
     * @param baselineSleepWatts 设备历史学习到的真实休眠底噪基线（单位：W，可选，杜绝硬编码先验常数）
     * @return 提取并计算完成的滑动窗口实体 [RollingDischargeWindow]，数据不足时返回 null
     */
    @Synchronized
    fun extractRollingScreenOffWindow(
        targetDurationMs: Long,
        baselineSleepWatts: Double? = null
    ): RollingDischargeWindow? {
        ensureLoaded()
        return extractRollingWindowInternal(
            targetDurationMs = targetDurationMs,
            filterScreenOn = false,
            filterScreenOff = true,
            baselineSleepWatts = baselineSleepWatts
        )
    }

    /**
     * 沿全局放电时序向前线性回溯指定目标跨度的滑动窗口做功切片（包含亮屏与息屏全部放电时段）。
     *
     * @param targetDurationMs 期望回溯的目标物理做功时长（毫秒）
     * @param baselineSleepWatts 设备历史学习到的真实休眠底噪基线（单位：W，可选，用于息屏待机段做功积分）
     * @return 提取并计算完成的滑动窗口实体 [RollingDischargeWindow]，数据不足时返回 null
     */
    @Synchronized
    fun extractRollingGlobalWindow(
        targetDurationMs: Long,
        baselineSleepWatts: Double? = null
    ): RollingDischargeWindow? {
        ensureLoaded()
        return extractRollingWindowInternal(
            targetDurationMs = targetDurationMs,
            filterScreenOn = false,
            filterScreenOff = false,
            baselineSleepWatts = baselineSleepWatts
        )
    }

    /**
     * 内部核心线性回溯算法：从最新采样点逆序向前扫描，按真实物理梯形微积分累计做功与时间跨度。
     * 具备跨充电鸿沟物理断开机制与长期待机硬件挂起（Suspend）微积分保护。
     *
     * @param targetDurationMs 目标物理时间跨度（毫秒）
     * @param filterScreenOn 是否仅筛选亮屏点
     * @param filterScreenOff 是否仅筛选息屏点
     * @param baselineSleepWatts 真实休眠底噪基线（单位：W，可选）
     * @return 经过微积分计算的滑动窗口数据包 [RollingDischargeWindow]，样本不足 2 个时返回 null
     */
    private fun extractRollingWindowInternal(
        targetDurationMs: Long,
        filterScreenOn: Boolean,
        filterScreenOff: Boolean,
        baselineSleepWatts: Double? = null
    ): RollingDischargeWindow? {
        if (bufferSamples.isEmpty()) return null

        // 逆序从最新样本向前线性扫描完整时序序列，严格保证状态纯度与时序连续性
        val reversed = bufferSamples.asReversed()
        val windowPoints = mutableListOf<PowerDischargePoint>()
        var accumulatedDurationMs = 0L
        var accumulatedScreenOnMs = 0L
        var accumulatedJoules = 0.0
        var totalLevelDrop = 0

        var prevTs = 0L
        var prevWatts = 0f
        var prevLevel = -1
        var prevIsScreenOn = false
        var segmentStartLevel = -1
        var segmentEndLevel = -1
        var segmentPointsCount = 0
        var segmentEndWatts = 0f

        for (p in reversed) {
            val matchesCondition = when {
                filterScreenOn -> p.isScreenOn
                filterScreenOff -> !p.isScreenOn
                else -> true
            }

            if (!matchesCondition) {
                // 遇到状态不匹配的点（如提取息屏窗口时遇到了亮屏使用时隙）：
                // 状态时间轴发生真实物理中断，重置时间锚点以断开微积分连接，绝不将跨状态时隙计入做功
                if (segmentStartLevel >= 0 && segmentEndLevel >= 0 && segmentStartLevel >= segmentEndLevel) {
                    totalLevelDrop += (segmentStartLevel - segmentEndLevel)
                }
                // 孤立单点补偿：若当前连续状态片段仅有 1 个采样点，梯形微积分未能累加时间
                // 该点在物理上至少代表了 10 秒微小碎片化用机时长，补充其物理代表时长与有效做功，保持能量守恒
                if (segmentPointsCount == 1 && segmentEndWatts > 0f) {
                    val stepDt = 10_000L
                    accumulatedDurationMs += stepDt
                    accumulatedJoules += segmentEndWatts * (stepDt / 1000.0)
                    if (filterScreenOn || prevIsScreenOn) {
                        accumulatedScreenOnMs += stepDt
                    }
                }
                prevTs = 0L
                prevWatts = 0f
                prevLevel = -1
                prevIsScreenOn = false
                segmentStartLevel = -1
                segmentEndLevel = -1
                segmentPointsCount = 0
                segmentEndWatts = 0f
                continue
            }

            if (prevTs > 0L) {
                val rawDt = abs(prevTs - p.timestamp)
                // 跨充电鸿沟检测：两点间隔超过 1 小时，或较早的点 p 电量反而显著低于较晚的点 prevLevel（电量因插电而充高）
                val isChargingGap = rawDt > 3600_000L || (prevLevel >= 0 && p.batteryLevel < prevLevel)
                if (isChargingGap) {
                    // 物理放电时间轴发生充电中断：结算当前连续放电片段的电量掉电，断开微积分连接
                    if (segmentStartLevel >= 0 && segmentEndLevel >= 0 && segmentStartLevel >= segmentEndLevel) {
                        totalLevelDrop += (segmentStartLevel - segmentEndLevel)
                    }
                    if (segmentPointsCount == 1 && segmentEndWatts > 0f) {
                        val stepDt = 10_000L
                        accumulatedDurationMs += stepDt
                        accumulatedJoules += segmentEndWatts * (stepDt / 1000.0)
                        if (filterScreenOn || prevIsScreenOn) {
                            accumulatedScreenOnMs += stepDt
                        }
                    }
                    prevTs = 0L
                    prevWatts = 0f
                    prevIsScreenOn = false
                    segmentEndLevel = -1
                    segmentPointsCount = 0
                    segmentEndWatts = 0f
                }
            }

            windowPoints.add(p)
            segmentPointsCount++
            if (segmentEndLevel < 0) {
                // 逆序扫描中，遇到的第一个有效点是该连续放电段的终态（较晚时刻）
                segmentEndLevel = p.batteryLevel
                segmentEndWatts = p.powerWatts
            }
            segmentStartLevel = p.batteryLevel // 随着逆序向前推移，不断更新为较早时刻的起始电量

            if (prevTs > 0L) {
                val rawDt = abs(prevTs - p.timestamp)
                val isScreenOffSuspended = (!p.isScreenOn && !prevIsScreenOn) && rawDt > 120_000L
                if (isScreenOffSuspended) {
                    // 无论是纯息屏窗口还是全局窗口，只要相邻连续点均为息屏且间隔 > 120s，均属于系统挂起深度休眠
                    val pulseDt = 10_000L
                    val sleepDt = (rawDt - pulseDt).coerceAtLeast(0L)
                    val avgPulseW = ((prevWatts + p.powerWatts) / 2.0).coerceAtLeast(0.0)
                    // 休眠底噪优先使用历史学习出的真实基准 baselineSleepWatts；若无则取端点物理测量的较小值自然衰减，严禁虚构硬编码
                    val effectiveSleepWatts = baselineSleepWatts?.takeIf { it > 0.0 }
                        ?: kotlin.math.min(prevWatts.toDouble(), p.powerWatts.toDouble()).coerceAtLeast(0.0)
                    accumulatedDurationMs += rawDt
                    accumulatedJoules += (avgPulseW * (pulseDt / 1000.0)) + (effectiveSleepWatts * (sleepDt / 1000.0))
                } else {
                    val dt = when {
                        filterScreenOff -> rawDt.coerceIn(100L, 60_000L)
                        else -> rawDt.coerceIn(100L, 300_000L)
                    }
                    val avgW = ((prevWatts + p.powerWatts) / 2.0).coerceAtLeast(0.0)
                    accumulatedDurationMs += dt
                    accumulatedJoules += avgW * (dt / 1000.0)
                    if (filterScreenOn) {
                        accumulatedScreenOnMs += dt
                    } else if (!filterScreenOff) {
                        if (p.isScreenOn && prevIsScreenOn) {
                            accumulatedScreenOnMs += dt
                        } else if (p.isScreenOn != prevIsScreenOn) {
                            val onDt = if (p.isScreenOn) minOf(1000L, dt) else (dt - minOf(1000L, dt))
                            accumulatedScreenOnMs += onDt
                        }
                    }
                }
            }

            prevTs = p.timestamp
            prevWatts = p.powerWatts
            prevLevel = p.batteryLevel
            prevIsScreenOn = p.isScreenOn

            if (accumulatedDurationMs >= targetDurationMs) {
                break
            }
        }

        // 结算最后一个连续放电片段的电量压降与孤立单点做功
        if (segmentStartLevel >= 0 && segmentEndLevel >= 0 && segmentStartLevel >= segmentEndLevel) {
            totalLevelDrop += (segmentStartLevel - segmentEndLevel)
        }
        if (segmentPointsCount == 1 && segmentEndWatts > 0f) {
            val stepDt = 10_000L
            accumulatedDurationMs += stepDt
            accumulatedJoules += segmentEndWatts * (stepDt / 1000.0)
            if (filterScreenOn || prevIsScreenOn) {
                accumulatedScreenOnMs += stepDt
            }
        }

        if (windowPoints.size < 2 || accumulatedDurationMs <= 0L) return null

        // 按时间顺序重新排列
        windowPoints.reverse()

        val avgPowerWatts = if (accumulatedDurationMs > 0L) {
            ((accumulatedJoules / (accumulatedDurationMs / 1000.0)).toFloat()).coerceAtLeast(0f)
        } else {
            0f
        }

        val startLevel = windowPoints.first().batteryLevel
        val endLevel = windowPoints.last().batteryLevel
        val hours = accumulatedDurationMs / 3600_000f

        val rate = if (hours > 0.05f && totalLevelDrop > 0) {
            totalLevelDrop / hours
        } else null

        val samples = windowPoints.map { pt ->
            PowerSample(
                timestampMs = pt.timestamp,
                voltageVolts = pt.voltageVolts,
                currentAmperes = null,
                powerWatts = pt.powerWatts,
                batteryLevelPercent = pt.batteryLevel.toFloat(),
                state = if (pt.isScreenOn) ScreenPowerState.SCREEN_ON else ScreenPowerState.SCREEN_OFF
            )
        }

        val safeScreenOnMs = when {
            filterScreenOn -> accumulatedDurationMs
            filterScreenOff -> 0L
            else -> accumulatedScreenOnMs.coerceIn(0L, accumulatedDurationMs)
        }

        return RollingDischargeWindow(
            samples = samples,
            effectiveDurationMs = accumulatedDurationMs,
            avgPowerWatts = avgPowerWatts,
            dischargeRatePercentPerHour = rate,
            startBatteryLevel = startLevel,
            endBatteryLevel = endLevel,
            screenOnDurationMs = safeScreenOnMs
        )
    }

    /**
     * 执行缓冲区容量修剪，保持保留跨度与点数规模在安全阈值以内。
     */
    private fun trimBufferIfNeeded() {
        if (bufferSamples.isEmpty()) return

        // 1. 点数超限截断
        if (bufferSamples.size > MAX_SAMPLES_COUNT) {
            val removeCount = bufferSamples.size - MAX_SAMPLES_COUNT
            bufferSamples.subList(0, removeCount).clear()
        }

        // 2. 物理时间跨度超限截断
        val latestTs = bufferSamples.last().timestamp
        val oldestAllowedTs = latestTs - MAX_BUFFER_DURATION_MS
        val idx = bufferSamples.indexOfFirst { it.timestamp >= oldestAllowedTs }
        if (idx > 0) {
            bufferSamples.subList(0, idx).clear()
        }
    }

    /**
     * 将滑动缓冲区采样点异步持久化至本地私有文件。
     */
    @Synchronized
    fun persistBufferAsync() {
        ensureLoaded()
        val snapshot = ArrayList(bufferSamples)
        if (snapshot.isEmpty()) return

        diskIoExecutor.execute {
            try {
                val sb = java.lang.StringBuilder(snapshot.size * 90)
                sb.append('[')
                for (i in snapshot.indices) {
                    val p = snapshot[i]
                    if (i > 0) sb.append(',')
                    sb.append("{\"ts\":").append(p.timestamp)
                        .append(",\"elapsed\":").append(p.elapsedHours)
                        .append(",\"lvl\":").append(p.batteryLevel)
                        .append(",\"volt\":").append(p.voltageVolts)
                        .append(",\"temp\":").append(p.temperature)
                        .append(",\"pwr\":").append(p.powerWatts)
                        .append(",\"screenOn\":").append(p.isScreenOn)
                    if (!p.packageName.isNullOrEmpty()) {
                        sb.append(",\"pkg\":\"").append(p.packageName.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
                    }
                    sb.append('}')
                }
                sb.append(']')

                val dir = baseDir ?: return@execute
                val targetFile = File(dir, FILE_NAME)
                val tempFile = File(dir, TMP_FILE_NAME)
                tempFile.writeText(sb.toString(), Charsets.UTF_8)
                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }
            } catch (_: Exception) {}
        }
    }

    private val diskIoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RollingDischargeBuffer-IO").apply { priority = Thread.MIN_PRIORITY }
    }
}
