package com.battery.analysis

import com.battery.analysis.util.SevenDaysDischargeCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 累积 7 天物理使用时间放电计算器专项单元测试类。
 * 覆盖验证：
 * 1. [SevenDaysDischargeCalculator.MetricAccumulator] 累加器的物理等比截断与能量守恒；
 * 2. 样本数据不足 7 天（168 小时）时的真实值保留与零假数据验证；
 * 3. 放电百分比速率（%/h）与充满电可用理论时长的物理公式换算；
 * 4. 紧凑时长字符串解析及边界异常情况防护。
 */
class SevenDaysDischargeCalculatorTest {

    /**
     * 7 天物理毫秒时长常量（168 小时）。
     */
    private val sevenDaysMs = 7L * 24L * 3600L * 1000L

    /**
     * 测试累加器在跨越 168 小时边界时的精确等比裁剪，验证时长精确满 168 小时且平均功率与能量严格守恒。
     */
    @Test
    fun testMetricAccumulatorExactBoundaryClipping() {
        val accumulator = SevenDaysDischargeCalculator.MetricAccumulator(sevenDaysMs)

        // 第一段：100 小时，能耗 200 Wh（平均功率 2.0 W）
        val firstDurMs = 100L * 3600L * 1000L
        val firstEnergyWh = 200.0
        accumulator.add(firstDurMs, firstEnergyWh)

        assertEquals("第一段时长累加正确", firstDurMs, accumulator.accumulatedDurationMs)
        assertEquals("第一段能耗累加正确", firstEnergyWh, accumulator.accumulatedEnergyWh, 0.0001)
        assertFalse("100小时尚未达到168小时上限", accumulator.isFull())

        // 第二段：100 小时，能耗 300 Wh（平均功率 3.0 W）
        // 剩余需要时长：168 - 100 = 68 小时
        // 截断比例：68 / 100 = 0.68
        // 截断纳入能耗：300 * 0.68 = 204.0 Wh
        val secondDurMs = 100L * 3600L * 1000L
        val secondEnergyWh = 300.0
        accumulator.add(secondDurMs, secondEnergyWh)

        assertEquals("总时长精确达到 7 天 168 小时", sevenDaysMs, accumulator.accumulatedDurationMs)
        val expectedTotalEnergy = 200.0 + (300.0 * (68.0 / 100.0))
        assertEquals("总能耗精确按比例守恒截断", expectedTotalEnergy, accumulator.accumulatedEnergyWh, 0.0001)
        assertTrue("累加器已满", accumulator.isFull())

        // 再次追加无效数据，验证不会超出上限
        accumulator.add(10L * 3600L * 1000L, 50.0)
        assertEquals("已满后不再追加任何时长", sevenDaysMs, accumulator.accumulatedDurationMs)
        assertEquals("已满后不再追加任何能耗", expectedTotalEnergy, accumulator.accumulatedEnergyWh, 0.0001)
    }

    /**
     * 测试数据样本不足 7 天时的累加器表现，验证忠实保留真实历史累积值，杜绝虚构数据与过度保底。
     */
    @Test
    fun testMetricAccumulatorWhenDataLessThanSevenDays() {
        val accumulator = SevenDaysDischargeCalculator.MetricAccumulator(sevenDaysMs)

        // 设备仅有 2 天 5 小时数据（53 小时）
        val actualDurMs = 53L * 3600L * 1000L
        val actualEnergyWh = 85.5
        accumulator.add(actualDurMs, actualEnergyWh)

        assertEquals("如实保留 53 小时真实时长", actualDurMs, accumulator.accumulatedDurationMs)
        assertEquals("如实保留 85.5 Wh 真实能耗", actualEnergyWh, accumulator.accumulatedEnergyWh, 0.0001)
        assertFalse("不足 7 天时不应标记为满", accumulator.isFull())
    }

