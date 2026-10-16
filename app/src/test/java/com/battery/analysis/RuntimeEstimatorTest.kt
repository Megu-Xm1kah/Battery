package com.battery.analysis

import com.battery.analysis.domain.estimator.PowerEstimator
import com.battery.analysis.domain.estimator.RuntimeEstimator
import com.battery.analysis.domain.estimator.model.RuntimeModelType
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.SevenDaysDischargeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理级自适应剩余续航估计引擎（RuntimeEstimator）单元测试套件。
 * 验证温度修正系数、可用能量计算、亮屏/息屏/全局三维物理推算，
 * 以及零假数据边界保护与数据缺失安全降级。
 */
class RuntimeEstimatorTest {

    private val estimator = RuntimeEstimator()

    /**
     * 测试电芯工作温度修正系数 K_T 在常温、极低温与极端高温下的物理衰减特性。
     */
    @Test
    fun testTemperatureFactor() {
        // 常温 25℃ 系数为 1.0（无损耗）
        assertEquals(1.0, RuntimeEstimator.calculateTemperatureFactor(25.0f), 0.001)

        // 极低温 -10℃ 活性受限，系数衰减至 0.75
        assertEquals(0.75, RuntimeEstimator.calculateTemperatureFactor(-10.0f), 0.001)

        // 0℃ 时介于 0.75 与 1.0 之间
        val factor0 = RuntimeEstimator.calculateTemperatureFactor(0.0f)
        assertTrue("0℃ 介于 0.75 至 1.0", factor0 in 0.75..1.0)

        // 高温 50℃ 发热内阻损耗，轻度衰减至 0.95
        assertEquals(0.95, RuntimeEstimator.calculateTemperatureFactor(50.0f), 0.001)
    }

    /**
     * 测试有效可用物理能量换算（支持直接能量值与容量电量换算两路输入）。
     */
    @Test
    fun testCalculateUsableEnergyWh() {
        // 直接读取硬件剩余能量 10.0 Wh
        val eDirect = estimator.calculateUsableEnergyWh(
            remainingEnergyWh = 10.0f,
            batteryLevelPercent = null,
            totalCapacityWh = null,
            tempCelsius = 25.0f
        )
        assertNotNull(eDirect)
        assertEquals(10.0, eDirect!!, 0.01)

        // 通过 50% 电量与 20.0 Wh 总能量换算 -> 10.0 Wh
        val eCalculated = estimator.calculateUsableEnergyWh(
            remainingEnergyWh = null,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )
        assertNotNull(eCalculated)
        assertEquals(10.0, eCalculated!!, 0.01)

        // 数据全缺失时严格返回 null，严禁假数据
        val eMissing = estimator.calculateUsableEnergyWh(null, null, null)
        assertNull(eMissing)
    }

    /**
     * 测试持续亮屏续航推算（T_on = E_usable / P_on）。
     */
    @Test
    fun testEstimateScreenOn() {
        val onPowerEstimator = PowerEstimator()
        onPowerEstimator.seedCurrentSession(durationMs = 1_800_000L, powerWatts = 3.85f)

        // 可用能量 19.25 Wh，亮屏功率 3.85 W -> 理论续航恰好 5 小时 = 18,000,000 ms
        val result = estimator.estimateScreenOn(
            usableEnergyWh = 19.25,
            onPowerEstimator = onPowerEstimator,
            batteryLevelPercent = 100.0f
        )

        assertNotNull(result.remainingMs)
        assertEquals(18_000_000L, result.remainingMs!!)
        assertEquals(3.85, result.predictedPowerWatts!!, 0.01)
        assertTrue("具有较高置信度", result.confidence >= 0.60)
        assertEquals(RuntimeModelType.MULTI_TIMESCALE, result.modelType)
    }

