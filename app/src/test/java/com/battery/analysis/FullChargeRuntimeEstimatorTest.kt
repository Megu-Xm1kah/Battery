package com.battery.analysis

import com.battery.analysis.domain.estimator.FullChargeEnergyEstimator
import com.battery.analysis.domain.estimator.FullChargeRuntimeEstimator
import com.battery.analysis.domain.estimator.PowerRegimeClassifier
import com.battery.analysis.domain.estimator.PowerSampleNormalizer
import com.battery.analysis.domain.estimator.UsageProfileEstimator
import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.DataSufficiencyLevel
import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.domain.estimator.model.EnergySource
import com.battery.analysis.domain.estimator.model.FullChargeEnergyEstimate
import com.battery.analysis.domain.estimator.model.PowerRegime
import com.battery.analysis.domain.estimator.model.RuntimeConfidenceLevel
import com.battery.analysis.domain.estimator.model.RuntimeModelVersion
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerDischargePoint
import com.battery.analysis.model.PowerUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 满电理论续航估计引擎（FullChargeRuntimeEstimator v2.0）专项单元测试类。
 * 覆盖验证：
 * 1. 恒定功耗与多工况分段功耗的物理能量做功守恒；
 * 2. 持续时间加权 vs 样本计数的抗采样偏置能力；
 * 3. 满充能量多级来源优先级与防二次 SOH 衰减扣除；
 * 4. 168 小时历史放电有效区间规范化与边界物理等比裁剪；
 * 5. 工况分布不足时的全局加权基准安全平滑降级；
 * 6. 零假数据原则：数据缺失、历史不足或非物理值时的安全 null 防护。
 */
class FullChargeRuntimeEstimatorTest {

    private val estimator = FullChargeRuntimeEstimator()
    private val energyEstimator = FullChargeEnergyEstimator()
    private val profileEstimator = UsageProfileEstimator()
    private val sampleNormalizer = PowerSampleNormalizer()

    /**
     * 测试恒定功耗与分段多工况放电下的物理能量做功守恒推导。
     * 验证 T_full = E_full / P_typical 绝对精确。
     */
    @Test
    fun testConstantPowerAndSegmentPowerConservation() {
        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )

        // 构造两段典型区间：
        // 1. 息屏待机 8 小时 (28,800,000 ms)，平均功率 0.25 W -> 能耗 2.0 Wh
        // 2. 亮屏日常 2 小时 (7,200,000 ms)，平均功率 4.0 W -> 能耗 8.0 Wh
        // 总时长 10 小时，总能耗 10.0 Wh，全局功率 1.0 W
        // 待机时长占比 80%，亮屏占比 20% -> 典型综合功率 = 0.8 * 0.25 + 0.2 * 4.0 = 0.2 + 0.8 = 1.0 W
        // 满电 20.0 Wh 下理论综合续航应精确为 20 小时 = 72,000,000 ms
        val now = System.currentTimeMillis()
        val offDur = 8L * 3600L * 1000L
        val onDur = 2L * 3600L * 1000L

        val intervals = listOf(
            DischargeInterval(
                startTimeMs = now - offDur - onDur,
                endTimeMs = now - onDur,
                durationMs = offDur,
                energyWh = 2.0,
                powerWatts = 0.25,
                isScreenOn = false
            ),
            DischargeInterval(
                startTimeMs = now - onDur,
                endTimeMs = now,
                durationMs = onDur,
                energyWh = 8.0,
                powerWatts = 4.0,
                isScreenOn = true
            )
        )

        val result = estimator.estimateFromIntervals(intervals, fullEnergy)

        assertNotNull("满电理论续航结果不为 null", result.fullRuntimeMs)
        val expectedMs = 20L * 3600L * 1000L
        assertTrue(
            "满电全局续航精确为 20 小时 (当前: ${result.fullRuntimeMs})",
            abs(expectedMs - result.fullRuntimeMs!!) <= 1000L
        )
        assertEquals("预测综合功率为 1.0 W", 1.0, result.predictedPowerWatts!!, 0.001)

        // 验证三维满电中的亮屏与息屏独立续航与典型功率
        assertNotNull("满电纯亮屏续航不为 null", result.screenOnFullRuntimeMs)
        val expectedOnMs = 5L * 3600L * 1000L
        assertTrue(
            "满电纯亮屏续航精确为 5 小时 (当前: ${result.screenOnFullRuntimeMs})",
            abs(expectedOnMs - result.screenOnFullRuntimeMs!!) <= 1000L
        )
        assertEquals("预测亮屏典型功率为 4.0 W", 4.0, result.predictedScreenOnPowerWatts!!, 0.001)

        assertNotNull("满电纯息屏待机不为 null", result.screenOffFullRuntimeMs)
        val expectedOffMs = 80L * 3600L * 1000L
        assertTrue(
            "满电纯待机时长精确为 80 小时 (当前: ${result.screenOffFullRuntimeMs})",
            abs(expectedOffMs - result.screenOffFullRuntimeMs!!) <= 1000L
        )
        assertEquals("预测息屏典型功率为 0.25 W", 0.25, result.predictedScreenOffPowerWatts!!, 0.001)

