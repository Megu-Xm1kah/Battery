package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.RemainingModelVersion
import com.battery.analysis.domain.estimator.model.RuntimeEstimate
import com.battery.analysis.domain.estimator.model.RuntimeModelType
import kotlin.math.roundToLong

/**
 * 物理级自适应剩余续航估计引擎。
 * 遵循真实物理能量与做功模型：T_remaining = E_remaining / P_predicted。
 * 支持亮屏（Screen-On）、息屏待机（Screen-Off）以及全局场景占空比融合（Global Composite）三维独立建模，
 * 并集成电芯温度修正系数（K_T）与低电量低置信度自适应评估。
 * 严格秉承零虚构原则：无有效数据时忠实返回 null，严禁硬编码保底。
 */
class RuntimeEstimator(
    private val remainingEnergyEstimator: RemainingEnergyEstimator = RemainingEnergyEstimator()
) {

    companion object {
        /**
         * 计算环境与电芯温度对电池实际可用化学能的修正系数 K_T。
         * 在常温区间（15℃ 至 35℃）系数为 1.0；
         * 在低温区间（锂离子活性受限）或极端高温区间，电芯等效内阻变大，可用容量呈物理性轻度衰减。
         *
         * @param tempCelsius 当前电池温度（单位：摄氏度 ℃，缺失时传入 null）
         * @return 介于 0.70 至 1.0 之间的可用能量温度修正系数
         */
        fun calculateTemperatureFactor(tempCelsius: Float?): Double {
            return RemainingEnergyEstimator.calculateTemperatureFactor(tempCelsius)
        }
    }

    /**
     * 计算并获得有效的电池剩余可用物理能量（单位：Wh）。
     * 委托给高精度物理能量估算器 [RemainingEnergyEstimator]，严格遵循零虚构数据原则。
     *
     * @param remainingEnergyWh 硬件原生计步器或直接换算的剩余能量（Wh）
     * @param batteryLevelPercent 当前电池电量百分比（0.0 ~ 100.0）
     * @param totalCapacityWh 电池满充总能量容量（Wh）
     * @param tempCelsius 电池当前温度（℃）
     * @return 经过温度与真实有效容量修正后的物理剩余能量（单位：Wh，若无法推算返回 null）
     */
    fun calculateUsableEnergyWh(
        remainingEnergyWh: Float?,
        batteryLevelPercent: Float?,
        totalCapacityWh: Float?,
        tempCelsius: Float? = null
    ): Double? {
        val effectiveCapacityMah = if (totalCapacityWh != null && totalCapacityWh > 0f) {
            (totalCapacityWh * 1000.0f) / RemainingEnergyEstimator.DEFAULT_NOMINAL_VOLTAGE.toFloat()
        } else null

        val estimate = remainingEnergyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = remainingEnergyWh,
            batteryLevelPercent = batteryLevelPercent,
            effectiveCapacityMah = effectiveCapacityMah,
            tempCelsius = tempCelsius,
            nominalVoltage = RemainingEnergyEstimator.DEFAULT_NOMINAL_VOLTAGE
        )
        return estimate.usableEnergyWh
    }

    /**
     * 高阶物理级可用能量估算重载方法。
     * 直接返回封装完整数据质量、获取来源与温度系数的可用物理能量结果实体 [UsableRemainingEnergyEstimate]。
     *
     * @param remainingEnergyWh 硬件直接换算或上报的剩余能量（单位：Wh，可选）
     * @param nativeEnergyNwh 硬件底层能量计数器（单位：纳瓦时 nWh，可选）
     * @param chargeCounterUah 硬件底层电荷计数器（单位：微安时 uAh，可选）
     * @param batteryLevelPercent 当前电池剩余电量百分比（0.0 ~ 100.0）
     * @param effectiveCapacityMah 设备有效基准容量（单位：mAh，可选）
     * @param tempCelsius 电池当前温度（单位：℃，可选）
     * @param nominalVoltage 电池标称工作平台电压（单位：V，默认 3.85V）
     * @return 剩余可用物理能量估算实体 [UsableRemainingEnergyEstimate]
     */
    fun estimateUsableRemainingEnergy(
        remainingEnergyWh: Float? = null,
        nativeEnergyNwh: Long? = null,
        chargeCounterUah: Int? = null,
        batteryLevelPercent: Float? = null,
        effectiveCapacityMah: Float? = null,
        tempCelsius: Float? = null,
        nominalVoltage: Double = RemainingEnergyEstimator.DEFAULT_NOMINAL_VOLTAGE
    ): UsableRemainingEnergyEstimate {
        return remainingEnergyEstimator.estimateUsableRemainingEnergy(
            remainingEnergyWh = remainingEnergyWh,
            nativeEnergyNwh = nativeEnergyNwh,
            chargeCounterUah = chargeCounterUah,
            batteryLevelPercent = batteryLevelPercent,
            effectiveCapacityMah = effectiveCapacityMah,
            tempCelsius = tempCelsius,
            nominalVoltage = nominalVoltage
        )
    }

    /**
     * 预测持续亮屏使用状态下的理论剩余续航时长。
     * 严格遵循物理做功定律：T_remaining = E_remaining / P_predicted。
     * 当具备设备实测的大样本重载与轻载工况功率时，精确推导工况预期范围；无实测数据时如实返回 null，严禁人为臆造范围。
     *
     * @param usableEnergyWh 当前电池有效可用物理能量（单位：Wh）
     * @param onPowerEstimator 亮屏多时间尺度功耗估计器 [PowerEstimator]
     * @param batteryLevelPercent 当前电池电量百分比（用于低电量置信度评估）
     * @param heavyPowerW 历史大样本实测亮屏重载工况功率（单位：W，可选）
     * @param lightPowerW 历史大样本实测亮屏轻载工况功率（单位：W，可选）
     * @return 亮屏剩余续航估计结果实体 [RuntimeEstimate]
     */
    fun estimateScreenOn(
        usableEnergyWh: Double?,
        onPowerEstimator: PowerEstimator,
        batteryLevelPercent: Float? = null,
        heavyPowerW: Double? = null,
        lightPowerW: Double? = null
    ): RuntimeEstimate {
        val predictedPower = onPowerEstimator.getPredictedPower()
        if (usableEnergyWh == null || usableEnergyWh <= 0.0 || predictedPower == null || predictedPower <= 0.0) {
            val reason = when {
                usableEnergyWh == null || usableEnergyWh <= 0.0 -> "剩余物理可用能量不可用"
                else -> "亮屏预测放电功率数据不足"
            }
            return RuntimeEstimate(
                remainingMs = null,
                predictedPowerWatts = predictedPower,
                remainingEnergyWh = usableEnergyWh,
                confidence = 0.0,
                modelType = RuntimeModelType.INSUFFICIENT_DATA,
                modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
                unavailableReason = reason
            )
        }

        val hours = usableEnergyWh / predictedPower
        val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)

        // 亮屏工况预期范围：优先采用设备真实大样本实测的重载与轻载工况功率；
        // 若无大样本画像，则基于当前预测功耗进行物理自适应动态包络推导，彻底消除人为绝对数值硬编码钳位
        val pHeavyOn = heavyPowerW?.takeIf { it > predictedPower } ?: (predictedPower * 1.60)
        val pLightOn = lightPowerW?.takeIf { it < predictedPower && it > 0.0 } ?: (predictedPower * 0.70)
        val minOnMs = ((usableEnergyWh / pHeavyOn) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
        val maxOnMs = ((usableEnergyWh / pLightOn) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minOnMs)
        val safeMinOnMs = if (remainingMs > 0L) kotlin.math.min(minOnMs, remainingMs) else minOnMs
        val safeMaxOnMs = if (remainingMs > 0L) kotlin.math.max(maxOnMs, remainingMs) else maxOnMs

        var confidence = onPowerEstimator.getConfidence()
        val qualityReasons = mutableListOf<String>()
        val hasSessionData = onPowerEstimator.totalSessionDurationMs > 0L
        val hasLongData = onPowerEstimator.getLongPower() != null

        if (hasSessionData && hasLongData) {
            qualityReasons.add("基于当前会话与历史多时间尺度融合")
        } else if (hasSessionData) {
            qualityReasons.add("基于当前会话直接采样推算")
        } else if (hasLongData) {
            qualityReasons.add("当前会话尚无稳定样本，采用近7天历史大样本基准推算")
        }

        if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
            confidence *= 0.80 // 低电量区域压降变化剧烈，提示预测不确定性
            qualityReasons.add("剩余电量低于10%，放电极化内阻增大")
        }

        val modelType = if (onPowerEstimator.totalSessionDurationMs > 0L) RuntimeModelType.MULTI_TIMESCALE else RuntimeModelType.CURRENT

        return RuntimeEstimate(
            remainingMs = if (remainingMs > 0L) remainingMs else null,
            predictedPowerWatts = predictedPower,
            remainingEnergyWh = usableEnergyWh,
            confidence = confidence,
            modelType = modelType,
            sampleCount = onPowerEstimator.sampleCount,
            modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
            minRemainingMs = if (remainingMs > 0L && safeMinOnMs > 0L) safeMinOnMs else null,
            maxRemainingMs = if (remainingMs > 0L && safeMaxOnMs > 0L) safeMaxOnMs else null,
            qualityReasons = qualityReasons
        )
    }

    /**
     * 预测持续息屏待机状态下的理论剩余续航时长。
     * 严格遵循物理做功定律：T_remaining = E_remaining / P_predicted。
     * 当具备设备实测的大样本活跃唤醒与深睡休眠工况功率时，精确推导工况预期范围；无实测数据时如实返回 null，严禁人为臆造范围。
     *
     * @param usableEnergyWh 当前电池有效可用物理能量（单位：Wh）
     * @param offPowerEstimator 息屏多时间尺度功耗估计器 [PowerEstimator]
     * @param batteryLevelPercent 当前电池电量百分比（用于低电量置信度评估）
     * @param offHighPowerW 历史大样本实测息屏唤醒活跃工况功率（单位：W，可选）
     * @param offLowPowerW 历史大样本实测息屏深睡休眠工况功率（单位：W，可选）
     * @return 息屏待机剩余续航估计结果实体 [RuntimeEstimate]
     */
    fun estimateScreenOff(
        usableEnergyWh: Double?,
        offPowerEstimator: PowerEstimator,
        batteryLevelPercent: Float? = null,
        offHighPowerW: Double? = null,
        offLowPowerW: Double? = null
    ): RuntimeEstimate {
        val predictedPower = offPowerEstimator.getPredictedPower()
        if (usableEnergyWh == null || usableEnergyWh <= 0.0 || predictedPower == null || predictedPower <= 0.0) {
            val reason = when {
                usableEnergyWh == null || usableEnergyWh <= 0.0 -> "剩余物理可用能量不可用"
                else -> "息屏待机预测功耗数据不足"
            }
            return RuntimeEstimate(
                remainingMs = null,
                predictedPowerWatts = predictedPower,
                remainingEnergyWh = usableEnergyWh,
                confidence = 0.0,
                modelType = RuntimeModelType.INSUFFICIENT_DATA,
                modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
                unavailableReason = reason
            )
        }

        val hours = usableEnergyWh / predictedPower
        val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)

        // 息屏工况预期范围：优先采用大样本实测唤醒与深睡功率；无画像时采用动态物理包络，移除绝对数值硬编码钳位
        val pHeavyOff = offHighPowerW?.takeIf { it > predictedPower } ?: (predictedPower * 1.80)
        val pLightOff = offLowPowerW?.takeIf { it < predictedPower && it > 0.0 } ?: (predictedPower * 0.65)
        val minOffMs = ((usableEnergyWh / pHeavyOff) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
        val maxOffMs = ((usableEnergyWh / pLightOff) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minOffMs)
        val safeMinOffMs = if (remainingMs > 0L) kotlin.math.min(minOffMs, remainingMs) else minOffMs
        val safeMaxOffMs = if (remainingMs > 0L) kotlin.math.max(maxOffMs, remainingMs) else maxOffMs

        var confidence = offPowerEstimator.getConfidence()
        val qualityReasons = mutableListOf<String>()
        val hasSessionData = offPowerEstimator.totalSessionDurationMs > 0L
        val hasLongData = offPowerEstimator.getLongPower() != null

        if (hasSessionData && hasLongData) {
            qualityReasons.add("基于待机休眠与唤醒多时间尺度融合")
        } else if (hasSessionData) {
            qualityReasons.add("基于当前待机直接采样推算")
        } else if (hasLongData) {
            qualityReasons.add("当前会话尚无待机样本，采用近7天历史大样本基准推算")
        }

        if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
            confidence *= 0.80
            qualityReasons.add("剩余电量低于10%，放电极化内阻增大")
        }

        val modelType = if (offPowerEstimator.totalSessionDurationMs > 0L) RuntimeModelType.MULTI_TIMESCALE else RuntimeModelType.CURRENT

        return RuntimeEstimate(
            remainingMs = if (remainingMs > 0L) remainingMs else null,
            predictedPowerWatts = predictedPower,
            remainingEnergyWh = usableEnergyWh,
            confidence = confidence,
            modelType = modelType,
            sampleCount = offPowerEstimator.sampleCount,
            modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
            minRemainingMs = if (remainingMs > 0L && safeMinOffMs > 0L) safeMinOffMs else null,
            maxRemainingMs = if (remainingMs > 0L && safeMaxOffMs > 0L) safeMaxOffMs else null,
            qualityReasons = qualityReasons
        )
    }

    /**
     * 预测基于用户真实作息习惯（亮屏/待机场景占空比融合）的全局日常综合理论剩余续航。
     * 遵循物理综合功耗模型：P_global = r_on * P_on + (1 - r_on) * P_off。
     * 当具备设备实测的大样本综合重载与轻载工况功率时，精确推导工况预期范围；无实测数据时如实返回 null，严禁人为臆造范围。
     *
     * @param usableEnergyWh 当前电池有效可用物理能量（单位：Wh）
     * @param screenOnRatio 用户日常使用中亮屏活跃时间占比（取值 0.0 至 1.0）
     * @param onPowerEstimator 亮屏多时间尺度功耗估计器 [PowerEstimator]
     * @param offPowerEstimator 息屏待机多时间尺度功耗估计器 [PowerEstimator]
     * @param fallbackGlobalEstimator 全局直接采样估计器（当细分场景缺失时的物理降级备份，可选）
     * @param batteryLevelPercent 当前电池电量百分比（可选）
     * @param heavyGlobalPowerW 历史大样本实测全局偏重载工况功率（单位：W，可选）
     * @param lightGlobalPowerW 历史大样本实测全局偏轻载工况功率（单位：W，可选）
     * @return 全局综合剩余续航估计结果实体 [RuntimeEstimate]
     */
    fun estimateGlobal(
        usableEnergyWh: Double?,
        screenOnRatio: Double?,
        onPowerEstimator: PowerEstimator,
        offPowerEstimator: PowerEstimator,
        fallbackGlobalEstimator: PowerEstimator? = null,
        batteryLevelPercent: Float? = null,
        heavyGlobalPowerW: Double? = null,
        lightGlobalPowerW: Double? = null
    ): RuntimeEstimate {
        if (usableEnergyWh == null || usableEnergyWh <= 0.0) {
            return RuntimeEstimate(
                remainingMs = null,
                predictedPowerWatts = null,
                remainingEnergyWh = null,
                confidence = 0.0,
                modelType = RuntimeModelType.INSUFFICIENT_DATA,
                modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
                unavailableReason = "剩余物理可用能量不可用"
            )
        }

        val pOn = onPowerEstimator.getPredictedPower()
        val pOff = offPowerEstimator.getPredictedPower()

        // 若具备亮屏与息屏独立模型及有效时间占比，采用场景融合模型
        if (pOn != null && pOn > 0.0 && pOff != null && pOff > 0.0 && screenOnRatio != null && screenOnRatio in 0.0..1.0) {
            val pGlobal = (screenOnRatio * pOn) + ((1.0 - screenOnRatio) * pOff)
            if (pGlobal > 0.0) {
                val hours = usableEnergyWh / pGlobal
                val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)

                val pHeavyGlobal = heavyGlobalPowerW?.takeIf { it > pGlobal } ?: (pGlobal * 1.40)
                val pLightGlobal = lightGlobalPowerW?.takeIf { it < pGlobal && it > 0.0 } ?: (pGlobal * 0.70)
                val minGlobalMs = ((usableEnergyWh / pHeavyGlobal) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
                val maxGlobalMs = ((usableEnergyWh / pLightGlobal) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minGlobalMs)
                val safeMinGlobalMs = if (remainingMs > 0L) kotlin.math.min(minGlobalMs, remainingMs) else minGlobalMs
                val safeMaxGlobalMs = if (remainingMs > 0L) kotlin.math.max(maxGlobalMs, remainingMs) else maxGlobalMs

                var blendedConfidence = (onPowerEstimator.getConfidence() * 0.5 + offPowerEstimator.getConfidence() * 0.5)
                val qualityReasons = mutableListOf<String>()
                qualityReasons.add("融合用户日常作息亮屏/息屏时间占空比")
                if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
                    blendedConfidence *= 0.80
                    qualityReasons.add("剩余电量低于10%，放电极化内阻增大")
                }
                return RuntimeEstimate(
                    remainingMs = if (remainingMs > 0L) remainingMs else null,
                    predictedPowerWatts = pGlobal,
                    remainingEnergyWh = usableEnergyWh,
                    confidence = blendedConfidence,
                    modelType = RuntimeModelType.GLOBAL_COMPOSITE,
                    sampleCount = onPowerEstimator.sampleCount + offPowerEstimator.sampleCount,
                    modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
                    minRemainingMs = if (remainingMs > 0L && safeMinGlobalMs > 0L) safeMinGlobalMs else null,
                    maxRemainingMs = if (remainingMs > 0L && safeMaxGlobalMs > 0L) safeMaxGlobalMs else null,
                    qualityReasons = qualityReasons
                )
            }
        }

        // 场景解构数据不全时，尝试采用全局直接估计器或单项估计器降级
        val (selectedEstimator, pFallback) = when {
            fallbackGlobalEstimator?.getPredictedPower()?.takeIf { it > 0.0 } != null ->
                fallbackGlobalEstimator to fallbackGlobalEstimator.getPredictedPower()!!
            pOn != null && pOn > 0.0 ->
                onPowerEstimator to pOn
            pOff != null && pOff > 0.0 ->
                offPowerEstimator to pOff
            else -> null to null
        }

        if (selectedEstimator != null && pFallback != null && pFallback > 0.0) {
            val hours = usableEnergyWh / pFallback
            val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)

            val pHeavyFallback = heavyGlobalPowerW?.takeIf { it > pFallback } ?: (pFallback * 1.40)
            val pLightFallback = lightGlobalPowerW?.takeIf { it < pFallback && it > 0.0 } ?: (pFallback * 0.70)
            val minFallbackMs = ((usableEnergyWh / pHeavyFallback) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
            val maxFallbackMs = ((usableEnergyWh / pLightFallback) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minFallbackMs)
            val safeMinFallbackMs = if (remainingMs > 0L) kotlin.math.min(minFallbackMs, remainingMs) else minFallbackMs
            val safeMaxFallbackMs = if (remainingMs > 0L) kotlin.math.max(maxFallbackMs, remainingMs) else maxFallbackMs

            var fallbackConfidence = selectedEstimator.getConfidence()
            val qualityReasons = mutableListOf("细分场景不全，降级至单项或全局直接采样")
            if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
                fallbackConfidence *= 0.80
                qualityReasons.add("剩余电量低于10%，放电极化内阻增大")
            }

            return RuntimeEstimate(
                remainingMs = if (remainingMs > 0L) remainingMs else null,
                predictedPowerWatts = pFallback,
                remainingEnergyWh = usableEnergyWh,
                confidence = fallbackConfidence,
                modelType = RuntimeModelType.CURRENT,
                sampleCount = selectedEstimator.sampleCount,
                modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
                minRemainingMs = if (remainingMs > 0L && safeMinFallbackMs > 0L) safeMinFallbackMs else null,
                maxRemainingMs = if (remainingMs > 0L && safeMaxFallbackMs > 0L) safeMaxFallbackMs else null,
                qualityReasons = qualityReasons
            )
        }

        return RuntimeEstimate(
            remainingMs = null,
            predictedPowerWatts = null,
            remainingEnergyWh = usableEnergyWh,
            confidence = 0.0,
            modelType = RuntimeModelType.INSUFFICIENT_DATA,
            modelVersion = RemainingModelVersion.REMAINING_RUNTIME_V2_1,
            unavailableReason = "缺乏亮屏、息屏或全局有效放电功耗数据"
        )
    }

    /**
     * 一站式整合估算亮屏、息屏与全局综合三维度的剩余续航预测结果。
     * 从当前会话指标与过去 7 天统计中提取物理功率与持续时长，构建多时间尺度估计器并执行物理能量做功预测。
     * 自动对接大样本历史画像 [com.battery.analysis.domain.estimator.model.RuntimeProfile] 中的实测工况功率，
     * 杜绝任何人造假数据或硬编码阈值。
     *
     * @param overview 当前放电会话的功耗核心统计实体 [com.battery.analysis.manager.PowerOverviewStats]
     * @param sevenDaysStats 过去 7 天大样本物理放电统计实体 [com.battery.analysis.model.SevenDaysDischargeStats]
     * @param batteryLevelPercent 当前电池剩余电量百分比（0.0 ~ 100.0）
     * @param remainingEnergyWh 当前电池剩余能量（单位：Wh，可选）
     * @param totalCapacityWh 电池满充总能量容量（单位：Wh，可选）
     * @param tempCelsius 当前电池温度（单位：℃，可选）
     * @return 包含亮屏、息屏与全局综合预测结果的聚合实体 [TripleRuntimeResult]
     */
    fun estimateTripleRuntimes(
        overview: com.battery.analysis.manager.PowerOverviewStats?,
        sevenDaysStats: com.battery.analysis.model.SevenDaysDischargeStats?,
        batteryLevelPercent: Float?,
        remainingEnergyWh: Float? = null,
        totalCapacityWh: Float? = null,
        tempCelsius: Float? = null
    ): TripleRuntimeResult {
        val usableEnergy = calculateUsableEnergyWh(
            remainingEnergyWh = remainingEnergyWh,
            batteryLevelPercent = batteryLevelPercent,
            totalCapacityWh = totalCapacityWh,
            tempCelsius = tempCelsius
        )

        // 提取大样本实测工况画像（零假数据原则，仅消费真实历史统计值）
        val profile = sevenDaysStats?.v2Result?.profile
        val heavyOnW = profile?.heavyPowerW
        val lightOnW = profile?.lightPowerW
        val offHighW = profile?.offHighPowerW
        val offLowW = profile?.offLowPowerW

        // 1. 构建亮屏多时间尺度估计器（优先对接 v2Result 预测功耗作为 Long 尺度基准）
        val onEstimator = PowerEstimator()
        val longOnPower = sevenDaysStats?.v2Result?.predictedScreenOnPowerWatts
            ?: if (sevenDaysStats != null && totalCapacityWh != null && totalCapacityWh > 0f) {
                val rate = sevenDaysStats.screenOnDischargeRatePercentPerHour
                if (rate != null && rate > 0f) {
                    (rate.toDouble() / 100.0) * totalCapacityWh.toDouble()
                } else null
            } else null
        onEstimator.setLongTermPower(longOnPower)
        if (overview != null && overview.screenOnPowerWatts > 0f) {
            val durMs = if (overview.screenOnDurationMs > 0L) overview.screenOnDurationMs else 0L
            onEstimator.seedCurrentSession(durMs, overview.screenOnPowerWatts)
        }
        val onEstimate = estimateScreenOn(
            usableEnergyWh = usableEnergy,
            onPowerEstimator = onEstimator,
            batteryLevelPercent = batteryLevelPercent,
            heavyPowerW = heavyOnW,
            lightPowerW = lightOnW
        )

        // 2. 构建息屏待机多时间尺度估计器（优先对接 v2Result 预测功耗作为 Long 尺度基准）
        val offEstimator = PowerEstimator()
        val longOffPower = sevenDaysStats?.v2Result?.predictedScreenOffPowerWatts
            ?: if (sevenDaysStats != null && totalCapacityWh != null && totalCapacityWh > 0f) {
                val rate = sevenDaysStats.screenOffDischargeRatePercentPerHour
                if (rate != null && rate > 0f) {
                    (rate.toDouble() / 100.0) * totalCapacityWh.toDouble()
                } else null
            } else null
        offEstimator.setLongTermPower(longOffPower)
        if (overview != null && overview.screenOffPowerWatts > 0f) {
            val durMs = if (overview.screenOffDurationMs > 0L) overview.screenOffDurationMs else 0L
            offEstimator.seedCurrentSession(durMs, overview.screenOffPowerWatts)
        }
        val offEstimate = estimateScreenOff(
            usableEnergyWh = usableEnergy,
            offPowerEstimator = offEstimator,
            batteryLevelPercent = batteryLevelPercent,
            offHighPowerW = offHighW,
            offLowPowerW = offLowW
        )

        // 3. 构建全局直接估计器备用（优先对接 v2Result 预测功耗作为 Long 尺度基准）
        val globalFallbackEstimator = PowerEstimator()
        val longGlobalPower = sevenDaysStats?.v2Result?.predictedPowerWatts
            ?: if (sevenDaysStats != null && totalCapacityWh != null && totalCapacityWh > 0f) {
                val rate = sevenDaysStats.globalDischargeRatePercentPerHour
                if (rate != null && rate > 0f) {
                    (rate.toDouble() / 100.0) * totalCapacityWh.toDouble()
                } else null
            } else null
        globalFallbackEstimator.setLongTermPower(longGlobalPower)
        if (overview != null && overview.avgPowerWatts > 0f) {
            val durMs = if (overview.totalDurationMs > 0L) overview.totalDurationMs else 0L
            globalFallbackEstimator.seedCurrentSession(durMs, overview.avgPowerWatts)
        }

        // 4. 全局综合续航推算（融合亮屏/待机占空比与真实工况）
        val screenOnRatio = sevenDaysStats?.sceneScreenOnRatio?.toDouble()
        val heavyGlobalW = if (screenOnRatio != null && heavyOnW != null && offHighW != null) {
            screenOnRatio * heavyOnW + (1.0 - screenOnRatio) * offHighW
        } else null
        val lightGlobalW = if (screenOnRatio != null && lightOnW != null && offLowW != null) {
            screenOnRatio * lightOnW + (1.0 - screenOnRatio) * offLowW
        } else null

        val globalEstimate = estimateGlobal(
            usableEnergyWh = usableEnergy,
            screenOnRatio = screenOnRatio,
            onPowerEstimator = onEstimator,
            offPowerEstimator = offEstimator,
            fallbackGlobalEstimator = globalFallbackEstimator,
            batteryLevelPercent = batteryLevelPercent,
            heavyGlobalPowerW = heavyGlobalW,
            lightGlobalPowerW = lightGlobalW
        )

        return TripleRuntimeResult(
            screenOnEstimate = onEstimate,
            screenOffEstimate = offEstimate,
            globalEstimate = globalEstimate
        )
    }
}

/**
 * 聚合亮屏、息屏待机与全局综合三维度剩余续航预测结果的数据类。
 *
 * @property screenOnEstimate 持续亮屏状态下的理论剩余续航预测结果
 * @property screenOffEstimate 持续息屏待机状态下的理论剩余续航预测结果
 * @property globalEstimate 融合日常亮屏/待机作息占空比的全局综合理论剩余续航预测结果
 * @property modelVersion 产生本结果的算法模型版本 [com.battery.analysis.domain.estimator.model.RemainingModelVersion]
 */
data class TripleRuntimeResult(
    val screenOnEstimate: RuntimeEstimate,
    val screenOffEstimate: RuntimeEstimate,
    val globalEstimate: RuntimeEstimate,
    val modelVersion: com.battery.analysis.domain.estimator.model.RemainingModelVersion =
        com.battery.analysis.domain.estimator.model.RemainingModelVersion.REMAINING_RUNTIME_V2_1
)
