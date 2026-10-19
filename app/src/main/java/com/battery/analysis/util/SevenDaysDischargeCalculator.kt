package com.battery.analysis.util

import android.content.Context
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.SevenDaysDischargeStats
import java.util.Locale

/**
 * 累积 7 天物理使用时间核心放电统计指标与充满电可用时长物理计算器。
 * 严格基于设备本地数据库真实历史快照记录与当前正在进行的放电会话，
 * 按时间倒序独立累加亮屏、息屏与全局三个维度的实际物理放电使用时长（上限为 7 天 = 168 小时），
 * 超出 168 小时时进行边界物理等比裁剪以确保放电平均功率不变与能量守恒，
 * 数据不足时忠实保留真实历史累积值绝不虚构数据，严谨换算加权平均放电速率（%/h）并推导充满电理论使用时长（包含模式1全局加权与模式2场景融合两套模型）。
 */
object SevenDaysDischargeCalculator {

    /**
     * 7 天物理毫秒时长常量（7 * 24 * 3600 * 1000 毫秒 = 168 小时）。
     */
    private const val SEVEN_DAYS_MS = 7L * 24L * 3600L * 1000L

    /**
     * 单个维度（亮屏、息屏或全局）的时长与消耗能量累加器。
     * 维护目标物理时长（默认 7 天 = 168 小时），并在超出时执行精确的物理等比裁剪。
     *
     * @property targetDurationMs 目标累积物理时长上限（单位：毫秒）
     */
    internal class MetricAccumulator(
        private val targetDurationMs: Long = SEVEN_DAYS_MS
    ) {
        /**
         * 当前已累积的实际物理时长（单位：毫秒）。
         */
        var accumulatedDurationMs: Long = 0L
            private set

        /**
         * 当前已累积的真实消耗物理能量（单位：瓦时 Wh）。
         */
        var accumulatedEnergyWh: Double = 0.0
            private set

        /**
         * 判断累加器是否已达到或超过目标统计物理时长上限。
         *
         * @return 若已达到或超过目标时长则返回 true，否则返回 false
         */
        fun isFull(): Boolean = accumulatedDurationMs >= targetDurationMs

        /**
         * 向累加器中按时间倒序追加一段物理放电记录数据。
         * 若追加后超出目标时长上限，则对超出部分按剩余所需时长与本条时长的比例进行精确物理等比裁剪，
         * 使得累加时长恰好等于目标时长，且该条记录的平均放电功率保持物理守恒。
         *
         * @param durationMs 追加的放电持续时长（单位：毫秒）
         * @param energyWh 追加的消耗物理能量（单位：瓦时 Wh）
         * @return 本次追加中实际被纳入累加器的比例（0.0 至 1.0 之间）
         */
        fun add(durationMs: Long, energyWh: Double): Double {
            if (isFull() || durationMs <= 0L || energyWh <= 0.0) {
                return 0.0
            }
            val remainMs = targetDurationMs - accumulatedDurationMs
            return if (durationMs <= remainMs) {
                accumulatedDurationMs += durationMs
                accumulatedEnergyWh += energyWh
                1.0
            } else {
                val ratio = remainMs.toDouble() / durationMs.toDouble()
                accumulatedDurationMs += remainMs
                accumulatedEnergyWh += energyWh * ratio
                ratio
            }
        }
    }

