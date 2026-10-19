package com.battery.analysis

import com.battery.analysis.domain.estimator.BatteryUnitNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 电池物理量纲与数据源单位自适应归一化工具测试类。
 * 针对 Google 标准 Android API 上报微安时（uAh）与国产厂商（OPPO/OnePlus、vivo等）
 * 定制 ROM 直接上报毫安时（mAh）的混合物理生态，验证自适应物理量级校验（Physical Magnitude Validation）的准确性。
 */
class BatteryUnitNormalizerTest {

    /**
     * 测试标准 Android 设备上报微安时（uAh）且已知基准容量时，准确除以 1000 转换为毫安时（mAh）。
     */
    @Test
    fun testNormalizeStandardUahWithEffectiveCapacity() {
        // 5000 mAh 电池在剩余 3000 mAh 时，标准驱动上报 3,000,000 uAh
        val result = BatteryUnitNormalizer.normalizeChargeCounterToMah(
            rawCounter = 3_000_000L,
            effectiveCapacityMah = 5000.0,
            batteryPercent = 60.0
        )
        assertEquals(3000.0, result!!, 0.001)
    }

    /**
     * 测试国产魔改 ROM 底层直接上报毫安时（mAh）时，绝不二次除以 1000 导致缩水千倍。
     * （对应用户真机现场：电量 24% 上报 1896，必须识别为 1896 mAh，杜绝被误算为 1.896 mAh）。
     */
    @Test
    fun testNormalizeOemMahWithEffectiveCapacity() {
        // 7900 mAh 电池在剩余 24% 时，魔改驱动直接上报 1896 (mAh)
        val result = BatteryUnitNormalizer.normalizeChargeCounterToMah(
            rawCounter = 1896L,
            effectiveCapacityMah = 7900.0,
            batteryPercent = 24.0
        )
        assertEquals(1896.0, result!!, 0.001)
    }

    /**
     * 测试在未知基准容量但已知当前电量百分比时，通过反推等效满电容量自适应识别物理量纲。
     * 1896 若当成 uAh 则满电容量只有 7.9 mAh（纽扣电池，非手机），故正确判定为 1896 mAh。
     */
    @Test
    fun testNormalizeBySocContextWithoutEffectiveCapacity() {
        val result = BatteryUnitNormalizer.normalizeChargeCounterToMah(
            rawCounter = 1896L,
            effectiveCapacityMah = null,
            batteryPercent = 24.0
        )
        // 判定为 mAh
        assertEquals(1896.0, result!!, 0.001)

        // 若上报 1,896,000，当成 uAh 满电容量为 7900 mAh（手机级别），正确判定为 uAh 并除以 1000
        val resultUah = BatteryUnitNormalizer.normalizeChargeCounterToMah(
            rawCounter = 1_896_000L,
            effectiveCapacityMah = null,
            batteryPercent = 24.0
        )
        assertEquals(1896.0, resultUah!!, 0.001)
    }

    /**
     * 测试极低电量下的真实微安时读数（如 80,000 uAh = 80 mAh 在 1% 电量），
     * 验证绝不会被误放大为 80,000 mAh。
     */
    @Test
    fun testNormalizeLowBatteryRealUahNotScaledUp() {
        val result = BatteryUnitNormalizer.normalizeChargeCounterToMah(
            rawCounter = 80_000L,
            effectiveCapacityMah = 5000.0,
            batteryPercent = 1.6
        )
        // 80,000 uAh > 50,000 且 80,000 / 1000 = 80 mAh，反推总容量 80 / 0.016 = 5000 mAh
        assertEquals(80.0, result!!, 0.001)
    }

    /**
     * 测试空值或非法非物理正数输入，忠实返回 null，绝不合成伪造数据。
     */
    @Test
    fun testNormalizeInvalidInputsReturnNull() {
        assertNull(BatteryUnitNormalizer.normalizeChargeCounterToMah(null))
        assertNull(BatteryUnitNormalizer.normalizeChargeCounterToMah(0L))
        assertNull(BatteryUnitNormalizer.normalizeChargeCounterToMah(-1000L))
    }

    /**
     * 测试在有效电压与有效电流下的真实物理瞬时放电功率计算。
     */
    @Test
    fun testCalculatePowerWattsWithValidInputs() {
        // 电压 4.0V，电流 500mA -> 2.0W
        val power = com.battery.analysis.util.BatteryUnitNormalizer.calculatePowerWatts(
            voltageVolts = 4.0f,
            currentMa = 500f
        )
        assertEquals(2.0f, power, 0.001f)
    }

    /**
     * 测试在非法电压（<= 0f）或零电流时，忠实反映真实物理状态，返回 0f，绝不捏造 4.0V 虚假电压。
     */
    @Test
    fun testCalculatePowerWattsWithInvalidVoltageReturnsZero() {
        // 非法电压 0f 或负数，严禁虚构 4.0V
        val powerZeroVolt = com.battery.analysis.util.BatteryUnitNormalizer.calculatePowerWatts(
            voltageVolts = 0f,
            currentMa = 1000f
        )
        assertEquals("非法电压必须返回 0f，杜绝 4.0V 虚假硬编码保底", 0f, powerZeroVolt, 0.0001f)

        val powerNegativeVolt = com.battery.analysis.util.BatteryUnitNormalizer.calculatePowerWatts(
            voltageVolts = -3.8f,
            currentMa = 1000f
        )
        assertEquals("负数电压必须返回 0f", 0f, powerNegativeVolt, 0.0001f)

        // 零电流
        val powerZeroCur = com.battery.analysis.util.BatteryUnitNormalizer.calculatePowerWatts(
            voltageVolts = 4.0f,
            currentMa = 0f
        )
        assertEquals("零电流必须返回 0f 功率", 0f, powerZeroCur, 0.0001f)
    }
}

