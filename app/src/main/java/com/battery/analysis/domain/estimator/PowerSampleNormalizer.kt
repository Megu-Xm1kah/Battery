package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.AdaptiveWindowMetrics
import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.DataSufficiencyLevel
import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerUsageRecord
import kotlin.math.exp

/**
 * 历史放电采样与物理区间规范化归一器。
 * 负责从设备本地持久化的历史放电快照 [PowerUsageRecord] 及当前正在进行的活跃放电会话 [PowerOverviewStats] 中，
 * 按时间倒序解析并提取不重叠、时间单调的有效放电物理区间序列，
 * 严格过滤充电记录与异常数据，并在跨越 168 小时（7 天物理使用时间）边界时执行精确的物理等比裁剪，
 * 确保各区间放电功率物理守恒、能量严格守恒。
 */
class PowerSampleNormalizer {

    companion object {
        /** 7 天基准自然墙钟毫秒时长常量（7 * 24 * 3600 * 1000 毫秒 = 168 小时） */
        const val SEVEN_DAYS_MS: Long = 7L * 24L * 3600L * 1000L

        /** 7 天优先基准自然时间窗口毫秒数 */
        const val BASE_WINDOW_MS: Long = 7L * 24L * 3600L * 1000L

        /** 自适应历史回溯最大硬上限毫秒数（30 天自然时间 = 30 * 24 * 3600 * 1000 毫秒） */
        const val MAX_WINDOW_MS: Long = 30L * 24L * 3600L * 1000L

        /** 历史样本时间指数衰减半衰时间常数 tau（单位：天，默认 7.0 天） */
        const val TAU_AGE_DAYS: Double = 7.0

        /** 每天的物理毫秒数常量（24 * 3600 * 1000） */
        const val MS_PER_DAY: Double = 86_400_000.0
    }

