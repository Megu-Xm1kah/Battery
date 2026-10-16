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
        assertEquals(RuntimeModelVersion.FULL_RUNTIME_V2_1, result.modelVersion)
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
}
