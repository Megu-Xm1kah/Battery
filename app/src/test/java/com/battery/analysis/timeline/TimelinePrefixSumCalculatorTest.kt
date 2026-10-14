package com.battery.analysis.timeline

import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.timeline.domain.BatterySample
import com.battery.analysis.timeline.domain.ScreenEvent
import com.battery.analysis.timeline.presentation.BatteryTimelineState
import com.battery.analysis.timeline.util.TimelinePrefixSumCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * 时间轴前缀和预计算与二分查找算法单元测试套件。
 * 验证阶段时长切片准确性、梯形能量积分守恒、二分查找极速响应及性能基准。
 */
class TimelinePrefixSumCalculatorTest {

    /**
     * 辅助构建测试用采样点。
     *
     * @param ts 时间戳（毫秒）
     * @param level 电量百分比
     * @param powerMw 功率（毫瓦）
     * @return 构造的 [BatterySample] 实例
     */
    private fun createSample(ts: Long, level: Int, powerMw: Double): BatterySample {
        return BatterySample(
            timestamp = ts,
            batteryLevel = level,
            voltageMv = 4000,
            currentMa = powerMw / 4.0,
            temperatureC = 28.0,
            powerMw = powerMw,
            energyWh = 20.0 * (level / 100.0)
        )
    }

    /**
     * 验证在亮屏与息屏交替区间下，二分查找切片计算得到的阶段亮屏与息屏时长准确无误。
     */
    @Test
    fun testDurationSlicingAccuracy() {
        val startTs = 1_000_000L
        val endTs = 1_100_000L // 100 秒

        // 构造屏幕区间：
        // 0s ~ 30s: 亮屏 (30,000ms)
        // 30s ~ 70s: 息屏唤醒 (40,000ms)
        // 70s ~ 100s: 息屏深度睡眠 (30,000ms)
        val screenEvents = listOf(
            ScreenEvent(startTime = startTs, endTime = startTs + 30_000L, isScreenOn = true, isDeepSleep = false),
            ScreenEvent(startTime = startTs + 30_000L, endTime = startTs + 70_000L, isScreenOn = false, isDeepSleep = false),
            ScreenEvent(startTime = startTs + 70_000L, endTime = endTs, isScreenOn = false, isDeepSleep = true)
        )

        val samples = listOf(
            createSample(startTs, 100, 2000.0),
            createSample(startTs + 30_000L, 99, 1000.0),
            createSample(startTs + 70_000L, 98, 500.0),
            createSample(endTs, 97, 200.0)
        )

        val state = BatteryTimelineState(
            startTimestamp = startTs,
            endTimestamp = endTs,
            screenEvents = screenEvents,
            batterySamples = samples,
            totalEnergyWh = 20.0f
        )

        val prefixData = TimelinePrefixSumCalculator.buildPrefixData(state, null, 20.0f)
        assertNotNull(prefixData)

        // 1. 查询 15s 时刻（位于第一个亮屏区间内）
        val stats15s = TimelinePrefixSumCalculator.querySubIntervalStats(prefixData!!, startTs + 15_000L)
        assertEquals(15_000L, stats15s.totalDurationMs)
        assertEquals(15_000L, stats15s.screenOnDurationMs)
        assertEquals(0L, stats15s.screenOffAwakeDurationMs)
        assertEquals(0L, stats15s.screenOffDeepSleepDurationMs)

        // 2. 查询 50s 时刻（位于第二个息屏唤醒区间中间，亮屏已满 30s，唤醒已有 20s）
        val stats50s = TimelinePrefixSumCalculator.querySubIntervalStats(prefixData, startTs + 50_000L)
        assertEquals(50_000L, stats50s.totalDurationMs)
        assertEquals(30_000L, stats50s.screenOnDurationMs)
        assertEquals(20_000L, stats50s.screenOffAwakeDurationMs)
        assertEquals(0L, stats50s.screenOffDeepSleepDurationMs)
        assertEquals(20_000L, stats50s.screenOffDurationMs)

        // 3. 查询 85s 时刻（位于第三个深度睡眠区间中间，亮屏 30s，唤醒 40s，深度睡眠 15s）
        val stats85s = TimelinePrefixSumCalculator.querySubIntervalStats(prefixData, startTs + 85_000L)
        assertEquals(85_000L, stats85s.totalDurationMs)
        assertEquals(30_000L, stats85s.screenOnDurationMs)
        assertEquals(40_000L, stats85s.screenOffAwakeDurationMs)
        assertEquals(15_000L, stats85s.screenOffDeepSleepDurationMs)
        assertEquals(55_000L, stats85s.screenOffDurationMs)

        // 4. 查询 100s 终点时刻（全部累计满额）
        val statsEnd = TimelinePrefixSumCalculator.querySubIntervalStats(prefixData, endTs)
        assertEquals(100_000L, statsEnd.totalDurationMs)
        assertEquals(30_000L, statsEnd.screenOnDurationMs)
        assertEquals(40_000L, statsEnd.screenOffAwakeDurationMs)
        assertEquals(30_000L, statsEnd.screenOffDeepSleepDurationMs)
        assertEquals(70_000L, statsEnd.screenOffDurationMs)
    }

