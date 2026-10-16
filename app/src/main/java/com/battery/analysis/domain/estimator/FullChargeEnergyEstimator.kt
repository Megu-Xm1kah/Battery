package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.EnergySource
import com.battery.analysis.domain.estimator.model.FullChargeEnergyEstimate

/**
 * 设备满充状态可用物理能量高精度估算器。
 * 遵循严格系统底层物理数据优先级顺序，统一标准能量与电荷物理单位换算，
 * 杜绝在电量计已包含老化衰减的满充容量（FCC）上二次扣除健康度（SOH），
 * 严格秉持零虚构数据原则：无可靠能量数据源时如实返回不可用。
 */
class FullChargeEnergyEstimator {

    companion object {
        /**
         * 行业标准锂离子与锂聚合物电池标称中位放电平台电压（单位：伏特 V）。
         */
        const val DEFAULT_NOMINAL_VOLTAGE: Double = 3.85

        /**
         * 纳瓦时转瓦时的物理除数常数 (1 Wh = 10^9 nWh)。
         */
        private const val NWH_TO_WH_FACTOR: Double = 1_000_000_000.0
    }

    /**
     * 根据设备底层可用的硬件能量、电量计容量或设计规格参数，估算电池在 100% 满充状态下的理论可用物理能量。
     *
     * 优先级逻辑：
     * 1. 硬件电量计原生纳瓦时能量计数器（若可用且大于 0，结合当前电量百分比反推 100% 满电能量）；
     * 2. 电量计实测真实满充容量 FCC（单位：mAh）结合标称电压折算，由于 FCC 已反映电芯自然老化后的实际容量，绝不二次乘 SOH；
     * 3. 电池出厂标称设计容量（单位：mAh）结合有效健康度 SOH 与标称电压折算；
     * 4. 若上述参数均无法获取或非物理正数，忠实返回不可用结果，绝不编造假数据。
     *
     * @param nativeEnergyNwh 硬件底层能量计数器数值（单位：纳瓦时 nWh，若不支持可为 null）
     * @param batteryPercent 当前电池电量百分比（取值范围 1 至 100，若不可用可为 null）
     * @param fccMah 电量计实测当前满充真实容量（Full Charge Capacity，单位：mAh，若不可用可为 null）
     * @param designCapacityMah 电池出厂设计容量（单位：mAh，若不可用可为 null）
     * @param sohPercent 电池健康度百分比（State of Health，取值 0.0 至 100.0，若不可用可为 null）
     * @param nominalVoltage 电池标称工作平台电压（单位：V，默认 3.85V）
     * @return 满充物理可用能量估算结果聚合实体 [FullChargeEnergyEstimate]
     */
    fun estimateFullEnergy(
        nativeEnergyNwh: Long? = null,
        batteryPercent: Int? = null,
        fccMah: Float? = null,
        designCapacityMah: Float? = null,
        sohPercent: Float? = null,
        nominalVoltage: Double = DEFAULT_NOMINAL_VOLTAGE
    ): FullChargeEnergyEstimate {
        val safeVolt = if (nominalVoltage > 0.0 && nominalVoltage.isFinite()) nominalVoltage else DEFAULT_NOMINAL_VOLTAGE

        // 优先级 1：硬件电量计原生能量寄存器 (nWh -> Wh)
        if (nativeEnergyNwh != null && nativeEnergyNwh > 0L && batteryPercent != null && batteryPercent in 1..100) {
            val remainWh = nativeEnergyNwh.toDouble() / NWH_TO_WH_FACTOR
            val fullWh = (remainWh / batteryPercent.toDouble()) * 100.0
            if (fullWh > 0.0 && fullWh.isFinite()) {
                return FullChargeEnergyEstimate(
                    energyWh = fullWh,
                    source = EnergySource.NATIVE_FULL_ENERGY,
                    quality = DataQuality.VALID,
                    notes = setOf("基于硬件原生能量寄存器与当前电量反推")
                )
            }
        }

        // 优先级 2：当前真实满充容量 FCC × 标称电压（FCC已包含老化衰减，绝不二次乘 SOH）
        if (fccMah != null && fccMah > 0f) {
            val fullWh = (fccMah.toDouble() * safeVolt) / 1000.0
            if (fullWh > 0.0 && fullWh.isFinite()) {
                return FullChargeEnergyEstimate(
                    energyWh = fullWh,
                    source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
                    quality = DataQuality.VALID,
                    notes = setOf("基于真实满充容量(FCC)与标称电压折算")
                )
            }
        }

        // 优先级 3：出厂设计容量 × 真实 SOH × 标称电压
        if (designCapacityMah != null && designCapacityMah > 0f) {
            val effectiveSoh = if (sohPercent != null && sohPercent in 10.0f..150.0f) {
                sohPercent.toDouble() / 100.0
            } else {
                1.0
            }
            val fullWh = (designCapacityMah.toDouble() * effectiveSoh * safeVolt) / 1000.0
            if (fullWh > 0.0 && fullWh.isFinite()) {
                val hasSoh = sohPercent != null && sohPercent > 0f
                return FullChargeEnergyEstimate(
                    energyWh = fullWh,
                    source = EnergySource.DESIGN_CAPACITY_TIMES_HEALTH,
                    quality = if (hasSoh) DataQuality.VALID else DataQuality.LOW_QUALITY,
                    notes = if (hasSoh) {
                        setOf("基于出厂设计容量与健康度(SOH)折算")
                    } else {
                        setOf("无SOH数据，按标称出厂设计容量基础估算")
                    }
                )
            }
        }

        // 兜底：所有底层数据源均缺失或无效，如实返回不可用（严禁硬编码默认能量）
        return FullChargeEnergyEstimate(
            energyWh = null,
            source = EnergySource.UNAVAILABLE,
            quality = DataQuality.INVALID,
            notes = setOf("无法获取有效的硬件能量、电量计FCC或设计容量")
        )
    }
}
