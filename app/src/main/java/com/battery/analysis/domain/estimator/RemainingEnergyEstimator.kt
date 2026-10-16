package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.EnergySource

/**
 * 剩余可用物理能量估算结果实体类。
 *
 * @property usableEnergyWh 经过温度修正后的实际可用物理能量（单位：Wh，若无法推算则为 null，严禁假数据）
 * @property rawEnergyWh 未经温度修正的原始物理能量（单位：Wh，若无法推算则为 null）
 * @property source 剩余物理能量的获取来源 [EnergySource]
 * @property quality 数据质量等级 [DataQuality]
 * @property temperatureFactor 当前环境与电芯温度修正系数 K_T（取值 0.70 至 1.0 之间）
 * @property notes 相关的计算依据或降级说明标签集合
 */
data class UsableRemainingEnergyEstimate(
    val usableEnergyWh: Double?,
    val rawEnergyWh: Double?,
    val source: EnergySource,
    val quality: DataQuality,
    val temperatureFactor: Double,
    val notes: Set<String> = emptySet()
)

/**
 * 电池当前剩余可用物理能量（瓦时 Wh）高精度计算器。
 * 遵循严格系统底层物理数据优先级顺序，统筹硬件能量寄存器、实时电荷计数器与容量电量折算，
 * 并结合电芯工作温度对锂离子活性与可用化学能进行物理级修正（K_T），
 * 严格秉持零虚构数据原则：无有效数据源时如实返回 null。
 */
class RemainingEnergyEstimator {

    companion object {
        /** 默认锂电池放电平台标称电压 (单位：V) */
        const val DEFAULT_NOMINAL_VOLTAGE: Double = 3.85

        /** 纳瓦时转瓦时的物理除数常数 (1 Wh = 10^9 nWh) */
        private const val NWH_TO_WH_FACTOR: Double = 1_000_000_000.0

        /**
         * 计算环境与电芯温度对电池实际可用化学能的物理修正系数 K_T。
         * 在常温区间（15℃ 至 35℃）系数为 1.0（无受限衰减）；
         * 在低温区间（锂离子活性与扩散速率受限），电芯等效内阻升高，可用容量呈物理性轻度衰减（-10℃ 线性过渡至 0.75）；
         * 在高温区间（>45℃），发热内阻损耗轻度衰减至 0.95。
         *
         * @param tempCelsius 当前电池温度（单位：摄氏度 ℃，缺失时传入 null）
         * @return 介于 0.70 至 1.0 之间的可用能量物理温度修正系数
         */
        fun calculateTemperatureFactor(tempCelsius: Float?): Double {
            if (tempCelsius == null) return 1.0
            return when {
                tempCelsius in 15.0f..35.0f -> 1.0
                tempCelsius < 15.0f -> {
                    val clamped = tempCelsius.coerceIn(-10.0f, 15.0f)
                    0.75 + ((clamped - (-10.0f)) / 25.0f) * 0.25
                }
                tempCelsius > 45.0f -> 0.95
                else -> 1.0
            }
        }
    }

    /**
     * 估算当前电池剩余状态下整机可释放的真实物理可用能量（单位：Wh）。
     *
     * 优先级逻辑：
     * 1. 硬件原生能量计数器直读（纳瓦时 nWh -> Wh）；
     * 2. 硬件电量计实时剩余电荷计数器（微安时 uAh / 毫安时 mAh）结合标称电压折算；
     * 3. 基准容量（优先当前真实满充容量 FCC，其次设计容量）结合当前电量百分比与标称电压推算；
     * 4. 结合电池当前工作温度实施 K_T 物理修正。
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
        nominalVoltage: Double = DEFAULT_NOMINAL_VOLTAGE
    ): UsableRemainingEnergyEstimate {
        val safeVolt = if (nominalVoltage > 0.0 && nominalVoltage.isFinite()) nominalVoltage else DEFAULT_NOMINAL_VOLTAGE
        val kt = calculateTemperatureFactor(tempCelsius)

        // 策略 1：直接传入的有效硬件剩余能量 Wh
        if (remainingEnergyWh != null && remainingEnergyWh > 0f) {
            val raw = remainingEnergyWh.toDouble()
            val usable = raw * kt
            return UsableRemainingEnergyEstimate(
                usableEnergyWh = if (usable > 0.0) usable else null,
                rawEnergyWh = raw,
                source = EnergySource.NATIVE_FULL_ENERGY,
                quality = DataQuality.VALID,
                temperatureFactor = kt,
                notes = setOf("基于直接硬件剩余能量上报")
            )
        }

        // 策略 2：硬件原生纳瓦时能量寄存器直读
        if (nativeEnergyNwh != null && nativeEnergyNwh > 0L) {
            val raw = nativeEnergyNwh.toDouble() / NWH_TO_WH_FACTOR
            if (raw > 0.0 && raw.isFinite()) {
                val usable = raw * kt
                return UsableRemainingEnergyEstimate(
                    usableEnergyWh = if (usable > 0.0) usable else null,
                    rawEnergyWh = raw,
                    source = EnergySource.NATIVE_FULL_ENERGY,
                    quality = DataQuality.VALID,
                    temperatureFactor = kt,
                    notes = setOf("基于硬件电量计原生纳瓦时能量寄存器")
                )
            }
        }

        // 策略 3：硬件实时电荷计数器 (uAh/mAh -> Wh)
        if (chargeCounterUah != null && chargeCounterUah > 0) {
            val currentMah = if (chargeCounterUah < 100_000) {
                chargeCounterUah.toDouble()
            } else {
                chargeCounterUah.toDouble() / 1000.0
            }
            val raw = (currentMah * safeVolt) / 1000.0
            if (raw > 0.0 && raw.isFinite()) {
                val usable = raw * kt
                return UsableRemainingEnergyEstimate(
                    usableEnergyWh = if (usable > 0.0) usable else null,
                    rawEnergyWh = raw,
                    source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
                    quality = DataQuality.VALID,
                    temperatureFactor = kt,
                    notes = setOf("基于硬件实时电荷计数器与标称电压折算")
                )
            }
        }

        // 策略 4：基准容量结合电量百分比推算
        if (batteryLevelPercent != null && batteryLevelPercent > 0f && effectiveCapacityMah != null && effectiveCapacityMah > 0f) {
            val safePercent = batteryLevelPercent.coerceIn(0.0f, 100.0f).toDouble()
            val remainingMah = effectiveCapacityMah.toDouble() * (safePercent / 100.0)
            val raw = (remainingMah * safeVolt) / 1000.0
            if (raw > 0.0 && raw.isFinite()) {
                val usable = raw * kt
                return UsableRemainingEnergyEstimate(
                    usableEnergyWh = if (usable > 0.0) usable else null,
                    rawEnergyWh = raw,
                    source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
                    quality = DataQuality.VALID,
                    temperatureFactor = kt,
                    notes = setOf("基于有效基准容量与电量百分比折算")
                )
            }
        }

        // 兜底：无有效能量数据，如实返回不可用（严禁假数据）
        return UsableRemainingEnergyEstimate(
            usableEnergyWh = null,
            rawEnergyWh = null,
            source = EnergySource.UNAVAILABLE,
            quality = DataQuality.INVALID,
            temperatureFactor = kt,
            notes = setOf("缺乏有效的硬件能量或容量电量数据")
        )
    }
}