    /**
     * 从当前实时放电会话及历史归档记录中提取并构建滚动 168 小时（7 天）墙钟时间窗口内的有效放电物理区间列表。
     *
     * 核心逻辑升级（RuntimeModel v2.1）：
     * 严格将历史统计范围界定为当前时刻向前滚动 168 小时的真实自然时间窗口：[baseTimestamp - 168h, baseTimestamp]，
     * 仅在该窗口内筛选纯放电区段，并在边界区间跨越 168 小时墙钟起点时执行精确的物理等比截断，
     * 杜绝将“回看 7 天”错误实现为“累积凑满 168 小时放电时长”所导致的过度回溯历史陈旧机况（轻度使用甚至回溯一个半月前）。
     *
     * @param currentOverview 当前正在进行的活跃放电会话概览统计（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表（已默认按时间倒序排列）
     * @param maxTargetDurationMs 最大墙钟回溯时间跨度上限（单位：毫秒，默认 168 小时）
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @return 落在 168 小时滚动墙钟窗口内的有效放电物理区间列表 [List]<[DischargeInterval]>
     */
    fun extractDischargeIntervals(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        maxTargetDurationMs: Long = SEVEN_DAYS_MS,
        baseTimestamp: Long = System.currentTimeMillis()
    ): List<DischargeInterval> {
        val intervals = mutableListOf<DischargeInterval>()
        val windowStartTs = baseTimestamp - maxTargetDurationMs

        /**
         * 内部辅助函数：向结果集合中安全追加物理区间。
         * 若区间完全落在 168 小时滚动墙钟窗口内则直接纳入；
         * 若跨越窗口边界（startTimeMs < windowStartTs 且 endTimeMs > windowStartTs），则在边界处等比截断；
         * 若区间完全早于窗口起始点（endTimeMs <= windowStartTs），则忽略并指示无需继续遍历更早记录。
         *
         * @param interval 待追加的完整放电物理区间
         * @return 是否已完全超出 168 小时墙钟窗口起始边界（true 为已早于窗口，无需继续遍历历史记录）
         */
        fun tryAppendInterval(interval: DischargeInterval): Boolean {
            if (interval.durationMs <= 0L || interval.energyWh <= 0.0 || !interval.powerWatts.isFinite()) {
                return false
            }

            // 区间完全早于 168 小时自然时间窗口起点，终止遍历
            if (interval.endTimeMs <= windowStartTs) {
                return true
            }

            if (interval.startTimeMs >= windowStartTs) {
                // 完全在窗口内
                intervals.add(interval)
                return false
            } else {
                // 跨越 168 小时墙钟窗口边界：执行边界物理等比裁剪
                val validDurMs = interval.endTimeMs - windowStartTs
                if (validDurMs > 0L) {
                    val ratio = validDurMs.toDouble() / interval.durationMs.toDouble()
                    val clippedEnergy = interval.energyWh * ratio
                    val clippedInterval = DischargeInterval(
                        startTimeMs = windowStartTs,
                        endTimeMs = interval.endTimeMs,
                        durationMs = validDurMs,
                        energyWh = clippedEnergy,
                        powerWatts = interval.powerWatts, // 平均功率保持守恒
                        isScreenOn = interval.isScreenOn,
                        quality = interval.quality
                    )
                    intervals.add(clippedInterval)
                }
                return true // 已经到达窗口边界，更早的记录无需继续遍历
            }
        }

        // 1. 优先解构当前正在进行的活跃会话
        if (currentOverview != null) {
            val now = baseTimestamp
            val onDur = currentOverview.screenOnDurationMs
            val onEnergy = if (currentOverview.screenOnEnergyWh > 0f) {
                currentOverview.screenOnEnergyWh.toDouble()
            } else if (currentOverview.screenOnPowerWatts > 0f && onDur > 0L) {
                currentOverview.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            if (onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                val fullAppend = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = now - onDur,
                        endTimeMs = now,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID
                    )
                )
                if (fullAppend) return intervals
            }

            val offDur = currentOverview.screenOffDurationMs
            val offEnergy = if (currentOverview.screenOffEnergyWh > 0f) {
                currentOverview.screenOffEnergyWh.toDouble()
            } else if (currentOverview.screenOffPowerWatts > 0f && offDur > 0L) {
                currentOverview.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
            } else 0.0

            if (offDur > 0L && offEnergy > 0.0) {
                val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                val fullAppend = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = now - onDur - offDur,
                        endTimeMs = now - onDur,
                        durationMs = offDur,
                        energyWh = offEnergy,
                        powerWatts = pOff,
                        isScreenOn = false,
                        quality = DataQuality.VALID
                    )
                )
                if (fullAppend) return intervals
            }
        }

        // 2. 倒序解构历史归档记录
        val eligibleRecords = allRecords.filter { record ->
            !record.isCharging && record.id <= (baseTimestamp + 60_000L)
        }

        for (record in eligibleRecords) {
            // 若历史记录中包含未完结草稿且当前已存在实时会话，跳过旧草稿以最新实时数据为准
            if (!record.isCompleted && currentOverview != null) {
                continue
            }

            val recEndTs = record.id
            val onDur = record.getScreenOnDurationMs()
            val onEnergy = if (record.screenOnEnergyWh > 0f) {
                record.screenOnEnergyWh.toDouble()
            } else if (record.screenOnPowerWatts > 0f && onDur > 0L) {
                record.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            if (onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                val isFull = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = recEndTs - onDur,
                        endTimeMs = recEndTs,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID
                    )
                )
                if (isFull) break
            }

            // 息屏细分：若具备深度休眠与唤醒的细分数据，则拆分为两个更具物理代表性的独立区间
            val hasDecomposed = record.isScreenOffDecomposedAvailable &&
                    (record.screenOffAwakeDurationMs > 0L || record.screenOffDeepSleepDurationMs > 0L)

            if (hasDecomposed) {
                val awakeDur = record.screenOffAwakeDurationMs
                val awakeEnergy = record.screenOffAwakeEnergyWh.toDouble()
                if (awakeDur > 0L && awakeEnergy > 0.0) {
                    val pAwake = awakeEnergy / (awakeDur.toDouble() / 3600000.0)
                    val isFull = tryAppendInterval(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - awakeDur,
                            endTimeMs = recEndTs - onDur,
                            durationMs = awakeDur,
                            energyWh = awakeEnergy,
                            powerWatts = pAwake,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isFull) break
                }

                val sleepDur = record.screenOffDeepSleepDurationMs
                val sleepEnergy = record.screenOffDeepSleepEnergyWh.toDouble()
                if (sleepDur > 0L && sleepEnergy > 0.0) {
                    val pSleep = sleepEnergy / (sleepDur.toDouble() / 3600000.0)
                    val isFull = tryAppendInterval(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - awakeDur - sleepDur,
                            endTimeMs = recEndTs - onDur - awakeDur,
                            durationMs = sleepDur,
                            energyWh = sleepEnergy,
                            powerWatts = pSleep,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isFull) break
                }
            } else {
                // 无细分时作为综合息屏区间纳入
                val offDur = record.getScreenOffDurationMs()
                val offEnergy = if (record.screenOffEnergyWh > 0f) {
                    record.screenOffEnergyWh.toDouble()
                } else if (record.screenOffPowerWatts > 0f && offDur > 0L) {
                    record.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
                } else 0.0

                if (offDur > 0L && offEnergy > 0.0) {
                    val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                    val isFull = tryAppendInterval(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - offDur,
                            endTimeMs = recEndTs - onDur,
                            durationMs = offDur,
                            energyWh = offEnergy,
                            powerWatts = pOff,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isFull) break
                }
            }
        }

        return intervals
    }

    /**
     * 计算历史放电区间相对于当前基准时刻的时间衰减权重 w_age。
     * 7 天以内的区间为全额权重 1.0；
     * 超出 7 天（7 ~ 30 天）的区间按照指数函数平滑衰减：w_age = exp(-(a - 7.0) / tau)；
     * 超出 30 天的区间硬截断为 0.0。
     *
     * @param endTimestampMs 放电区间结束系统时间戳（毫秒）
     * @param baseTimestamp 当前统计基准系统时间戳（毫秒）
     * @param tauDays 时间衰减半衰时间常数（单位：天，默认 7.0 天）
     * @return 介于 0.0 至 1.0 之间的时间衰减权重
     */
    fun calculateAgeWeight(
        endTimestampMs: Long,
        baseTimestamp: Long,
        tauDays: Double = TAU_AGE_DAYS
    ): Double {
        if (endTimestampMs >= baseTimestamp) return 1.0
        val ageDays = (baseTimestamp - endTimestampMs).toDouble() / MS_PER_DAY
        return when {
            ageDays <= 7.0 -> 1.0
            ageDays <= 30.0 -> exp(-(ageDays - 7.0) / tauDays).coerceIn(0.01, 1.0)
            else -> 0.0
        }
    }

    /**
     * 评估已收集的历史放电区间在时间充分性与工况类别覆盖度上的数据充分性等级。
     *
     * 评定准则：
     * 1. 充足（SUFFICIENT）：亮屏有效时长 >= 6h、息屏有效时长 >= 12h、覆盖 >= 2 类基础工况且总时长 >= 18h；
     * 2. 基本可用（MODERATE）：总有效时长 >= 6h 且同时具备亮屏与息屏数据；
     * 3. 样本较少（INSUFFICIENT）：总有效时长 < 6h，或严重缺失单一核心工况。
     *
     * @param intervals 待评估的放电物理区间集合
     * @return 包含数据充分性等级 [DataSufficiencyLevel] 与覆盖核心工况类别数的二元组 [Pair]
     */
    fun evaluateSufficiency(intervals: List<DischargeInterval>): Pair<DataSufficiencyLevel, Int> {
        val onDur = intervals.filter { it.isScreenOn }.sumOf { it.durationMs }
        val offDur = intervals.filter { !it.isScreenOn }.sumOf { it.durationMs }
        val totalDur = intervals.sumOf { it.durationMs }

        var regimeCount = 0
        if (onDur > 0L) regimeCount++
        if (offDur > 0L) regimeCount++

        val sufficiency = when {
            onDur >= 6L * 3600_000L && offDur >= 12L * 3600_000L && totalDur >= 18L * 3600_000L && regimeCount >= 2 -> {
                DataSufficiencyLevel.SUFFICIENT
            }
            totalDur >= 6L * 3600_000L && onDur > 0L && offDur > 0L -> {
                DataSufficiencyLevel.MODERATE
            }
            else -> {
                DataSufficiencyLevel.INSUFFICIENT
            }
        }
        return Pair(sufficiency, regimeCount)
    }

    /**
     * 自适应历史时间窗口放电物理区间提取器（RuntimeModel v2.1 核心算法）。
     *
     * 遵循四级自适应架构：
     * 1. 第一优先级（近 7 天基准优先）：优先收集最近 168 小时（7 天自然墙钟）内的有效放电区间，全额权重（w_age = 1.0）；
     * 2. 第二优先级（充分性门控检查）：评估 7 天内样本充分性；若已满足充足条件则立即停止回溯，锁定统计范围为最近 7 天；
     * 3. 第三优先级（按需扩展至最多 30 天与时间衰减权重）：若 7 天样本不足，自适应继续向前扩展，并对超出 7 天的旧记录赋予时间衰减权重；
     * 4. 第四优先级（30 天硬上限截断与充分性如实评定）：若回溯到达 30 天上限仍不足，停止回溯并如实标记为数据偏少（严格遵守零假数据原则）。
     *
     * @param currentOverview 当前正在进行的活跃放电会话概览（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @param tauDays 时间衰减半衰常数（单位：天，默认 7.0 天）
     * @return 包含自适应提取的放电区间集合与对应窗口元信息指标的二元组 [Pair]<[List]<[DischargeInterval]>, [AdaptiveWindowMetrics]>
     */
    fun extractAdaptiveDischargeIntervals(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        baseTimestamp: Long = System.currentTimeMillis(),
        tauDays: Double = TAU_AGE_DAYS
    ): Pair<List<DischargeInterval>, AdaptiveWindowMetrics> {
        // 阶段 1：优先提取最近 7 天（168 小时自然墙钟时间）内的放电物理区间
        val base7dIntervals = extractDischargeIntervals(
            currentOverview = currentOverview,
            allRecords = allRecords,
            maxTargetDurationMs = BASE_WINDOW_MS,
            baseTimestamp = baseTimestamp
        ).map { it.copy(ageWeight = 1.0) }

        val (baseSufficiency, baseRegimeCount) = evaluateSufficiency(base7dIntervals)

        // 若最近 7 天内样本已达到充分（SUFFICIENT），立即锁定窗口为 7.0 天，绝不读取 7 天前陈旧记录
        if (baseSufficiency == DataSufficiencyLevel.SUFFICIENT) {
            val effDischargeMs = base7dIntervals.sumOf { it.durationMs }
            val onDurMs = base7dIntervals.filter { it.isScreenOn }.sumOf { it.durationMs }
            val offDurMs = base7dIntervals.filter { !it.isScreenOn }.sumOf { it.durationMs }
            val metrics = AdaptiveWindowMetrics(
                windowDurationMs = BASE_WINDOW_MS,
                windowDays = 7.0,
                effectiveDischargeDurationMs = effDischargeMs,
                screenOnDurationMs = onDurMs,
                screenOffDurationMs = offDurMs,
                sufficiency = DataSufficiencyLevel.SUFFICIENT,
                regimeCount = baseRegimeCount
            )
            return Pair(base7dIntervals, metrics)
        }

        // 阶段 2：7 天样本不足，自适应向前扩展至最多 30 天，并应用时间指数衰减权重
        val window30dStartTs = baseTimestamp - MAX_WINDOW_MS
        val intervals = mutableListOf<DischargeInterval>()
        var earliestIncludedTs = baseTimestamp

        /**
         * 内部辅助函数：向自适应集合中追加区间，并动态计算时间衰减权重 w_age。
         * 超出 30 天硬上限时在边界处进行等比物理截断并终止。
         *
         * @param interval 原始放电区间
         * @return 是否已到达或超出 30 天最大回溯硬上限边界
         */
        fun tryAppendAdaptive(interval: DischargeInterval): Boolean {
            if (interval.durationMs <= 0L || interval.energyWh <= 0.0 || !interval.powerWatts.isFinite()) {
                return false
            }
            if (interval.endTimeMs <= window30dStartTs) {
                return true
            }

            if (interval.startTimeMs >= window30dStartTs) {
                val weight = calculateAgeWeight(interval.endTimeMs, baseTimestamp, tauDays)
                val weightedInterval = interval.copy(ageWeight = weight)
                intervals.add(weightedInterval)
                if (interval.startTimeMs < earliestIncludedTs) {
                    earliestIncludedTs = interval.startTimeMs
                }
                return false
            } else {
                // 跨越 30 天硬边界等比截断
                val validDurMs = interval.endTimeMs - window30dStartTs
                if (validDurMs > 0L) {
                    val ratio = validDurMs.toDouble() / interval.durationMs.toDouble()
                    val clippedEnergy = interval.energyWh * ratio
                    val weight = calculateAgeWeight(interval.endTimeMs, baseTimestamp, tauDays)
                    val clippedInterval = DischargeInterval(
                        startTimeMs = window30dStartTs,
                        endTimeMs = interval.endTimeMs,
                        durationMs = validDurMs,
                        energyWh = clippedEnergy,
                        powerWatts = interval.powerWatts,
                        isScreenOn = interval.isScreenOn,
                        quality = interval.quality,
                        ageWeight = weight
                    )
                    intervals.add(clippedInterval)
                    earliestIncludedTs = window30dStartTs
                }
                return true
            }
        }

        // 1. 优先纳入当前活跃会话
        if (currentOverview != null) {
            val now = baseTimestamp
            val onDur = currentOverview.screenOnDurationMs
            val onEnergy = if (currentOverview.screenOnEnergyWh > 0f) {
                currentOverview.screenOnEnergyWh.toDouble()
            } else if (currentOverview.screenOnPowerWatts > 0f && onDur > 0L) {
                currentOverview.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            if (onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                tryAppendAdaptive(
                    DischargeInterval(
                        startTimeMs = now - onDur,
                        endTimeMs = now,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID,
                        ageWeight = 1.0
                    )
                )
            }

            val offDur = currentOverview.screenOffDurationMs
            val offEnergy = if (currentOverview.screenOffEnergyWh > 0f) {
                currentOverview.screenOffEnergyWh.toDouble()
            } else if (currentOverview.screenOffPowerWatts > 0f && offDur > 0L) {
                currentOverview.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
            } else 0.0

            if (offDur > 0L && offEnergy > 0.0) {
                val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                tryAppendAdaptive(
                    DischargeInterval(
                        startTimeMs = now - onDur - offDur,
                        endTimeMs = now - onDur,
                        durationMs = offDur,
                        energyWh = offEnergy,
                        powerWatts = pOff,
                        isScreenOn = false,
                        quality = DataQuality.VALID,
                        ageWeight = 1.0
                    )
                )
            }
        }

        // 2. 倒序遍历历史记录
        val eligibleRecords = allRecords.filter { record ->
            !record.isCharging && record.id <= (baseTimestamp + 60_000L)
        }

        for (record in eligibleRecords) {
            if (!record.isCompleted && currentOverview != null) {
                continue
            }

            val recEndTs = record.id
            val onDur = record.getScreenOnDurationMs()
            val onEnergy = if (record.screenOnEnergyWh > 0f) {
                record.screenOnEnergyWh.toDouble()
            } else if (record.screenOnPowerWatts > 0f && onDur > 0L) {
                record.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            if (onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                val isMaxReached = tryAppendAdaptive(
                    DischargeInterval(
                        startTimeMs = recEndTs - onDur,
                        endTimeMs = recEndTs,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID
                    )
                )
                if (isMaxReached) break
            }

            val hasDecomposed = record.isScreenOffDecomposedAvailable &&
                    (record.screenOffAwakeDurationMs > 0L || record.screenOffDeepSleepDurationMs > 0L)

            if (hasDecomposed) {
                val awakeDur = record.screenOffAwakeDurationMs
                val awakeEnergy = record.screenOffAwakeEnergyWh.toDouble()
                if (awakeDur > 0L && awakeEnergy > 0.0) {
                    val pAwake = awakeEnergy / (awakeDur.toDouble() / 3600000.0)
                    val isMaxReached = tryAppendAdaptive(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - awakeDur,
                            endTimeMs = recEndTs - onDur,
                            durationMs = awakeDur,
                            energyWh = awakeEnergy,
                            powerWatts = pAwake,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isMaxReached) break
                }

                val sleepDur = record.screenOffDeepSleepDurationMs
                val sleepEnergy = record.screenOffDeepSleepEnergyWh.toDouble()
                if (sleepDur > 0L && sleepEnergy > 0.0) {
                    val pSleep = sleepEnergy / (sleepDur.toDouble() / 3600000.0)
                    val isMaxReached = tryAppendAdaptive(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - awakeDur - sleepDur,
                            endTimeMs = recEndTs - onDur - awakeDur,
                            durationMs = sleepDur,
                            energyWh = sleepEnergy,
                            powerWatts = pSleep,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isMaxReached) break
                }
            } else {
                val offDur = record.getScreenOffDurationMs()
                val offEnergy = if (record.screenOffEnergyWh > 0f) {
                    record.screenOffEnergyWh.toDouble()
                } else if (record.screenOffPowerWatts > 0f && offDur > 0L) {
                    record.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
                } else 0.0

                if (offDur > 0L && offEnergy > 0.0) {
                    val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                    val isMaxReached = tryAppendAdaptive(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - offDur,
                            endTimeMs = recEndTs - onDur,
                            durationMs = offDur,
                            energyWh = offEnergy,
                            powerWatts = pOff,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isMaxReached) break
                }
            }

            // 门控检查：已收集样本达到了充分等级，停止继续回溯更早记录
            val (currentSufficiency, _) = evaluateSufficiency(intervals)
            if (currentSufficiency == DataSufficiencyLevel.SUFFICIENT) {
                break
            }
        }

        // 3. 构建自适应统计指标
        val (finalSufficiency, regimeCount) = evaluateSufficiency(intervals)
        val rawWindowMs = (baseTimestamp - earliestIncludedTs).coerceAtLeast(0L)
        val effectiveWindowMs = when {
            rawWindowMs > MAX_WINDOW_MS -> MAX_WINDOW_MS
            rawWindowMs < BASE_WINDOW_MS -> BASE_WINDOW_MS
            else -> rawWindowMs
        }
        val windowDays = (effectiveWindowMs.toDouble() / MS_PER_DAY).coerceIn(7.0, 30.0)
        val effectiveDischargeMs = intervals.sumOf { it.durationMs }
        val onDurMs = intervals.filter { it.isScreenOn }.sumOf { it.durationMs }
        val offDurMs = intervals.filter { !it.isScreenOn }.sumOf { it.durationMs }

        val metrics = AdaptiveWindowMetrics(
            windowDurationMs = effectiveWindowMs,
            windowDays = windowDays,
            effectiveDischargeDurationMs = effectiveDischargeMs,
            screenOnDurationMs = onDurMs,
            screenOffDurationMs = offDurMs,
            sufficiency = finalSufficiency,
            regimeCount = regimeCount
        )

        return Pair(intervals, metrics)
    }
}
