package com.battery.analysis.domain.estimator

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
     *
     * @param usableEnergyWh 当前电池有效可用物理能量（单位：Wh）
     * @param onPowerEstimator 亮屏多时间尺度功耗估计器 [PowerEstimator]
     * @param batteryLevelPercent 当前电池电量百分比（用于低电量置信度评估）
     * @return 亮屏剩余续航估计结果实体 [RuntimeEstimate]
     */
    fun estimateScreenOn(
        usableEnergyWh: Double?,
        onPowerEstimator: PowerEstimator,
        batteryLevelPercent: Float? = null
    ): RuntimeEstimate {
        val predictedPower = onPowerEstimator.getPredictedPower()
        if (usableEnergyWh == null || usableEnergyWh <= 0.0 || predictedPower == null || predictedPower <= 0.0) {
            return RuntimeEstimate(
                remainingMs = null,
                predictedPowerWatts = predictedPower,
                remainingEnergyWh = usableEnergyWh,
                confidence = 0.0,
                modelType = RuntimeModelType.INSUFFICIENT_DATA
            )
        }

        val hours = usableEnergyWh / predictedPower
        val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)

        // 亮屏工况预期范围：
        // 重载工况（大型 3D 游戏/相机录像，功率通常不低于 3.5W，或约为典型功率的 1.8~2.2 倍）
        // 轻载工况（纯静态文本/电子书阅读，功率通常在 1.0W~1.2W，或约为典型功率的 0.75~0.80 倍）
        val pHeavyOn = kotlin.math.max(predictedPower * 1.80, 3.50)
        val pLightOn = kotlin.math.min(predictedPower * 0.80, kotlin.math.max(1.00, predictedPower * 0.75))
        val minOnMs = ((usableEnergyWh / pHeavyOn) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
        val maxOnMs = ((usableEnergyWh / pLightOn) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minOnMs)

        var confidence = onPowerEstimator.getConfidence()
        if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
            confidence *= 0.80 // 低电量区域压降变化剧烈，降低预测置信度
        }

        return RuntimeEstimate(
            remainingMs = if (remainingMs > 0L) remainingMs else null,
            predictedPowerWatts = predictedPower,
            remainingEnergyWh = usableEnergyWh,
            confidence = confidence,
            modelType = if (onPowerEstimator.totalSessionDurationMs > 0L) RuntimeModelType.MULTI_TIMESCALE else RuntimeModelType.CURRENT,
            sampleCount = onPowerEstimator.sampleCount,
            minRemainingMs = if (minOnMs > 0L) minOnMs else null,
            maxRemainingMs = if (maxOnMs > 0L) maxOnMs else null
        )
    }

    /**
     * 预测持续息屏待机状态下的理论剩余续航时长。
     *
     * @param usableEnergyWh 当前电池有效可用物理能量（单位：Wh）
     * @param offPowerEstimator 息屏多时间尺度功耗估计器 [PowerEstimator]
     * @param batteryLevelPercent 当前电池电量百分比（用于低电量置信度评估）
     * @return 息屏待机剩余续航估计结果实体 [RuntimeEstimate]
     */
    fun estimateScreenOff(
        usableEnergyWh: Double?,
        offPowerEstimator: PowerEstimator,
        batteryLevelPercent: Float? = null
    ): RuntimeEstimate {
        val predictedPower = offPowerEstimator.getPredictedPower()
        if (usableEnergyWh == null || usableEnergyWh <= 0.0 || predictedPower == null || predictedPower <= 0.0) {
            return RuntimeEstimate(
                remainingMs = null,
                predictedPowerWatts = predictedPower,
                remainingEnergyWh = usableEnergyWh,
                confidence = 0.0,
                modelType = RuntimeModelType.INSUFFICIENT_DATA
            )
        }

        val hours = usableEnergyWh / predictedPower
        val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)

        // 息屏工况预期范围：
        // 唤醒活跃工况（后台下载/定位/媒体，功率通常约 0.70W 以上，或约为典型待机的 1.8~2.2 倍）
        // 深度休眠底噪（纯静置无唤醒锁，功率约为 0.15W~0.30W，或约为典型待机的 0.75 倍）
        val pHeavyOff = kotlin.math.max(predictedPower * 1.80, 0.70)
        val pLightOff = kotlin.math.max(0.15, predictedPower * 0.75)
        val minOffMs = ((usableEnergyWh / pHeavyOff) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
        val maxOffMs = ((usableEnergyWh / pLightOff) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minOffMs)

        var confidence = offPowerEstimator.getConfidence()
        if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
            confidence *= 0.80
        }

        return RuntimeEstimate(
            remainingMs = if (remainingMs > 0L) remainingMs else null,
            predictedPowerWatts = predictedPower,
            remainingEnergyWh = usableEnergyWh,
            confidence = confidence,
            modelType = if (offPowerEstimator.totalSessionDurationMs > 0L) RuntimeModelType.MULTI_TIMESCALE else RuntimeModelType.CURRENT,
            sampleCount = offPowerEstimator.sampleCount,
            minRemainingMs = if (minOffMs > 0L) minOffMs else null,
            maxRemainingMs = if (maxOffMs > 0L) maxOffMs else null
        )
    }

    /**
     * 预测基于用户真实作息习惯（亮屏/待机场景占空比融合）的全局日常综合理论剩余续航。
     * 遵循物理综合功耗模型：P_global = r_on * P_on + (1 - r_on) * P_off。
     *
     * @param usableEnergyWh 当前电池有效可用物理能量（单位：Wh）
     * @param screenOnRatio 用户日常使用中亮屏活跃时间占比（取值 0.0 至 1.0）
     * @param onPowerEstimator 亮屏多时间尺度功耗估计器
     * @param offPowerEstimator 息屏待机多时间尺度功耗估计器
     * @param fallbackGlobalEstimator 全局直接采样估计器（当细分场景缺失时的物理降级备份）
     * @param batteryLevelPercent 当前电池电量百分比
     * @return 全局综合剩余续航估计结果实体 [RuntimeEstimate]
     */
    fun estimateGlobal(
        usableEnergyWh: Double?,
        screenOnRatio: Double?,
        onPowerEstimator: PowerEstimator,
        offPowerEstimator: PowerEstimator,
        fallbackGlobalEstimator: PowerEstimator? = null,
        batteryLevelPercent: Float? = null
    ): RuntimeEstimate {
        if (usableEnergyWh == null || usableEnergyWh <= 0.0) {
            return RuntimeEstimate(
                remainingMs = null,
                predictedPowerWatts = null,
                remainingEnergyWh = null,
                confidence = 0.0,
                modelType = RuntimeModelType.INSUFFICIENT_DATA
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

                // 全局综合工况预期范围（偏重载综合与偏轻载综合）
                val pHeavyGlobal = pGlobal * 1.50
                val pLightGlobal = kotlin.math.max(0.30, pGlobal * 0.70)
                val minGlobalMs = ((usableEnergyWh / pHeavyGlobal) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
                val maxGlobalMs = ((usableEnergyWh / pLightGlobal) * 3600.0 * 1000.0).roundToLong().coerceAtLeast(minGlobalMs)

                var blendedConfidence = (onPowerEstimator.getConfidence() * 0.5 + offPowerEstimator.getConfidence() * 0.5)
                if (batteryLevelPercent != null && batteryLevelPercent < 10.0f) {
                    blendedConfidence *= 0.80
                }
                return RuntimeEstimate(
                    remainingMs = if (remainingMs > 0L) remainingMs else null,
                    predictedPowerWatts = pGlobal,
                    remainingEnergyWh = usableEnergyWh,
                    confidence = blendedConfidence,
                    modelType = RuntimeModelType.GLOBAL_COMPOSITE,
                    sampleCount = onPowerEstimator.sampleCount + offPowerEstimator.sampleCount,
                    minRemainingMs = if (minGlobalMs > 0L) minGlobalMs else null,
                    maxRemainingMs = if (maxGlobalMs > 0L) maxGlobalMs else null
                )
            }
        }

        // 场景解构数据不全时，尝试采用全局直接估计器降级
        val pFallback = fallbackGlobalEstimator?.getPredictedPower()
            ?: pOn
            ?: pOff

        if (pFallback != null && pFallback > 0.0) {
            val hours = usableEnergyWh / pFallback
            val remainingMs = (hours * 3600.0 * 1000.0).roundToLong().coerceAtLeast(0L)
            return RuntimeEstimate(
                remainingMs = if (remainingMs > 0L) remainingMs else null,
                predictedPowerWatts = pFallback,
                remainingEnergyWh = usableEnergyWh,
                confidence = fallbackGlobalEstimator?.getConfidence() ?: 0.40,
                modelType = RuntimeModelType.CURRENT,
                sampleCount = fallbackGlobalEstimator?.sampleCount ?: 0
            )
        }

        return RuntimeEstimate(
            remainingMs = null,
            predictedPowerWatts = null,
            remainingEnergyWh = usableEnergyWh,
            confidence = 0.0,
            modelType = RuntimeModelType.INSUFFICIENT_DATA
        )
    }

    /**
     * 一站式整合估算亮屏、息屏与全局综合三维度的剩余续航预测结果。
     * 从当前会话指标与过去 7 天统计中提取物理功率与持续时长，构建多时间尺度估计器并执行物理能量做功预测。
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

        // 1. 构建亮屏多时间尺度估计器
        val onEstimator = PowerEstimator()
        val longOnPower = if (sevenDaysStats != null && totalCapacityWh != null && totalCapacityWh > 0f) {
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
        val onEstimate = estimateScreenOn(usableEnergy, onEstimator, batteryLevelPercent)

        // 2. 构建息屏待机多时间尺度估计器
        val offEstimator = PowerEstimator()
        val longOffPower = if (sevenDaysStats != null && totalCapacityWh != null && totalCapacityWh > 0f) {
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
        val offEstimate = estimateScreenOff(usableEnergy, offEstimator, batteryLevelPercent)

        // 3. 构建全局直接估计器备用
        val globalFallbackEstimator = PowerEstimator()
        val longGlobalPower = if (sevenDaysStats != null && totalCapacityWh != null && totalCapacityWh > 0f) {
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

        // 4. 全局综合续航推算（融合亮屏/待机占空比）
        val screenOnRatio = sevenDaysStats?.sceneScreenOnRatio?.toDouble()
        val globalEstimate = estimateGlobal(
            usableEnergyWh = usableEnergy,
            screenOnRatio = screenOnRatio,
            onPowerEstimator = onEstimator,
            offPowerEstimator = offEstimator,
            fallbackGlobalEstimator = globalFallbackEstimator,
            batteryLevelPercent = batteryLevelPercent
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
        com.battery.analysis.domain.estimator.model.RemainingModelVersion.REMAINING_RUNTIME_V2
)