    /**
     * 倒序累加计算累积 7 天（168 小时）使用时间的亮屏、息屏与全局加权放电速度及充满电可用时长。
     * 支持模式1（全局加权）与模式2（场景占空比融合）两套完整续航模型。
     *
     * @param context 应用程序上下文
     * @param currentOverview 当前正在进行的放电会话统计数据（可为空）
     * @param totalCapacityWh 电池标称总能量容量（单位：Wh）
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @param currentTrendPoints 当前活跃放电会话的时序采样点列表（可选，若提供则优先细粒度切片）
     * @return 累积 7 天使用时间统计计算结果实体 [SevenDaysDischargeStats]
     */
    fun calculateSevenDaysStats(
        context: Context,
        currentOverview: PowerOverviewStats?,
        totalCapacityWh: Float?,
        baseTimestamp: Long = System.currentTimeMillis(),
        currentTrendPoints: List<com.battery.analysis.model.PowerDischargePoint>? = null
    ): SevenDaysDischargeStats {
        if (totalCapacityWh == null || totalCapacityWh <= 0f) {
            return SevenDaysDischargeStats()
        }

        val onAcc = MetricAccumulator(SEVEN_DAYS_MS)
        val offAcc = MetricAccumulator(SEVEN_DAYS_MS)
        val globalAcc = MetricAccumulator(SEVEN_DAYS_MS)

        // 记录全局样本窗口内实际统计的亮屏与息屏时长，用于推算模式2日常场景占空比
        var globalScopeOnDur = 0L
        var globalScopeOffDur = 0L

        // 1. 优先累加最新的、当前正在进行的活跃放电会话
        if (currentOverview != null) {
            val curGDur = if (currentOverview.totalDurationMs > 0L) {
                currentOverview.totalDurationMs
            } else {
                parseDurationTextToMs(currentOverview.totalDurationText)
            }
            val curGEnergy = if (currentOverview.totalEnergyWh > 0f) {
                currentOverview.totalEnergyWh.toDouble()
            } else if (currentOverview.avgPowerWatts > 0f && curGDur > 0L) {
                currentOverview.avgPowerWatts.toDouble() * (curGDur / 3600000.0)
            } else {
                0.0
            }
            val gRatio = globalAcc.add(curGDur, curGEnergy)

            val curOnDur = if (currentOverview.screenOnDurationMs > 0L) {
                currentOverview.screenOnDurationMs
            } else {
                parseDurationTextToMs(currentOverview.screenOnDurationText)
            }
            val curOnEnergy = if (currentOverview.screenOnEnergyWh > 0f) {
                currentOverview.screenOnEnergyWh.toDouble()
            } else if (currentOverview.screenOnPowerWatts > 0f && curOnDur > 0L) {
                currentOverview.screenOnPowerWatts.toDouble() * (curOnDur / 3600000.0)
            } else {
                0.0
            }
            onAcc.add(curOnDur, curOnEnergy)

            val curOffDur = if (currentOverview.screenOffDurationMs > 0L) {
                currentOverview.screenOffDurationMs
            } else {
                parseDurationTextToMs(currentOverview.screenOffDurationText)
            }
            val curOffEnergy = if (currentOverview.screenOffEnergyWh > 0f) {
                currentOverview.screenOffEnergyWh.toDouble()
            } else if (currentOverview.screenOffPowerWatts > 0f && curOffDur > 0L) {
                currentOverview.screenOffPowerWatts.toDouble() * (curOffDur / 3600000.0)
            } else {
                0.0
            }
            offAcc.add(curOffDur, curOffEnergy)

            if (gRatio > 0.0) {
                globalScopeOnDur += (curOnDur * gRatio).toLong()
                globalScopeOffDur += (curOffDur * gRatio).toLong()
            }
        }

        // 2. 倒序加载历史归档记录（getAllRecords 已默认按 id DESC 倒序排序）
        val dbHelper = PowerUsageDbHelper.getInstance(context)
        val allRecords = dbHelper.getAllRecords()

        // 筛选符合 168 小时滚动墙钟窗口与基准时间戳的历史记录（排除未来时间戳记录与充电异常记录）
        val windowStartTs = baseTimestamp - SEVEN_DAYS_MS
        val eligibleRecords = allRecords.filter { record ->
            !record.isCharging && record.id <= (baseTimestamp + 60_000L)
        }

        for (record in eligibleRecords) {
            // 若历史记录中包含未完结的检查点草稿，且当前存在实时会话 currentOverview，则跳过旧草稿以当前最新数据为准
            if (!record.isCompleted && currentOverview != null) {
                continue
            }

            val recEndTs = record.id
            // 若记录完全早于 7 天自然时间窗口起点，终止遍历更早记录
            if (recEndTs <= windowStartTs) {
                break
            }

            // 若三个维度均已累积满 7 天（168 小时），提前终止遍历以提高性能
            if (onAcc.isFull() && offAcc.isFull() && globalAcc.isFull()) {
                break
            }

            val rawGDur = record.getDurationMs()
            val rawGEnergy = if (record.totalEnergyWh > 0f) {
                record.totalEnergyWh.toDouble()
            } else if (record.avgPowerWatts > 0f && rawGDur > 0L) {
                record.avgPowerWatts.toDouble() * (rawGDur / 3600000.0)
            } else {
                0.0
            }

            val rawOnDur = record.getScreenOnDurationMs()
            val rawOnEnergy = if (record.screenOnEnergyWh > 0f) {
                record.screenOnEnergyWh.toDouble()
            } else if (record.screenOnPowerWatts > 0f && rawOnDur > 0L) {
                record.screenOnPowerWatts.toDouble() * (rawOnDur / 3600000.0)
            } else {
                0.0
            }

            val rawOffDur = record.getScreenOffDurationMs()
            val rawOffEnergy = if (record.screenOffEnergyWh > 0f) {
                record.screenOffEnergyWh.toDouble()
            } else if (record.screenOffPowerWatts > 0f && rawOffDur > 0L) {
                record.screenOffPowerWatts.toDouble() * (rawOffDur / 3600000.0)
            } else {
                0.0
            }

            // 计算记录是否跨越 7 天（168 小时）滚动自然时间窗口起点并执行边界物理等比裁剪
            val recStartTs = recEndTs - rawGDur
            val isCrossingBoundary = recStartTs < windowStartTs
            val boundaryRatio = if (isCrossingBoundary && rawGDur > 0L) {
                ((recEndTs - windowStartTs).toDouble() / rawGDur.toDouble()).coerceIn(0.0, 1.0)
            } else {
                1.0
            }

            val gDur = if (boundaryRatio < 1.0) (rawGDur.toDouble() * boundaryRatio).toLong().coerceAtLeast(1L) else rawGDur
            val gEnergy = rawGEnergy * boundaryRatio
            val onDur = if (boundaryRatio < 1.0) (rawOnDur.toDouble() * boundaryRatio).toLong() else rawOnDur
            val onEnergy = rawOnEnergy * boundaryRatio
            val offDur = if (boundaryRatio < 1.0) (rawOffDur.toDouble() * boundaryRatio).toLong() else rawOffDur
            val offEnergy = rawOffEnergy * boundaryRatio

            // 全局放电指标倒序累加
            if (!globalAcc.isFull() && gDur > 0L && gEnergy > 0.0) {
                val gRatio = globalAcc.add(gDur, gEnergy)
                if (gRatio > 0.0) {
                    globalScopeOnDur += (onDur * gRatio).toLong()
                    globalScopeOffDur += (offDur * gRatio).toLong()
                }
            }

            // 亮屏放电指标倒序累加
            if (!onAcc.isFull() && onDur > 0L && onEnergy > 0.0) {
                onAcc.add(onDur, onEnergy)
            }

            // 息屏放电指标倒序累加
            if (!offAcc.isFull() && offDur > 0L && offEnergy > 0.0) {
                offAcc.add(offDur, offEnergy)
            }

            // 若本条记录已跨越自然时间窗口起始边界，更早的历史记录无需继续遍历
            if (isCrossingBoundary) {
                break
            }
        }

        // 3. 计算累积 7 天加权平均放电速率（%/h）与充满电可用理论时长（毫秒）
        val screenOnRate = computeRate(onAcc.accumulatedEnergyWh, onAcc.accumulatedDurationMs, totalCapacityWh)
        val screenOffRate = computeRate(offAcc.accumulatedEnergyWh, offAcc.accumulatedDurationMs, totalCapacityWh)
        val globalRate = computeRate(globalAcc.accumulatedEnergyWh, globalAcc.accumulatedDurationMs, totalCapacityWh)

        // 模式1：充满电理论可用时长（基于累积 7 天各维度加权平均功耗）
        val fullOnDuration = computeFullDurationMs(screenOnRate)
        val fullOffDuration = computeFullDurationMs(screenOffRate)
        val fullGlobalDuration = computeFullDurationMs(globalRate)

        // 模式2：日常场景占空比融合推算（基于真实亮屏与息屏时间占比动态加权）
        val totalScopeDur = globalScopeOnDur + globalScopeOffDur
        val sceneRatio = if (totalScopeDur > 0L && globalScopeOnDur >= 0L) {
            (globalScopeOnDur.toDouble() / totalScopeDur.toDouble()).toFloat()
        } else {
            null
        }

        val (sceneRate, sceneFullDuration) = if (sceneRatio != null && screenOnRate != null && screenOffRate != null) {
            val compositeRate = (sceneRatio * screenOnRate) + ((1.0f - sceneRatio) * screenOffRate)
            val compositeFullDur = computeFullDurationMs(compositeRate)
            Pair(compositeRate, compositeFullDur)
        } else {
            Pair(null, null)
        }

        // 实际有效累积时长（数据不足时如实返回实际值，上限精确为 7 天使用时长 168 小时）
        val actualOnDur = if (screenOnRate != null && onAcc.accumulatedDurationMs > 0L) {
            onAcc.accumulatedDurationMs
        } else {
            null
        }
        val actualOffDur = if (screenOffRate != null && offAcc.accumulatedDurationMs > 0L) {
            offAcc.accumulatedDurationMs
        } else {
            null
        }
        val actualGlobalDur = if (globalRate != null && globalAcc.accumulatedDurationMs > 0L) {
            globalAcc.accumulatedDurationMs
        } else {
            null
        }

        // 4. 执行自适应历史时间窗口与细粒度工况切片满电续航算法升级（v2.2）影子模式计算
        val v2Result = try {
            val v2Estimator = com.battery.analysis.domain.estimator.FullChargeRuntimeEstimator()
            val powerMgr = com.battery.analysis.manager.PowerUsageManager.getInstance(context)
            val capWithSource = powerMgr.getEffectiveDeviceCapacityWithSource()
            val sourceDesc = capWithSource.second
            val isFromFcc = sourceDesc.contains("满充") || sourceDesc.contains("FCC")
            val energySource = if (isFromFcc) {
                com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE
            } else if (sourceDesc.contains("设计")) {
                com.battery.analysis.domain.estimator.model.EnergySource.DESIGN_CAPACITY_TIMES_HEALTH
            } else {
                com.battery.analysis.domain.estimator.model.EnergySource.FCC_TIMES_NOMINAL_VOLTAGE
            }
            val v2EnergyEstimate = com.battery.analysis.domain.estimator.model.FullChargeEnergyEstimate(
                energyWh = totalCapacityWh.toDouble(),
                source = energySource,
                quality = com.battery.analysis.domain.estimator.model.DataQuality.VALID,
                notes = setOf("基于$sourceDesc (${String.format(Locale.US, "%.2fWh", totalCapacityWh)})")
            )
            val v2Normalizer = com.battery.analysis.domain.estimator.PowerSampleNormalizer()
            val runningDraftPoints = if (currentOverview != null && currentTrendPoints.isNullOrEmpty()) {
                allRecords.firstOrNull { !it.isCompleted }?.let { rec ->
                    com.battery.analysis.domain.estimator.PowerSampleNormalizer.parseTrendPoints(rec.trendPointsJson)
                }
            } else null
            val effectiveTrendPoints = currentTrendPoints ?: runningDraftPoints
            val (v2Intervals, v2Metrics) = v2Normalizer.extractAdaptiveDischargeIntervals(
                currentOverview = currentOverview,
                allRecords = allRecords,
                baseTimestamp = baseTimestamp,
                currentTrendPoints = effectiveTrendPoints
            )
            v2Estimator.estimateFromIntervals(v2Intervals, v2EnergyEstimate, v2Metrics)
        } catch (_: Exception) {
            null
        }

        try {
            val sb = StringBuilder()
            sb.append("\n================= 满电续航底层高精度核算日志 =================")
            sb.append(String.format(Locale.US, "\n1. 电池可用总容量: %.6f Wh", totalCapacityWh))
            sb.append("\n2. 升级前算术平均:")
            val onHours = onAcc.accumulatedDurationMs / 3600000.0
            val rawAvgOnWatts = if (onHours > 0.0) onAcc.accumulatedEnergyWh / onHours else 0.0
            sb.append(String.format(Locale.US, "\n  - 亮屏总时长: %d ms (%s)", onAcc.accumulatedDurationMs, BatteryEnergyCalculator.formatDurationWithSeconds(onAcc.accumulatedDurationMs)))
            sb.append(String.format(Locale.US, "\n  - 亮屏总能量: %.6f Wh", onAcc.accumulatedEnergyWh))
            sb.append(String.format(Locale.US, "\n  - 原始平均功耗: %.6f W (速率: %.4f %%/h)", rawAvgOnWatts, screenOnRate ?: 0f))
            sb.append(String.format(Locale.US, "\n  - 满电亮屏续航: %d ms (%s)", fullOnDuration ?: 0L, BatteryEnergyCalculator.formatDurationWithSeconds(fullOnDuration)))
            if (v2Result != null) {
                sb.append("\n3. 升级模型 v2.2 工况细分 (原始未舍入数值):")
                val prof = v2Result.profile
                if (prof != null) {
                    sb.append(String.format(Locale.US, "\n  - 轻载工况: 原始占比=%.6f%%, 原始功耗=%.6f W", prof.lightRatio * 100.0, prof.lightPowerW ?: 0.0))
                    sb.append(String.format(Locale.US, "\n  - 普通工况: 原始占比=%.6f%%, 原始功耗=%.6f W", prof.normalRatio * 100.0, prof.normalPowerW ?: 0.0))
                    sb.append(String.format(Locale.US, "\n  - 重载工况: 原始占比=%.6f%%, 原始功耗=%.6f W", prof.heavyRatio * 100.0, prof.heavyPowerW ?: 0.0))
                }
                val v2OnWatts = v2Result.predictedScreenOnPowerWatts ?: 0.0
                val v2OnMs = v2Result.screenOnFullRuntimeMs ?: 0L
                sb.append(String.format(Locale.US, "\n  - 加权典型功耗: %.6f W", v2OnWatts))
                sb.append(String.format(Locale.US, "\n  - 满电亮屏续航: %d ms (%s)", v2OnMs, BatteryEnergyCalculator.formatDurationWithSeconds(v2OnMs)))
                if (fullOnDuration != null && v2OnMs > 0L) {
                    val diffMs = v2OnMs - fullOnDuration
                    val diffW = v2OnWatts - rawAvgOnWatts
                    sb.append(String.format(Locale.US, "\n4. 新旧模型绝对差值: 功耗差=%.6f W, 续航差=%d ms (%.3f 秒)", diffW, diffMs, diffMs / 1000.0))
                }
            }
            sb.append("\n=============================================================")
            android.util.Log.d("FullChargeEstimator", sb.toString())
        } catch (_: Throwable) {}

        return SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = screenOnRate,
            screenOffDischargeRatePercentPerHour = screenOffRate,
            globalDischargeRatePercentPerHour = globalRate,
            screenOnDurationMs = actualOnDur,
            screenOffDurationMs = actualOffDur,
            globalDurationMs = actualGlobalDur,
            fullChargeScreenOnDurationMs = fullOnDuration,
            fullChargeScreenOffDurationMs = fullOffDuration,
            fullChargeGlobalDurationMs = fullGlobalDuration,
            sceneScreenOnRatio = sceneRatio,
            sceneCompositeRatePercentPerHour = sceneRate,
            fullChargeSceneCompositeDurationMs = sceneFullDuration,
            v2Result = v2Result
        )
    }

    /**
     * 计算指定能量与时长下的每小时放电百分比速率（%/h）。
     *
     * @param totalEnergyWh 消耗总物理能量（单位：Wh）
     * @param durationMs 对应总物理时长（单位：毫秒）
     * @param totalCapacityWh 电池标称总能量容量（单位：Wh）
     * @return 每小时放电速率百分比（%/h），无数据或无效时返回 null
     */
    fun computeRate(totalEnergyWh: Double, durationMs: Long, totalCapacityWh: Float): Float? {
        if (durationMs <= 0L || totalEnergyWh <= 0.0 || totalCapacityWh <= 0f) {
            return null
        }
        val durationHours = durationMs / 3600000.0
        val avgPowerWatts = totalEnergyWh / durationHours
        val rate = (avgPowerWatts / totalCapacityWh) * 100.0
        return if (rate > 0.0) rate.toFloat() else null
    }

    /**
     * 根据放电速率严谨推算充满电（100%）理论可用物理时长（单位：毫秒）。
     *
     * @param ratePercentPerHour 每小时放电百分比速率（%/h）
     * @return 充满电理论续航物理毫秒数，速率缺失或无效时返回 null
     */
    fun computeFullDurationMs(ratePercentPerHour: Float?): Long? {
        if (ratePercentPerHour == null || ratePercentPerHour <= 0f) {
            return null
        }
        val fullHours = 100.0 / ratePercentPerHour.toDouble()
        val fullMs = Math.round(fullHours * 3600.0 * 1000.0)
        return if (fullMs > 0L) fullMs else null
    }

    /**
     * 将紧凑时长字符串解析还原为物理毫秒数。
     *
     * @param text 格式化时长字符串（如 "1d2h"、"3h20m"）
     * @return 解析得到的物理毫秒数
     */
    fun parseDurationTextToMs(text: String): Long {
        if (text.isBlank() || text == "--") return 0L
        var totalMs = 0L
        val dMatch = Regex("(\\d+)d").find(text)
        val hMatch = Regex("(\\d+)h").find(text)
        val mMatch = Regex("(\\d+)m").find(text)
        val sMatch = Regex("(\\d+)s").find(text)

        dMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 86400000L }
        hMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 3600000L }
        mMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 60000L }
        sMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
        return totalMs
    }
}