        assertTrue("成功启用了工况条件化典型模型", result.usedRegimeModel)
        assertEquals(RuntimeModelVersion.FULL_RUNTIME_V2_2, result.modelVersion)
        assertTrue("结果包含质量依据说明", result.qualityReasons.isNotEmpty())
    }

    /**
     * 验证工况时间占比严格遵循物理持续时间加权，彻底消除样本点数偏置。
     */
    @Test
    fun testDurationWeightedTimeRatiosVersusSampleCount() {
        // 模拟息屏待机 9 小时 (32,400,000 ms)，但只有 1 个采样区间
        // 模拟亮屏高频采样 1 小时 (3,600,000 ms)，包含 100 个微小区间
        // 物理持续时间：待机 90%，亮屏 10%
        val durByRegime = mapOf(
            PowerRegime.OFF_LOW to 9L * 3600L * 1000L,
            PowerRegime.ON_NORMAL to 1L * 3600L * 1000L
        )

        val ratios = profileEstimator.computeTimeRatios(durByRegime)

        assertEquals("待机时间占比严格为 0.90", 0.90, ratios[PowerRegime.OFF_LOW]!!, 0.0001)
        assertEquals("亮屏时间占比严格为 0.10", 0.10, ratios[PowerRegime.ON_NORMAL]!!, 0.0001)
        val sum = ratios.values.sum()
        assertEquals("概率归一化严格等于 1.0", 1.0, sum, 0.0001)
    }

    /**
     * 测试满充可用能量多级获取优先级与防二次 SOH 衰减扣除。
     */
    @Test
    fun testFullChargeEnergyEstimatorPriorities() {
        // 1. 真实满充容量 FCC 折算测试（首选优先级 1）：5000 mAh @ 3.85V -> 19.25 Wh
        // 即使传入了 SOH 80%，FCC 已经反映老化，绝不得二次乘 0.8
        val estFcc = energyEstimator.estimateFullEnergy(
            fccMah = 5000f,
            designCapacityMah = 5500f,
            sohPercent = 80f,
            nominalVoltage = 3.85
        )
        assertEquals(EnergySource.FCC_TIMES_NOMINAL_VOLTAGE, estFcc.source)
        assertEquals("FCC折算精确为 19.25 Wh，未被二次扣除SOH", 19.25, estFcc.energyWh!!, 0.01)
        assertEquals(DataQuality.VALID, estFcc.quality)

        // 2. 当同时传入 FCC 与原生剩余能量寄存器时，必须优先采用 FCC，杜绝 SOC 量化跳变
        val estFccOverNative = energyEstimator.estimateFullEnergy(
            nativeEnergyNwh = 10_000_000_000L,
            batteryPercent = 50,
            fccMah = 5000f,
            nominalVoltage = 3.85
        )
        assertEquals("FCC 必须优先于单点剩余能量反推", EnergySource.FCC_TIMES_NOMINAL_VOLTAGE, estFccOverNative.source)
        assertEquals(19.25, estFccOverNative.energyWh!!, 0.01)

        // 3. 设计容量 × SOH 折算测试：5000 mAh * 80% @ 3.85V -> 4000 mAh @ 3.85V = 15.4 Wh
        val estDesign = energyEstimator.estimateFullEnergy(
            designCapacityMah = 5000f,
            sohPercent = 80f,
            nominalVoltage = 3.85
        )
        assertEquals(EnergySource.DESIGN_CAPACITY_TIMES_HEALTH, estDesign.source)
        assertEquals(15.4, estDesign.energyWh!!, 0.01)

        // 4. 原生能量计数器单点反推（降级备选）：无 FCC与设计容量时，当前 50% 电量读取到 10.0 Wh -> 满电 20.0 Wh (LOW_QUALITY)
        val estNative = energyEstimator.estimateFullEnergy(
            nativeEnergyNwh = 10_000_000_000L,
            batteryPercent = 50
        )
        assertEquals(EnergySource.NATIVE_FULL_ENERGY, estNative.source)
        assertEquals(20.0, estNative.energyWh!!, 0.01)
        assertEquals(DataQuality.LOW_QUALITY, estNative.quality)

        // 5. 数据全缺失时严格返回 UNAVAILABLE，绝不制造保底虚构数据
        val estMissing = energyEstimator.estimateFullEnergy()
        assertEquals(EnergySource.UNAVAILABLE, estMissing.source)
        assertNull(estMissing.energyWh)
        assertEquals(DataQuality.INVALID, estMissing.quality)
    }

    /**
     * 测试有效放电区间提取并在跨越 168 小时自然墙钟时间窗口边界时执行物理等比截断。
     */
    @Test
    fun testDischargeIntervalBoundaryClipping() {
        val sevenDaysMs = 7L * 24L * 3600L * 1000L
        val now = System.currentTimeMillis()

        // 构造一个在当前窗口内的 100 小时会话（从 now - 100h 到 now）
        val currentOverview = PowerOverviewStats(
            avgPowerWatts = 2.0f,
            totalEnergyWh = 200f,
            totalDurationMs = 100L * 3600L * 1000L,
            screenOnDurationMs = 50L * 3600L * 1000L,
            screenOnEnergyWh = 150f,
            screenOffDurationMs = 50L * 3600L * 1000L,
            screenOffEnergyWh = 50f
        )

        // 构造一个结束于 now - 120h，持续 100 小时的历史记录（跨越 now - 168h 边界 52 小时）
        // 落在 [now - 168h, now] 窗口内的有效放电时长为 100h + 48h = 148h
        val record = PowerUsageRecord(
            id = now - 120L * 3600L * 1000L,
            recordTime = "2026-10-10 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 15f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationText = "50h",
            screenOffDurationText = "50h",
            totalDurationText = "100h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 100f,
            screenOffEnergyWh = 10f,
            totalEnergyWh = 110f
        )

        val intervals = sampleNormalizer.extractDischargeIntervals(
            currentOverview = currentOverview,
            allRecords = listOf(record),
            maxTargetDurationMs = sevenDaysMs,
            baseTimestamp = now
        )

        val totalMs = intervals.sumOf { it.durationMs }
        val expectedMs = 148L * 3600L * 1000L
        assertEquals("落在 168 小时自然时间窗口内的物理时长精确为 148 小时", expectedMs, totalMs)
        assertTrue("存在区间被精准截断", intervals.isNotEmpty())
    }

    /**
     * 测试工况类别不足时的安全降级机制：平滑退回全局加权均值基准模型。
     */
    @Test
    fun testFallbackWhenRegimesInsufficient() {
        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )

        // 仅包含单一工况（只有亮屏 1 小时 2.0 W -> 2.0 Wh）
        val intervals = listOf(
            DischargeInterval(
                startTimeMs = 0L,
                endTimeMs = 3600_000L,
                durationMs = 3600_000L,
                energyWh = 2.0,
                powerWatts = 2.0,
                isScreenOn = true
            )
        )

        val result = estimator.estimateFromIntervals(intervals, fullEnergy)

        assertNotNull("降级模式下仍可输出满电续航", result.fullRuntimeMs)
        // 满电 20Wh / 2W = 10 小时 = 36,000,000 ms
        assertEquals(36_000_000L, result.fullRuntimeMs!!)
        assertFalse("标记未使用工况模型（已降级）", result.usedRegimeModel)
        assertNotNull("包含降级原因说明", result.fallbackReason)
    }

    /**
     * 验证数据缺失、有效时长不足 10 分钟或非有限数值时的安全防护（零假数据）。
     */
    @Test
    fun testMissingDataProtection() {
        // 1. 能量不可用
        val noEnergy = FullChargeEnergyEstimate(
            energyWh = null,
            source = EnergySource.UNAVAILABLE,
            quality = DataQuality.INVALID
        )
        val r1 = estimator.estimateFromIntervals(emptyList(), noEnergy)
        assertNull("无能量时续航必须为 null", r1.fullRuntimeMs)
        assertEquals(RuntimeConfidenceLevel.UNAVAILABLE, r1.confidenceLevel)

        // 2. 历史时长不足 10 分钟（例如仅有 5 分钟 = 300,000 ms）
        val validEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )
        val shortIntervals = listOf(
            DischargeInterval(
                startTimeMs = 0L,
                endTimeMs = 300_000L,
                durationMs = 300_000L,
                energyWh = 0.2,
                powerWatts = 2.4,
                isScreenOn = true
            )
        )
        val r2 = estimator.estimateFromIntervals(shortIntervals, validEnergy)
        assertNull("历史放电不足 10 分钟时严禁伪造续航", r2.fullRuntimeMs)
        assertEquals(RuntimeConfidenceLevel.UNAVAILABLE, r2.confidenceLevel)
    }

    /**
     * 测试自适应历史时间窗口场景 1：
     * 当最近 7 天内具备充足的亮屏与息屏样本时（如亮屏 8h、息屏 16h、总计 24h），
     * 算法立即停止向前回溯，统计窗口严格锁定为 7.0 天，全样本衰减权重严格为 1.0，充分性评级为 SUFFICIENT。
     */
    @Test
    fun testAdaptiveWindowSufficientIn7DaysStopsEarly() {
        val now = System.currentTimeMillis()
        // 构造近 3 天内的充足样本：
        // 1. 亮屏放电 8 小时 (28,800,000 ms)
        // 2. 息屏放电 16 小时 (57,600,000 ms)
        val rec1 = PowerUsageRecord(
            id = now - 24L * 3600L * 1000L,
            recordTime = "2026-10-15 10:00:00",
            levelPercent = 90,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 20f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationText = "8h",
            screenOffDurationText = "16h",
            totalDurationText = "24h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 16f,
            screenOffEnergyWh = 3.2f,
            totalEnergyWh = 19.2f
        )

        // 构造一个 15 天前的旧记录（若 7 天充分，绝不应回溯到此条记录）
        val oldRec = PowerUsageRecord(
            id = now - 15L * 24L * 3600L * 1000L,
            recordTime = "2026-10-01 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 50f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationText = "20h",
            screenOffDurationText = "30h",
            totalDurationText = "50h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 40f,
            screenOffEnergyWh = 6f,
            totalEnergyWh = 46f
        )

        val (intervals, metrics) = sampleNormalizer.extractAdaptiveDischargeIntervals(
            currentOverview = null,
            allRecords = listOf(rec1, oldRec),
            baseTimestamp = now
        )

        assertEquals("7天内充足，窗口跨度严格为 7.0 天", 7.0, metrics.windowDays, 0.01)
        assertEquals("充分性评级为 SUFFICIENT", DataSufficiencyLevel.SUFFICIENT, metrics.sufficiency)
        assertTrue("所有提取的样本衰减权重均为 1.0", intervals.all { it.ageWeight == 1.0 })
        assertTrue("15天前记录未被包含", intervals.none { it.endTimeMs < now - 7L * 24L * 3600L * 1000L })
    }

    /**
     * 测试自适应历史时间窗口场景 2：
     * 当最近 7 天内样本不足（仅有 2 小时亮屏），算法自适应向前扩展至 14~30 天并补齐样本；
     * 超出 7 天的旧记录严格计算指数衰减权重 w_age = exp(-(a - 7.0) / 7.0) < 1.0。
     */
    @Test
    fun testAdaptiveWindowExpandsWhen7DaysSparseWithDecay() {
        val now = System.currentTimeMillis()
        // 近 2 天内仅有 2 小时亮屏放电（不足以满足充足标准）
        val sparseRec = PowerUsageRecord(
            id = now - 24L * 3600L * 1000L,
            recordTime = "2026-10-15 10:00:00",
            levelPercent = 90,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 4f,
            isCharging = false,
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "2h",
            screenOffDurationText = "0h",
            totalDurationText = "2h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 4f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 4f
        )

        // 14 天前存在 20 小时息屏记录与 10 小时亮屏记录
        val pastRec = PowerUsageRecord(
            id = now - 14L * 24L * 3600L * 1000L,
            recordTime = "2026-10-02 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 25f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationText = "10h",
            screenOffDurationText = "20h",
            totalDurationText = "30h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 20f,
            screenOffEnergyWh = 4f,
            totalEnergyWh = 24f
        )

        val (intervals, metrics) = sampleNormalizer.extractAdaptiveDischargeIntervals(
            currentOverview = null,
            allRecords = listOf(sparseRec, pastRec),
            baseTimestamp = now
        )

        assertTrue("窗口成功扩展至大于 7 天 (实际: ${metrics.windowDays})", metrics.windowDays >= 14.0)
        assertTrue("存在时间衰减权重 < 1.0 的旧样本", intervals.any { it.ageWeight < 1.0 })

        // 14 天前的样本年龄为 14 天，超出 7 天部分为 7 天，exp(-7/7) = exp(-1) ≈ 0.368
        val oldInterval = intervals.first { it.endTimeMs <= now - 10L * 24L * 3600L * 1000L }
        assertEquals("14天前样本权重严格约为 0.368", 0.368, oldInterval.ageWeight, 0.05)

        // 验证 FullChargeRuntimeEstimator 一站式计算正确融入 metrics
        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )
        val result = estimator.estimateFromIntervals(intervals, fullEnergy, metrics)
        assertNotNull("满电续航推算成功", result.fullRuntimeMs)
        assertEquals("结果中历史窗口天数与 metrics 一致", metrics.windowDays, result.historyWindowDays, 0.01)
        assertTrue("质量说明中明确注明自适应扩展", result.qualityReasons.any { it.contains("自适应扩展") || it.contains("衰减加权") })
    }

    /**
     * 测试自适应历史时间窗口场景 3：
     * 30 天自然时间硬上限截断保护；早于 30 天的历史记录绝不纳入统计；
     * 样本严重不足时如实标记为 INSUFFICIENT，置信度降为 LOW（严格零假数据）。
     */
    @Test
    fun testAdaptiveWindowMax30DaysHardCap() {
        val now = System.currentTimeMillis()
        // 构造仅有 1 小时极稀疏记录（20 天前）
        val sparse20dRec = PowerUsageRecord(
            id = now - 20L * 24L * 3600L * 1000L,
            recordTime = "2026-09-26 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 2f,
            isCharging = false,
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "1h",
            screenOffDurationText = "0h",
            totalDurationText = "1h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 2f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 2f
        )

        // 构造 45 天前的陈旧记录（严重超出 30 天硬上限）
        val ancient45dRec = PowerUsageRecord(
            id = now - 45L * 24L * 3600L * 1000L,
            recordTime = "2026-09-01 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 20f,
            isCharging = false,
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "10h",
            screenOffDurationText = "0h",
            totalDurationText = "10h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 20f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 20f
        )

        val (intervals, metrics) = sampleNormalizer.extractAdaptiveDischargeIntervals(
            currentOverview = null,
            allRecords = listOf(sparse20dRec, ancient45dRec),
            baseTimestamp = now
        )

        assertTrue("窗口跨度不超过 30 天上限", metrics.windowDays <= 30.0)
        assertEquals("样本极少时充分性评定为 INSUFFICIENT", DataSufficiencyLevel.INSUFFICIENT, metrics.sufficiency)
        assertTrue("早于 30 天前记录完全被忽略", intervals.none { it.endTimeMs < now - 30L * 24L * 3600L * 1000L })
    }

    /**
     * 测试 1：7 天数据充足（亮屏 20h，至少 2 个稳定工况）。
     * 预期：自适应历史窗口选择器锁定窗口为 7 天，停止向前扩展。
     */
    @Test
    fun testCase1_7DaysSufficientStopsExtension() {
        val now = System.currentTimeMillis()
        val selector = com.battery.analysis.domain.estimator.DefaultHistoricalWindowSelector(
            sampleNormalizer = sampleNormalizer
        )

        // 构造近 3 天内包含 20 小时亮屏、涵盖日常与轻载两个稳定工况的记录
        // 记录 1：亮屏轻载 5 小时 (1.0 W)
        val recLight = PowerUsageRecord(
            id = now - 24L * 3600L * 1000L,
            recordTime = "2026-10-18 10:00:00",
            levelPercent = 90,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 5f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 1.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "5h",
            screenOffDurationText = "0h",
            totalDurationText = "5h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 5f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 5f
        )

        // 记录 2：亮屏日常 15 小时 (1.8 W)
        val recNormal = PowerUsageRecord(
            id = now - 48L * 3600L * 1000L,
            recordTime = "2026-10-17 10:00:00",
            levelPercent = 70,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 27f,
            isCharging = false,
            avgPowerWatts = 1.8f,
            screenOnPowerWatts = 1.8f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "15h",
            screenOffDurationText = "0h",
            totalDurationText = "15h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 27f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 27f
        )

        val window = selector.select(now, listOf(recLight, recNormal))
        assertEquals("7天充足时锁定为 7 天窗口", 7, window.windowDays)
        assertEquals("充分性为 SUFFICIENT", DataSufficiencyLevel.SUFFICIENT, window.metrics.sufficiency)
    }

    /**
     * 测试 2：7 天不足（亮屏仅 2h）。
     * 预期：自适应扩展到 14 天。
     */
    @Test
    fun testCase2_7DaysSparseExpandsTo14Days() {
        val now = System.currentTimeMillis()
        val selector = com.battery.analysis.domain.estimator.DefaultHistoricalWindowSelector(
            sampleNormalizer = sampleNormalizer
        )

        // 近 2 天内仅有 2 小时亮屏
        val recSparse7d = PowerUsageRecord(
            id = now - 24L * 3600L * 1000L,
            recordTime = "2026-10-18 10:00:00",
            levelPercent = 95,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 4f,
            isCharging = false,
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "2h",
            screenOffDurationText = "0h",
            totalDurationText = "2h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 4f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 4f
        )

        // 10 天前有 15 小时多工况记录（落在 14 天窗口内）
        val rec10d = PowerUsageRecord(
            id = now - 10L * 24L * 3600L * 1000L,
            recordTime = "2026-10-09 10:00:00",
            levelPercent = 60,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 25f,
            isCharging = false,
            avgPowerWatts = 1.5f,
            screenOnPowerWatts = 1.0f,
            screenOffPowerWatts = 0.2f,
            screenOnDurationText = "12h",
            screenOffDurationText = "10h",
            totalDurationText = "22h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 12f,
            screenOffEnergyWh = 2f,
            totalEnergyWh = 14f
        )

        val window = selector.select(now, listOf(recSparse7d, rec10d))
        assertEquals("7天不足时自适应扩展至 14 天", 14, window.windowDays)
    }

    /**
     * 测试 3：14 天仍不足。
     * 预期：自适应扩展到 30 天。
     */
    @Test
    fun testCase3_14DaysSparseExpandsTo30Days() {
        val now = System.currentTimeMillis()
        val selector = com.battery.analysis.domain.estimator.DefaultHistoricalWindowSelector(
            sampleNormalizer = sampleNormalizer
        )

        // 7天内仅有 1h 亮屏
        val rec1d = PowerUsageRecord(
            id = now - 24L * 3600L * 1000L,
            recordTime = "2026-10-18 10:00:00",
            levelPercent = 95,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 2f,
            isCharging = false,
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "1h",
            screenOffDurationText = "0h",
            totalDurationText = "1h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 2f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 2f
        )

        // 20 天前有 15h 记录（落在 30 天窗口内）
        val rec20d = PowerUsageRecord(
            id = now - 20L * 24L * 3600L * 1000L,
            recordTime = "2026-09-29 10:00:00",
            levelPercent = 60,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 30f,
            isCharging = false,
            avgPowerWatts = 1.8f,
            screenOnPowerWatts = 1.8f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "15h",
            screenOffDurationText = "0h",
            totalDurationText = "15h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 27f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 27f
        )

        val window = selector.select(now, listOf(rec1d, rec20d))
        assertEquals("14天仍不足时扩展至 30 天", 30, window.windowDays)
    }

    /**
     * 测试 4：30 天仍不足。
     * 预期：停止扩展并如实降低充分性与置信度。
     */
    @Test
    fun testCase4_30DaysSparseStopsAndLowConfidence() {
        val now = System.currentTimeMillis()
        val selector = com.battery.analysis.domain.estimator.DefaultHistoricalWindowSelector(
            sampleNormalizer = sampleNormalizer
        )

        // 仅有 25 天前 1 小时极稀疏记录
        val recSparse = PowerUsageRecord(
            id = now - 25L * 24L * 3600L * 1000L,
            recordTime = "2026-09-24 10:00:00",
            levelPercent = 90,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 2f,
            isCharging = false,
            avgPowerWatts = 2.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0f,
            screenOnDurationText = "1h",
            screenOffDurationText = "0h",
            totalDurationText = "1h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 2f,
            screenOffEnergyWh = 0f,
            totalEnergyWh = 2f
        )

        val window = selector.select(now, listOf(recSparse))
        assertEquals("达到 30 天硬上限停止扩展", 30, window.windowDays)
        assertEquals("数据充分性评为 INSUFFICIENT", DataSufficiencyLevel.INSUFFICIENT, window.metrics.sufficiency)

        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )
        val result = estimator.estimateFromIntervals(window.intervals, fullEnergy, window.metrics)
        assertEquals("置信度降为 LOW", RuntimeConfidenceLevel.LOW, result.confidenceLevel)
    }

    /**
     * 测试 5：采样频率变化（相同真实持续时间、不同采样频率）。
     * 预期：时间占比严格根据真实持续时间 Δt 积分计算，两工况时间占比基本一致（各 50%），消除采样频率偏差。
     */
    @Test
    fun testCase5_SamplingFrequencyInvariantTimeRatios() {
        val now = System.currentTimeMillis()
        // 工况 A（日常）：高频采样，包含 60 个 1 分钟区间，累计持续 60 分钟 = 3,600,000 ms
        val highFreqIntervals = (0 until 60).map { i ->
            val start = now - (120 - i) * 60_000L
            DischargeInterval(
                startTimeMs = start,
                endTimeMs = start + 60_000L,
                durationMs = 60_000L,
                energyWh = 1.8 * (60_000.0 / 3600000.0),
                powerWatts = 1.8,
                isScreenOn = true
            )
        }

        // 工况 B（轻载）：低频采样，仅有 1 个 60 分钟区间，累计持续 60 分钟 = 3,600,000 ms
        val lowFreqInterval = DischargeInterval(
            startTimeMs = now - 60 * 60_000L,
            endTimeMs = now,
            durationMs = 60 * 60_000L,
            energyWh = 1.0 * (3600_000.0 / 3600000.0),
            powerWatts = 1.0,
            isScreenOn = true
        )

        val allIntervals = highFreqIntervals + listOf(lowFreqInterval)
        val durByRegime = mutableMapOf<PowerRegime, Long>()
        val classifier = PowerRegimeClassifier()

        for (interval in allIntervals) {
            val regime = classifier.classify(interval)
            durByRegime[regime] = (durByRegime[regime] ?: 0L) + interval.durationMs
        }

        val ratios = profileEstimator.computeTimeRatios(durByRegime)
        val ratioNormal = ratios[PowerRegime.ON_NORMAL] ?: 0.0
        val ratioLight = ratios[PowerRegime.ON_LIGHT] ?: 0.0

        assertEquals("高频与低频相同物理时长下，时间占比各为 50%", 0.50, ratioNormal, 0.01)
        assertEquals("低频工况时间占比亦严格为 50%", 0.50, ratioLight, 0.01)
    }

    /**
     * 测试 6：工况切换（1.0W -> 1.6W -> 4.0W）。
     * 预期：正确划分形成 LIGHT、NORMAL 和 HEAVY 三大工况。
     */
    @Test
    fun testCase6_RegimeSwitchingLightNormalHeavy() {
        val classifier = PowerRegimeClassifier()
        val now = System.currentTimeMillis()

        val intervalLight = DischargeInterval(
            startTimeMs = now - 1800_000L,
            endTimeMs = now - 1200_000L,
            durationMs = 600_000L, // 10分钟
            energyWh = 1.0 * (600_000.0 / 3600000.0),
            powerWatts = 1.0,
            isScreenOn = true
        )

        val intervalNormal = DischargeInterval(
            startTimeMs = now - 1200_000L,
            endTimeMs = now - 600_000L,
            durationMs = 600_000L, // 10分钟
            energyWh = 1.6 * (600_000.0 / 3600000.0),
            powerWatts = 1.6,
            isScreenOn = true
        )

        val intervalHeavy = DischargeInterval(
            startTimeMs = now - 600_000L,
            endTimeMs = now,
            durationMs = 600_000L, // 10分钟持续
            energyWh = 4.0 * (600_000.0 / 3600000.0),
            powerWatts = 4.0,
            isScreenOn = true
        )

        assertEquals("1.0W 亮屏判定为 ON_LIGHT", PowerRegime.ON_LIGHT, classifier.classify(intervalLight))
        assertEquals("1.6W 亮屏判定为 ON_NORMAL", PowerRegime.ON_NORMAL, classifier.classify(intervalNormal))
        assertEquals("4.0W 持续亮屏判定为 ON_HEAVY", PowerRegime.ON_HEAVY, classifier.classify(intervalHeavy))
    }

    /**
     * 测试 7：瞬时尖峰（1.6W -> 1.7W -> 5.0W 1秒 -> 1.6W）。
     * 预期：5.0W 瞬态短脉冲（1 秒）不直接形成 ON_HEAVY 重载工况。
     */
    @Test
    fun testCase7_TransientSpikeDoesNotTriggerHeavy() {
        val classifier = PowerRegimeClassifier()
        val now = System.currentTimeMillis()

        val spikeInterval = DischargeInterval(
            startTimeMs = now - 1000L,
            endTimeMs = now,
            durationMs = 1000L, // 仅 1 秒
            energyWh = 5.0 * (1000.0 / 3600000.0),
            powerWatts = 5.0,
            isScreenOn = true
        )

        val regime = classifier.classify(spikeInterval)
        assertEquals("1秒 5.0W 瞬态尖峰不建立 ON_HEAVY，平滑归入 ON_NORMAL", PowerRegime.ON_NORMAL, regime)
    }

    /**
     * 测试 8：持续重载（1.6W -> 4.0W 持续 10 分钟）。
     * 预期：持续 10 分钟高功耗正确确立为真实 ON_HEAVY 重载工况。
     */
    @Test
    fun testCase8_SustainedHighLoadTriggersHeavy() {
        val classifier = PowerRegimeClassifier()
        val now = System.currentTimeMillis()

        val sustainedHeavy = DischargeInterval(
            startTimeMs = now - 600_000L,
            endTimeMs = now,
            durationMs = 600_000L, // 持续 10 分钟
            energyWh = 4.0 * (600_000.0 / 3600000.0),
            powerWatts = 4.0,
            isScreenOn = true
        )

        val regime = classifier.classify(sustainedHeavy)
        assertEquals("持续 10 分钟 4.0W 正确识别为 ON_HEAVY", PowerRegime.ON_HEAVY, regime)
    }

    /**
     * 测试 9：新旧模型结果相同（P_old = 1.58W, P_v21 = 1.58W）。
     * 预期：允许两个模型得出相同的满电续航数值，且 v2.1 具备可追溯依据说明。
     */
    @Test
    fun testCase9_IdenticalResultAllowedAndExplained() {
        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 29.69,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )

        // 构造两个亮屏工况，使其加权典型功耗恰好为 1.58W：
        // 轻载 1.0W (10 小时)，重载 2.16W (10 小时)
        // 加权典型功率 = (1.0 + 2.16) / 2 = 1.58 W
        // 全局平均功率亦为 1.58 W
        val now = System.currentTimeMillis()
        val dur = 10L * 3600L * 1000L
        val intervals = listOf(
            DischargeInterval(
                startTimeMs = now - 2 * dur,
                endTimeMs = now - dur,
                durationMs = dur,
                energyWh = 10.0,
                powerWatts = 1.0,
                isScreenOn = true
            ),
            DischargeInterval(
                startTimeMs = now - dur,
                endTimeMs = now,
                durationMs = dur,
                energyWh = 21.6,
                powerWatts = 2.16,
                isScreenOn = true
            )
        )

        val result = estimator.estimateFromIntervals(intervals, fullEnergy)
        assertNotNull("满电纯亮屏续航不为 null", result.screenOnFullRuntimeMs)
        assertEquals("亮屏典型功率为 1.58 W", 1.58, result.predictedScreenOnPowerWatts!!, 0.01)

        // 29.69 Wh / 1.58 W ≈ 18.79 小时 = 67,645,569 ms
        val expectedHours = 29.69 / 1.58
        val expectedMs = (expectedHours * 3600.0 * 1000.0).toLong()
        assertEquals("新旧模型预测一致且完全符合物理守恒", expectedMs, result.screenOnFullRuntimeMs!!)

        assertNotNull("具备 RuntimeProfile 画像实体以供追溯", result.profile)
        assertEquals("RuntimeProfile 中的典型亮屏功耗为 1.58 W", 1.58, result.profile!!.typicalScreenOnPowerW!!, 0.01)
        assertTrue("结果中包含一致性解释说明", result.qualityReasons.any { it.contains("一致") || it.contains("贴合") })
    }

    /**
     * 测试 10：v2.2 细粒度采样切片能够从单一混合会话中成功分离出轻度、普通与重度三大工况。
     * 针对用户痛点：解决过去会话级宏观平均导致 100% 归为 ON_NORMAL 的问题。
     */
    @Test
    fun testTrendPointsSlicing_SeparatesLightNormalHeavy() {
        val baseTs = System.currentTimeMillis() - 3600_000L
        val points = mutableListOf<PowerDischargePoint>()

        // 阶段 1：轻度工况（如息屏或低亮度阅读 0.8W），持续 15 分钟（900 秒），每 30 秒一个采样点
        for (i in 0..30) {
            points.add(
                PowerDischargePoint(
                    timestamp = baseTs + i * 30_000L,
                    elapsedHours = (i * 30f) / 3600f,
                    batteryLevel = 100 - i / 10,
                    voltageVolts = 4.2f,
                    temperature = 25.0f,
                    powerWatts = 0.8f,
                    isScreenOn = true
                )
            )
        }

        // 阶段 2：普通日常工况（如即时通讯 1.8W），持续 30 分钟（1800 秒），每 30 秒一个采样点
        val phase2StartTs = baseTs + 900_000L
        for (i in 1..60) {
            points.add(
                PowerDischargePoint(
                    timestamp = phase2StartTs + i * 30_000L,
                    elapsedHours = (900f + i * 30f) / 3600f,
                    batteryLevel = 97 - i / 5,
                    voltageVolts = 4.0f,
                    temperature = 28.0f,
                    powerWatts = 1.8f,
                    isScreenOn = true
                )
            )
        }

        // 阶段 3：重载工况（如高画质游戏 4.5W），持续 15 分钟（900 秒），每 30 秒一个采样点
        val phase3StartTs = phase2StartTs + 1800_000L
        for (i in 1..30) {
            points.add(
                PowerDischargePoint(
                    timestamp = phase3StartTs + i * 30_000L,
                    elapsedHours = (2700f + i * 30f) / 3600f,
                    batteryLevel = 85 - i / 3,
                    voltageVolts = 3.8f,
                    temperature = 36.0f,
                    powerWatts = 4.5f,
                    isScreenOn = true
                )
            )
        }

        val intervals = sampleNormalizer.sliceTrendPointsToIntervals(points)
        assertFalse("采样点切片列表不为空", intervals.isEmpty())

        val classifier = PowerRegimeClassifier()
        val regimes = intervals.map { classifier.classify(it) }.toSet()

        assertTrue("成功分离并包含轻度工况 ON_LIGHT", regimes.contains(PowerRegime.ON_LIGHT))
        assertTrue("成功分离并包含普通工况 ON_NORMAL", regimes.contains(PowerRegime.ON_NORMAL))
        assertTrue("成功分离并包含重度工况 ON_HEAVY", regimes.contains(PowerRegime.ON_HEAVY))
    }

    /**
     * 测试 11：v2.2 采样切片对重载工况的稳定维持门禁校验（持续时间 >= 10 秒）。
     * 验证：5 秒 5.0W 瞬间脉冲被吸收防抖合并，不误判为 ON_HEAVY；而持续 30 秒 4.5W 成功识别为 ON_HEAVY。
     */
    @Test
    fun testTrendPointsSlicing_HeavySustainGate() {
        val baseTs = System.currentTimeMillis()
        val points = mutableListOf<PowerDischargePoint>()

        // 基础普通工况：持续 60 秒 1.6W
        points.add(PowerDischargePoint(timestamp = baseTs, elapsedHours = 0f, batteryLevel = 100, powerWatts = 1.6f, isScreenOn = true))
        points.add(PowerDischargePoint(timestamp = baseTs + 60_000L, elapsedHours = 60f / 3600f, batteryLevel = 100, powerWatts = 1.6f, isScreenOn = true))

        // 短时脉冲：仅 5 秒的 5.0W 高功耗
        points.add(PowerDischargePoint(timestamp = baseTs + 65_000L, elapsedHours = 65f / 3600f, batteryLevel = 100, powerWatts = 5.0f, isScreenOn = true))

        // 回到普通工况：持续 60 秒 1.6W
        points.add(PowerDischargePoint(timestamp = baseTs + 125_000L, elapsedHours = 125f / 3600f, batteryLevel = 100, powerWatts = 1.6f, isScreenOn = true))

        val classifier = PowerRegimeClassifier()
        val shortSpikeIntervals = sampleNormalizer.sliceTrendPointsToIntervals(points)
        val shortRegimes = shortSpikeIntervals.map { classifier.classify(it) }.toSet()
        assertFalse("5秒短脉冲被吸收防抖合并，不应触发 ON_HEAVY", shortRegimes.contains(PowerRegime.ON_HEAVY))

        // 追加持续 30 秒的 4.5W 真实重载（两个连续的 4.5W 采样点，中间相距 30 秒）
        points.add(PowerDischargePoint(timestamp = baseTs + 130_000L, elapsedHours = 130f / 3600f, batteryLevel = 99, powerWatts = 4.5f, isScreenOn = true))
        points.add(PowerDischargePoint(timestamp = baseTs + 160_000L, elapsedHours = 160f / 3600f, batteryLevel = 99, powerWatts = 4.5f, isScreenOn = true))
        val sustainedIntervals = sampleNormalizer.sliceTrendPointsToIntervals(points)
        val sustainedRegimes = sustainedIntervals.map { classifier.classify(it) }.toSet()
        assertTrue("持续 30 秒重载高功耗成功触发确立 ON_HEAVY", sustainedRegimes.contains(PowerRegime.ON_HEAVY))
    }

    /**
     * 测试 12：v2.2 细粒度采样切片的物理能量与时长完全守恒校准。
     * 验证：无论离散采样点梯形积分如何波动，校准后的总时长严格等于真值，总能耗严格等于真值。
     */
    @Test
    fun testTrendPointsSlicing_EnergyConservation() {
        val baseTs = System.currentTimeMillis()
        val points = listOf(
            PowerDischargePoint(timestamp = baseTs, elapsedHours = 0f, batteryLevel = 100, powerWatts = 1.0f, isScreenOn = true),
            PowerDischargePoint(timestamp = baseTs + 60_000L, elapsedHours = 60f / 3600f, batteryLevel = 100, powerWatts = 2.0f, isScreenOn = true),
            PowerDischargePoint(timestamp = baseTs + 120_000L, elapsedHours = 120f / 3600f, batteryLevel = 99, powerWatts = 3.0f, isScreenOn = true)
        )

        val targetDurMs = 120_000L
        val targetEnergyWh = 0.070

        val intervals = sampleNormalizer.sliceTrendPointsToIntervals(
            points = points,
            expectedScreenOnDurMs = targetDurMs,
            expectedScreenOnEnergyWh = targetEnergyWh
        )

        val totalDurMs = intervals.sumOf { it.durationMs }
        val totalEnergyWh = intervals.sumOf { it.energyWh }

        assertEquals("切片总时长与预期真实亮屏时长完全守恒", targetDurMs, totalDurMs)
        assertEquals("切片总能量与预期真实亮屏能量完全物理守恒", targetEnergyWh, totalEnergyWh, 1e-6)
    }

    /**
     * 测试 13：v2.2 模型版本号校验与在无采样点记录时的安全平滑向后兼容降级。
     */
    @Test
    fun testV22_ModelVersion_And_FallbackToSessionLevel() {
        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )

        // 构造一条无采样点 trendPointsJson 的历史记录
        val now = System.currentTimeMillis()
        val legacyRecord = PowerUsageRecord(
            id = now - 3600_000L,
            recordTime = "2026-10-19 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 2.0f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 1.6f,
            screenOffPowerWatts = 0.4f,
            screenOnDurationText = "30m",
            screenOffDurationText = "30m",
            totalDurationText = "1h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 0.8f,
            screenOffEnergyWh = 0.2f,
            totalEnergyWh = 1.0f
        )

        val intervals = sampleNormalizer.extractDischargeIntervals(
            currentOverview = null,
            allRecords = listOf(legacyRecord),
            baseTimestamp = now
        )
        assertFalse("即使无采样点也能安全平滑降级为会话级区间", intervals.isEmpty())

        val result = estimator.estimateFromIntervals(intervals, fullEnergy)
        assertNotNull("满电理论续航结果不为 null", result.fullRuntimeMs)
        assertEquals("估计结果模型版本号已成功升级至 FULL_RUNTIME_V2_2", RuntimeModelVersion.FULL_RUNTIME_V2_2, result.modelVersion)
    }

    /**
     * 测试 14：验证息屏典型工况缺失时安全降级至全局加权均值 pGlobal，严禁把综合续航缩水为纯亮屏续航。
     * 当只有亮屏工况（例如 3.0W）与非息屏工况时，综合预测功率预测必须忠实等于全局样本能量与时间物理均值 pGlobal，
     * 且 usedRegimeModel 标记为 false，qualityReasons 中明确记录息屏样本缺失的降级说明。
     */
    @Test
    fun testFallbackWhenScreenOffMissing_UsesGlobalAverageNotScreenOnPower() {
        val fullEnergy = FullChargeEnergyEstimate(
            energyWh = 20.0,
            source = EnergySource.FCC_TIMES_NOMINAL_VOLTAGE,
            quality = DataQuality.VALID
        )

        val now = System.currentTimeMillis()
        // 构造仅有亮屏工况（轻载 1.0 W 持续 1 小时，中载 3.0 W 持续 3 小时），完全缺失息屏待机工况样本
        // 总时长 4 小时，总能耗 1.0*1 + 3.0*3 = 10.0 Wh，全局真实加权功率 pGlobal = 10.0 / 4 = 2.5 W
        val intervals = listOf(
            DischargeInterval(
                startTimeMs = now - 4L * 3600L * 1000L,
                endTimeMs = now - 3L * 3600L * 1000L,
                durationMs = 1L * 3600L * 1000L,
                energyWh = 1.0,
                powerWatts = 1.0,
                isScreenOn = true
            ),
            DischargeInterval(
                startTimeMs = now - 3L * 3600L * 1000L,
                endTimeMs = now,
                durationMs = 3L * 3600L * 1000L,
                energyWh = 9.0,
                powerWatts = 3.0,
                isScreenOn = true
            )
        )

        val result = estimator.estimateFromIntervals(intervals, fullEnergy)

        assertNotNull("满电理论续航结果不为 null", result.fullRuntimeMs)
        assertFalse("由于典型息屏工况缺失，不得启用有偏的工况加权模型", result.usedRegimeModel)
        assertEquals("综合预测功率必须忠实降级退回全局物理加权均值 pGlobal (2.5 W)", 2.5, result.predictedPowerWatts!!, 0.01)
        val expectedRuntimeMs = (20.0 / 2.5 * 3600_000.0).toLong()
        assertEquals("综合满电续航应严格由 pGlobal 导出，杜绝纯亮屏高功耗导致的缩水", expectedRuntimeMs, result.fullRuntimeMs!!)
        assertTrue("质量说明中必须明确注明降级原因", result.qualityReasons.any { it.contains("降级") || it.contains("息屏") })
    }

    /**
     * 测试 15：验证宏观历史记录解析时起止时间戳跨度裁剪比例守恒且比率严格小于等于 1.0。
     * 当历史记录包含亮屏、息屏唤醒与深睡区间，且部分跨越窗口边界时，
     * 所有子区间应按物理有效时间戳截断，且比例 ratio = validSpanMs / spanMs 严格 <= 1.0，
     * 绝不发生能量膨胀或时间倒流。
     */
    @Test
    fun testMacroRecordBoundaryClipping_ValidSpanRatioConservation() {
        val now = System.currentTimeMillis()
        val targetWindowMs = 24L * 3600L * 1000L // 24小时目标窗口

        // 构造一个结束于 now - 12h，持续 20h 的宏观记录（从 now - 32h 到 now - 12h）
        // 目标窗口边界为 now - 24h，因此该记录在窗口内的有效时间为从 now - 24h 到 now - 12h，共计 12h
        // 总跨度 20h，落在窗口内 12h，截断比例严格为 12 / 20 = 0.6
        val record = PowerUsageRecord(
            id = now - 12L * 3600L * 1000L,
            recordTime = "2026-10-18 10:00:00",
            levelPercent = 80,
            voltageVolts = 3.85f,
            temperature = 25f,
            energyWh = 20f,
            isCharging = false,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.5f,
            screenOnDurationText = "10h",
            screenOffDurationText = "10h",
            totalDurationText = "20h",
            remainingScreenOnText = "",
            remainingCompositeText = "",
            remainingScreenOffText = "",
            isShizukuRealData = false,
            appCount = 0,
            trendPointsJson = "",
            appListJson = "",
            screenOnEnergyWh = 15f,
            screenOffEnergyWh = 5f,
            totalEnergyWh = 20f
        )

        val intervals = sampleNormalizer.extractDischargeIntervals(
            currentOverview = null,
            allRecords = listOf(record),
            maxTargetDurationMs = targetWindowMs,
            baseTimestamp = now
        )

        val totalDurMs = intervals.sumOf { it.durationMs }
        val totalEnergyWh = intervals.sumOf { it.energyWh }
        val expectedDurMs = 12L * 3600L * 1000L

        assertEquals("跨越边界的宏观记录截断后时长严格等于落入窗口的有效时长 12h", expectedDurMs, totalDurMs)
        assertTrue("截断后每个区间的持续时间必须严格小于等于其原始分配时长", intervals.all { it.durationMs <= 10L * 3600L * 1000L })
        assertTrue("截断后能量守恒且严格小于原始总能量", totalEnergyWh < 20.0)
    }
}