    /**
     * 测试每小时放电百分比速率（%/h）的物理公式换算精度。
     */
    @Test
    fun testComputeRateCalculation() {
        val capacityWh = 19.25f // 标称总容量 19.25 Wh (≈ 5000mAh @ 3.85V)

        // 消耗 1.925 Wh 在 1 小时内，平均功率 1.925 W，速率应为 10.0%/h
        val oneHourMs = 3600L * 1000L
        val rate = SevenDaysDischargeCalculator.computeRate(1.925, oneHourMs, capacityWh)
        assertNotNull("速率不为 null", rate)
        assertEquals("放电速率精确为 10.0%/h", 10.0f, rate!!, 0.001f)

        // 无效输入防护测试：时长为 0 或容量 <= 0
        assertNull("时长为0时返回null", SevenDaysDischargeCalculator.computeRate(1.0, 0L, capacityWh))
        assertNull("能耗为0时返回null", SevenDaysDischargeCalculator.computeRate(0.0, oneHourMs, capacityWh))
        assertNull("容量为0时返回null", SevenDaysDischargeCalculator.computeRate(1.0, oneHourMs, 0f))
    }

    /**
     * 测试基于放电速率推算充满电理论可用时长的换算精度。
     */
    @Test
    fun testComputeFullDurationMs() {
        // 放电速率为 10.0%/h，充满电应精确为 10 小时
        val rate = 10.0f
        val fullMs = SevenDaysDischargeCalculator.computeFullDurationMs(rate)
        assertNotNull("满电时长不为 null", fullMs)
        assertEquals("满电时长精确为 10 小时", 10L * 3600L * 1000L, fullMs!!)

        // 速率无效或为 0 时防护
        assertNull("速率为 null 时返回 null", SevenDaysDischargeCalculator.computeFullDurationMs(null))
        assertNull("速率为 0 时返回 null", SevenDaysDischargeCalculator.computeFullDurationMs(0f))
        assertNull("速率为负数时返回 null", SevenDaysDischargeCalculator.computeFullDurationMs(-1.5f))
    }

    /**
     * 测试紧凑时长字符串解析还原为毫秒数的准确性。
     */
    @Test
    fun testParseDurationTextToMs() {
        assertEquals("解析空文本返回0", 0L, SevenDaysDischargeCalculator.parseDurationTextToMs(""))
        assertEquals("解析横线返回0", 0L, SevenDaysDischargeCalculator.parseDurationTextToMs("--"))

        val expectedMs = (2L * 86400L + 5L * 3600L + 30L * 60L + 15L) * 1000L
        val actualMs = SevenDaysDischargeCalculator.parseDurationTextToMs("2d5h30m15s")
        assertEquals("解析复合天小时分秒文本正确", expectedMs, actualMs)

        val compactMs = (1L * 3600L + 20L * 60L) * 1000L
        assertEquals("解析常用小时分文本正确", compactMs, SevenDaysDischargeCalculator.parseDurationTextToMs("1h20m"))
    }

    /**
     * 测试模式2场景占空比融合模型的加权计算与续航推算准确性。
     */
    @Test
    fun testMode2SceneCompositeCalculation() {
        val screenOnRate = 8.0f // 亮屏 8.0%/h
        val screenOffRate = 2.0f // 息屏 2.0%/h

        // 假设统计窗口内亮屏 20 小时，息屏 80 小时（总计 100 小时）
        val onDurMs = 20L * 3600L * 1000L
        val offDurMs = 80L * 3600L * 1000L
        val totalDurMs = onDurMs + offDurMs

        val ratioOn = (onDurMs.toDouble() / totalDurMs.toDouble()).toFloat()
        assertEquals("亮屏占空比为 20.0%", 0.20f, ratioOn, 0.001f)

        // 模式2加权速率：0.2 * 8.0 + 0.8 * 2.0 = 1.6 + 1.6 = 3.2%/h
        val compositeRate = (ratioOn * screenOnRate) + ((1.0f - ratioOn) * screenOffRate)
        assertEquals("模式2融合放电速率为 3.2%/h", 3.2f, compositeRate, 0.001f)

        // 充满电续航：100 / 3.2 = 31.25 小时 (31h15m)
        val fullMs = SevenDaysDischargeCalculator.computeFullDurationMs(compositeRate)
        assertNotNull("模式2满电续航不为 null", fullMs)
        val expectedMs = (31.25 * 3600.0 * 1000.0).toLong()
        assertTrue("模式2满电续航时长准确", kotlin.math.abs(expectedMs - fullMs!!) < 1000L)
    }
}
