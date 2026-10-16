package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.*
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerUsageRecord
import kotlin.math.roundToLong

/**
 * 满电理论续航核心推算引擎（FullChargeRuntimeEstimator v2.0）。
 * 基于 168 小时（7 天物理使用时间）真实历史放电区间构建工况条件化典型行为模型，
 * 遵循严格物理守恒：T_full = E_full,usable / P_typical,future。
 * 当工况类别覆盖充分时启用行为条件化高阶模型；当样本分布不足时平滑安全降级至全局加权均值基准，
 * 严格秉持零虚构数据原则：无能量数据或历史不足时如实返回 null。
 */
class FullChargeRuntimeEstimator(
    private val energyEstimator: FullChargeEnergyEstimator = FullChargeEnergyEstimator(),
    private val sampleNormalizer: PowerSampleNormalizer = PowerSampleNormalizer(),
    private val classifier: PowerRegimeClassifier = PowerRegimeClassifier(),
    private val profileEstimator: UsageProfileEstimator = UsageProfileEstimator(),
    private val regimePowerEstimator: RegimePowerEstimator = RegimePowerEstimator()
) {

    /**
     * 基于预先提取并裁剪后的有效放电物理区间集合，推算设备满电理论可用续航结果。
     *
     * @param intervals 经过自适应历史时间窗口提取并带有时间衰减权重的有效放电物理区间列表
     * @param energyEstimate 满充物理可用能量估算结果 [FullChargeEnergyEstimate]
     * @param windowMetrics 可选的自适应窗口元信息指标 [AdaptiveWindowMetrics]
     * @return 满电续航统一推算结果聚合实体 [FullChargeRuntimeResult]
     */
    fun estimateFromIntervals(
        intervals: List<DischargeInterval>,
        energyEstimate: FullChargeEnergyEstimate,
        windowMetrics: AdaptiveWindowMetrics? = null
    ): FullChargeRuntimeResult {
        val fullWh = energyEstimate.energyWh
        if (fullWh == null || fullWh <= 0.0 || energyEstimate.source == EnergySource.UNAVAILABLE) {
            return FullChargeRuntimeResult(
                fullRuntimeMs = null,
                fullEnergyWh = null,
                predictedPowerWatts = null,
                modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
                confidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE,
                energySource = energyEstimate.source,
                effectiveHistoryMs = 0L,
                usedRegimeModel = false,
                fallbackReason = "满充物理总能量不可用",
                historyWindowDays = windowMetrics?.windowDays ?: 7.0,
                effectiveDischargeDurationMs = 0L,
                dataSufficiency = DataSufficiencyLevel.INSUFFICIENT
            )
        }

        val totalHistoryMs = intervals.sumOf { it.durationMs }
        val weightedHistoryMs = intervals.sumOf { it.durationMs.toDouble() * it.ageWeight }
        val weightedHistoryWh = intervals.sumOf { it.energyWh * it.ageWeight }

        val (evalSufficiency, _) = sampleNormalizer.evaluateSufficiency(intervals)
        val finalSufficiency = windowMetrics?.sufficiency ?: evalSufficiency
        val windowDays = windowMetrics?.windowDays ?: 7.0

        // 若历史有效放电记录不足 10 分钟，无法建立统计模型，如实返回未知
        if (totalHistoryMs < 600_000L || weightedHistoryMs <= 0.0 || weightedHistoryWh <= 0.0) {
            return FullChargeRuntimeResult(
                fullRuntimeMs = null,
                fullEnergyWh = fullWh,
                predictedPowerWatts = null,
                modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
                confidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE,
                energySource = energyEstimate.source,
                effectiveHistoryMs = totalHistoryMs,
                usedRegimeModel = false,
                fallbackReason = "历史有效放电时长不足 10 分钟",
                historyWindowDays = windowDays,
                effectiveDischargeDurationMs = totalHistoryMs,
                dataSufficiency = DataSufficiencyLevel.INSUFFICIENT
            )
        }

        // 1. 先计算全局历史物理加权平均功率 P_global 作为基准与降级底线 (引入时间衰减权重)
        val pGlobal = weightedHistoryWh / (weightedHistoryMs / 3600000.0)

        // 2. 按工况归集放电物理区间 (累计加权持续时长)
        val intervalsByRegime = mutableMapOf<PowerRegime, MutableList<DischargeInterval>>()
        val durByRegime = mutableMapOf<PowerRegime, Double>()

        for (interval in intervals) {
            val regime = classifier.classify(interval)
            intervalsByRegime.getOrPut(regime) { mutableListOf() }.add(interval)
            val weightedDur = interval.durationMs.toDouble() * interval.ageWeight
            durByRegime[regime] = (durByRegime[regime] ?: 0.0) + weightedDur
        }

        // 3. 计算各工况代表功率与经衰减加权的时间占比概率
        val regimePowers = regimePowerEstimator.estimateAllRegimePowers(intervalsByRegime)
        val ratios = profileEstimator.computeWeightedTimeRatios(durByRegime)

        var pTypicalFuture = 0.0
        var validRegimeCount = 0

        for ((regime, ratio) in ratios) {
            val pRegime = regimePowers[regime]
            if (pRegime != null && pRegime > 0.0) {
                pTypicalFuture += ratio * pRegime
                validRegimeCount++
            }
        }

        // 4. 判定工况模型是否充分有效（至少覆盖 2 类有效核心工况）
        val canUseRegimeModel = (validRegimeCount >= 2 && pTypicalFuture > 0.0 && pTypicalFuture.isFinite())
        val finalPredictedPower = if (canUseRegimeModel) pTypicalFuture else pGlobal

        if (finalPredictedPower <= 0.0 || !finalPredictedPower.isFinite()) {
            return FullChargeRuntimeResult(
                fullRuntimeMs = null,
                fullEnergyWh = fullWh,
                predictedPowerWatts = null,
                modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
                confidenceLevel = RuntimeConfidenceLevel.UNAVAILABLE,
                energySource = energyEstimate.source,
                effectiveHistoryMs = totalHistoryMs,
                usedRegimeModel = false,
                fallbackReason = "计算预测功率非有限正数",
                historyWindowDays = windowDays,
                effectiveDischargeDurationMs = totalHistoryMs,
                dataSufficiency = finalSufficiency
            )
        }

        // 5. 核心物理公式推导满电理论可用时长：T_full = E_full / P_typical
        val fullHours = fullWh / finalPredictedPower
        val fullMs = (fullHours * 3600.0 * 1000.0).roundToLong()

        // 6. 亮屏工况代表性功耗与满电亮屏续航推导（v2.1 加权积分）
        val onIntervals = intervals.filter { it.isScreenOn }
        val onDurTotal = onIntervals.sumOf { it.durationMs }
        val onDurWeighted = onIntervals.sumOf { it.durationMs.toDouble() * it.ageWeight }
        val onEnergyWeighted = onIntervals.sumOf { it.energyWh * it.ageWeight }

        val (screenOnPower, screenOnMs) = if (onDurTotal >= 60_000L && onDurWeighted > 0.0 && onEnergyWeighted > 0.0) {
            val onRegimes = setOf(PowerRegime.ON_LIGHT, PowerRegime.ON_NORMAL, PowerRegime.ON_HEAVY)
            val onDurMap = durByRegime.filterKeys { it in onRegimes }
            val onRatios = profileEstimator.computeWeightedTimeRatios(onDurMap)
            var pTypicalOn = 0.0
            for ((r, ratio) in onRatios) {
                val p = regimePowers[r]
                if (p != null && p > 0.0) {
                    pTypicalOn += ratio * p
                }
            }
            val pEffectiveOn = if (pTypicalOn > 0.0) pTypicalOn else (onEnergyWeighted / (onDurWeighted / 3600000.0))
            val ms = if (pEffectiveOn > 0.0) ((fullWh / pEffectiveOn) * 3600.0 * 1000.0).roundToLong() else null
            Pair(pEffectiveOn, ms)
        } else {
            Pair(null, null)
        }

        // 7. 息屏工况代表性功耗与满电待机续航推导（v2.1 加权积分）
        val offIntervals = intervals.filter { !it.isScreenOn }
        val offDurTotal = offIntervals.sumOf { it.durationMs }
        val offDurWeighted = offIntervals.sumOf { it.durationMs.toDouble() * it.ageWeight }
        val offEnergyWeighted = offIntervals.sumOf { it.energyWh * it.ageWeight }

        val (screenOffPower, screenOffMs) = if (offDurTotal >= 60_000L && offDurWeighted > 0.0 && offEnergyWeighted > 0.0) {
            val offRegimes = setOf(PowerRegime.OFF_LOW, PowerRegime.OFF_HIGH)
            val offDurMap = durByRegime.filterKeys { it in offRegimes }
            val offRatios = profileEstimator.computeWeightedTimeRatios(offDurMap)
            var pTypicalOff = 0.0
            for ((r, ratio) in offRatios) {
                val p = regimePowers[r]
                if (p != null && p > 0.0) {
                    pTypicalOff += ratio * p
                }
            }
            val pEffectiveOff = if (pTypicalOff > 0.0) pTypicalOff else (offEnergyWeighted / (offDurWeighted / 3600000.0))
            val ms = if (pEffectiveOff > 0.0) ((fullWh / pEffectiveOff) * 3600.0 * 1000.0).roundToLong() else null
            Pair(pEffectiveOff, ms)
        } else {
            Pair(null, null)
        }

        // 8. 结合自适应数据充分性与工况完整度的综合置信度评级
        val confidence = when {
            finalSufficiency == DataSufficiencyLevel.SUFFICIENT && canUseRegimeModel -> RuntimeConfidenceLevel.HIGH
            finalSufficiency == DataSufficiencyLevel.MODERATE || (totalHistoryMs >= 3600000L * 6L) -> RuntimeConfidenceLevel.MEDIUM
            else -> RuntimeConfidenceLevel.LOW
        }

        val qualityReasons = mutableListOf<String>()
        qualityReasons.addAll(energyEstimate.notes)
        if (canUseRegimeModel) {
            qualityReasons.add("覆盖 $validRegimeCount 类典型工况行为建模")
        } else {
            qualityReasons.add("工况样本不足，降级至自适应历史全局加权均值")
        }
        if (windowDays > 7.1) {
            val daysStr = String.format(java.util.Locale.US, "%.1f", windowDays)
            qualityReasons.add("7天样本不足，自适应扩展至近 $daysStr 天 (衰减加权)")
        } else {
            qualityReasons.add("基于最近 7 天完整周期作息建模")
        }

        return FullChargeRuntimeResult(
            fullRuntimeMs = if (fullMs > 0L) fullMs else null,
            fullEnergyWh = fullWh,
            predictedPowerWatts = finalPredictedPower,
            modelVersion = RuntimeModelVersion.FULL_RUNTIME_V2_1,
            confidenceLevel = confidence,
            energySource = energyEstimate.source,
            effectiveHistoryMs = totalHistoryMs,
            usedRegimeModel = canUseRegimeModel,
            fallbackReason = if (!canUseRegimeModel) "工况样本类别不足，已安全降级至全局加权均值" else null,
            screenOnFullRuntimeMs = if (screenOnMs != null && screenOnMs > 0L) screenOnMs else null,
            screenOffFullRuntimeMs = if (screenOffMs != null && screenOffMs > 0L) screenOffMs else null,
            predictedScreenOnPowerWatts = screenOnPower,
            predictedScreenOffPowerWatts = screenOffPower,
            qualityReasons = qualityReasons,
            historyWindowDays = windowDays,
            effectiveDischargeDurationMs = totalHistoryMs,
            dataSufficiency = finalSufficiency,
            screenOnEffectiveMs = onDurTotal,
            screenOffEffectiveMs = offDurTotal
        )
    }

    /**
     * 一站式整合估算接口：从当前会话与历史快照直接提取自适应历史区间、估算能量并推算满电理论续航。
     * 自动应用 7~30 天自适应窗口展开、时间指数衰减与工况条件化典型行为模型。
     *
     * @param currentOverview 当前正在进行的活跃放电会话统计（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表
     * @param nativeEnergyNwh 硬件底层能量计数器（纳瓦时 nWh）
     * @param batteryPercent 当前电池电量百分比 (1..100)
     * @param fccMah 电量计实测真实满充容量 (mAh)
     * @param designCapacityMah 电池标称出厂设计容量 (mAh)
     * @param sohPercent 电池健康度百分比 (0.0..100.0)
     * @param baseTimestamp 统计基准时间戳（毫秒）
     * @return 满电续航统一推算结果聚合实体 [FullChargeRuntimeResult]
     */
    fun estimateFullRuntime(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        nativeEnergyNwh: Long? = null,
        batteryPercent: Int? = null,
        fccMah: Float? = null,
        designCapacityMah: Float? = null,
        sohPercent: Float? = null,
        baseTimestamp: Long = System.currentTimeMillis()
    ): FullChargeRuntimeResult {
        val energyEstimate = energyEstimator.estimateFullEnergy(
            nativeEnergyNwh = nativeEnergyNwh,
            batteryPercent = batteryPercent,
            fccMah = fccMah,
            designCapacityMah = designCapacityMah,
            sohPercent = sohPercent
        )

        val (intervals, metrics) = sampleNormalizer.extractAdaptiveDischargeIntervals(
            currentOverview = currentOverview,
            allRecords = allRecords,
            baseTimestamp = baseTimestamp
        )

        return estimateFromIntervals(intervals, energyEstimate, metrics)
    }
}