    /**
     * 测试持续息屏待机续航推算（T_off = E_usable / P_off）。
     */
    @Test
    fun testEstimateScreenOff() {
        val offPowerEstimator = PowerEstimator()
        offPowerEstimator.seedCurrentSession(durationMs = 3_600_000L, powerWatts = 0.385f)

        // 可用能量 19.25 Wh，息屏待机功率 0.385 W -> 理论待机 50 小时 = 180,000,000 ms
        val result = estimator.estimateScreenOff(
            usableEnergyWh = 19.25,
            offPowerEstimator = offPowerEstimator,
            batteryLevelPercent = 100.0f
        )

        assertNotNull(result.remainingMs)
        assertTrue("待机时长在 50 小时容差范围内", kotlin.math.abs(180_000_000L - result.remainingMs!!) <= 10L)
        assertEquals(0.385, result.predictedPowerWatts!!, 0.001)
    }

    /**
     * 测试全局综合续航推算（P_global = r_on * P_on + (1 - r_on) * P_off）。
     */
    @Test
    fun testEstimateGlobalComposite() {
        val onEstimator = PowerEstimator()
        onEstimator.seedCurrentSession(1_800_000L, 4.0f) // 亮屏 4.0W

        val offEstimator = PowerEstimator()
        offEstimator.seedCurrentSession(3_600_000L, 0.4f) // 息屏 0.4W

        // 用户日常 20% 亮屏 + 80% 息屏
        // P_global = 0.2 * 4.0 + 0.8 * 0.4 = 0.8 + 0.32 = 1.12 W
        // 可用能量 11.2 Wh -> 理论综合续航 10 小时 = 36,000,000 ms
        val result = estimator.estimateGlobal(
            usableEnergyWh = 11.2,
            screenOnRatio = 0.20,
            onPowerEstimator = onEstimator,
            offPowerEstimator = offEstimator
        )

        assertNotNull(result.remainingMs)
        assertEquals(36_000_000L, result.remainingMs!!)
        assertEquals(1.12, result.predictedPowerWatts!!, 0.01)
        assertEquals(RuntimeModelType.GLOBAL_COMPOSITE, result.modelType)
    }

    /**
     * 测试 estimateTripleRuntimes 一站式三维续航推算入口。
     */
    @Test
    fun testEstimateTripleRuntimesOneStop() {
        val overview = PowerOverviewStats(
            avgPowerWatts = 1.12f,
            screenOnPowerWatts = 4.0f,
            screenOffPowerWatts = 0.4f,
            screenOnDurationMs = 1_800_000L,
            screenOffDurationMs = 7_200_000L,
            totalDurationMs = 9_000_000L
        )

        val sevenDays = SevenDaysDischargeStats(
            screenOnDischargeRatePercentPerHour = 20.0f, // 20%/h 在 20Wh 对应 4.0W
            screenOffDischargeRatePercentPerHour = 2.0f,  // 2%/h 在 20Wh 对应 0.4W
            globalDischargeRatePercentPerHour = 5.6f,
            sceneScreenOnRatio = 0.20f
        )

        val triple = estimator.estimateTripleRuntimes(
            overview = overview,
            sevenDaysStats = sevenDays,
            batteryLevelPercent = 50.0f,
            totalCapacityWh = 20.0f,
            tempCelsius = 25.0f
        )

        // 剩余 10Wh
        assertNotNull(triple.screenOnEstimate.remainingMs)
        assertNotNull(triple.screenOffEstimate.remainingMs)
        assertNotNull(triple.globalEstimate.remainingMs)
    }

    /**
     * 验证数据缺失时严谨返回 null，严禁伪造硬编码假数据。
     */
    @Test
    fun testMissingDataReturnsNull() {
        val onEstimator = PowerEstimator()
        val result = estimator.estimateScreenOn(null, onEstimator)
        assertNull("无能量时续航必须为 null", result.remainingMs)
        assertEquals(RuntimeModelType.INSUFFICIENT_DATA, result.modelType)
    }
}
