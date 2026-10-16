package com.battery.analysis

import com.battery.analysis.domain.estimator.PowerAnomalyDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳健统计异常功耗检测器（PowerAnomalyDetector）单元测试套件。
 * 验证中位数、绝对中位差（MAD）、标准化残差 Z-Score 计算，
 * 以及瞬态孤立尖峰功耗检测与置信度自适应衰减降权效果。
 */
class PowerAnomalyDetectorTest {

    private val detector = PowerAnomalyDetector(zScoreThreshold = 3.0, minMadFloorWatts = 0.05)

    /**
     * 测试奇数与偶数长度采样序列的中位数计算精度。
     */
    @Test
    fun testCalculateMedian_OddAndEvenList() {
        val oddList = listOf(1.2f, 3.5f, 2.0f)
        assertEquals(2.0f, detector.calculateMedian(oddList)!!, 0.001f)

        val evenList = listOf(1.0f, 2.0f, 3.0f, 4.0f)
        assertEquals(2.5f, detector.calculateMedian(evenList)!!, 0.001f)

        assertTrue(detector.calculateMedian(emptyList()) == null)
    }

    /**
     * 测试绝对中位差 MAD 计算。
     */
    @Test
    fun testCalculateMad_NormalDistribution() {
        // [1.2, 1.2, 1.3, 1.3, 1.4], median = 1.3
        // deviations = [0.1, 0.1, 0.0, 0.0, 0.1], sorted = [0.0, 0.0, 0.1, 0.1, 0.1], median deviation = 0.1
        val list = listOf(1.2f, 1.2f, 1.3f, 1.3f, 1.4f)
        val mad = detector.calculateMad(list)
        org.junit.Assert.assertNotNull(mad)
        assertEquals(0.1, mad!!, 0.01)
    }

    /**
     * 测试典型孤立高负载瞬态尖峰（如 1.2, 1.3, 1.2, 8.8, 1.3, 1.2）的异常识别与置信度降权。
     */
    @Test
    fun testOutlierSpike_IsDetectedAndDownweighted() {
        val normalWindow = listOf(1.2f, 1.3f, 1.2f, 1.3f, 1.2f)

        // 1. 正常波动样本（如 1.25W）不被判定为异常，获得 100% 全权重
        assertFalse(detector.isAnomaly(1.25f, normalWindow))
        assertEquals(1.0, detector.getSampleWeight(1.25f, normalWindow), 0.01)

        // 2. 突发孤立尖峰（8.8W，偏离近 7 倍）必须被精准识别为统计离群异常
        assertTrue(detector.isAnomaly(8.8f, normalWindow))
        val spikeWeight = detector.getSampleWeight(8.8f, normalWindow)
        assertTrue("尖峰权重必须被自适应削减至 0.20 以下", spikeWeight <= 0.20)

        // 3. 极小样本防误判保护（样本少于 3 个时 Z-Score 为 0，不降权）
        val tinyWindow = listOf(1.2f, 1.3f)
        assertFalse(detector.isAnomaly(8.8f, tinyWindow))
        assertEquals(1.0, detector.getSampleWeight(8.8f, tinyWindow), 0.01)
    }
}