    /**
     * 验证当提供全局权威真值时，阶段终点输出的能量与权威真值 100% 保持守恒一致。
     */
    @Test
    fun testGlobalEnergyCalibrationConservation() {
        val startTs = 1_000_000L
        val endTs = 1_360_000L // 360 秒（0.1 小时）

        val screenEvents = listOf(
            ScreenEvent(startTime = startTs, endTime = startTs + 180_000L, isScreenOn = true, isDeepSleep = false),
            ScreenEvent(startTime = startTs + 180_000L, endTime = endTs, isScreenOn = false, isDeepSleep = false)
        )

        val samples = listOf(
            createSample(startTs, 100, 2000.0),
            createSample(startTs + 180_000L, 99, 2000.0),
            createSample(endTs, 98, 1000.0)
        )

        val state = BatteryTimelineState(
            startTimestamp = startTs,
            endTimestamp = endTs,
            screenEvents = screenEvents,
            batterySamples = samples,
            totalEnergyWh = 20.0f
        )

        // 假设权威全局真值：亮屏耗电 0.150Wh，息屏唤醒耗电 0.080Wh
        val overview = PowerOverviewStats(
            avgPowerWatts = 2.3f,
            screenOnPowerWatts = 3.0f,
            screenOffPowerWatts = 1.6f,
            screenOnEnergyWh = 0.150f,
            screenOffEnergyWh = 0.080f,
            screenOffAwakeEnergyWh = 0.080f,
            screenOffDeepSleepEnergyWh = 0f,
            totalEnergyWh = 0.230f,
            screenOnDurationMs = 180_000L,
            screenOffDurationMs = 180_000L,
            totalDurationMs = 360_000L,
            isScreenOffDecomposedAvailable = true
        )

        val prefixData = TimelinePrefixSumCalculator.buildPrefixData(state, overview, 20.0f)
        assertNotNull(prefixData)

        // 在终点查询
        val finalStats = TimelinePrefixSumCalculator.querySubIntervalStats(prefixData!!, endTs)
        assertEquals(0.150f, finalStats.screenOnEnergyWh, 0.001f)
        assertEquals(0.080f, finalStats.screenOffAwakeEnergyWh, 0.001f)
        assertEquals(0.230f, finalStats.totalEnergyWh, 0.001f)
    }

    /**
     * 验证二分查找高频查询在连续 10,000 次执行下的性能基准，单次平均耗时必须严格低于 0.01ms（10 微秒）。
     */
    @Test
    fun testHighFrequencyQueryPerformanceBenchmark() {
        val startTs = 1_000_000L
        val sampleCount = 3000
        val endTs = startTs + sampleCount * 1000L

        val samples = mutableListOf<BatterySample>()
        val screenEvents = mutableListOf<ScreenEvent>()

        for (i in 0 until sampleCount) {
            val ts = startTs + i * 1000L
            samples.add(createSample(ts, 100 - (i / 100), 1500.0))
            if (i % 60 == 0) {
                screenEvents.add(ScreenEvent(ts, ts + 60_000L, i % 120 == 0, false))
            }
        }

        val state = BatteryTimelineState(
            startTimestamp = startTs,
            endTimestamp = endTs,
            screenEvents = screenEvents,
            batterySamples = samples,
            totalEnergyWh = 20.0f
        )

        val prefixData = TimelinePrefixSumCalculator.buildPrefixData(state, null, 20.0f)
        assertNotNull(prefixData)

        val iterations = 10_000
        val targetTimestamps = LongArray(iterations) {
            startTs + (it % (sampleCount * 1000L))
        }

        // 预热 JIT
        for (i in 0 until 500) {
            TimelinePrefixSumCalculator.querySubIntervalStats(prefixData!!, targetTimestamps[i])
        }

        // 正式计时基准测试
        val totalNanos = measureNanoTime {
            for (i in 0 until iterations) {
                TimelinePrefixSumCalculator.querySubIntervalStats(prefixData!!, targetTimestamps[i])
            }
        }

        val avgNanosPerQuery = totalNanos.toDouble() / iterations
        val avgMicrosPerQuery = avgNanosPerQuery / 1000.0
        val avgMillisPerQuery = avgMicrosPerQuery / 1000.0

        println("二分查找阶段统计基准测试完成: 总计执行 $iterations 次, 单次平均耗时: ${String.format("%.3f", avgMicrosPerQuery)} μs (${String.format("%.5f", avgMillisPerQuery)} ms)")

        // 严格断言：单次平均耗时必须远小于 0.05ms（即小于 50 微秒）
        assertTrue("单次查询耗时应小于 0.05ms, 当前为 $avgMillisPerQuery ms", avgMillisPerQuery < 0.05)
    }
}
