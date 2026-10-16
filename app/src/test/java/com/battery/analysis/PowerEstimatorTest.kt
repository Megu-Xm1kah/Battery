package com.battery.analysis

import com.battery.analysis.domain.estimator.PowerEstimator
import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应多时间尺度放电功耗估计器（PowerEstimator）单元测试套件。
 * 验证三时间尺度（Fast / Medium / Long）独立更新、动态自适应权重归一化、
 * 充电状态样本隔离拦截以及置信度评估。
 */
class PowerEstimatorTest {

    private val estimator = PowerEstimator()

    /**
     * 测试充电状态样本被严格拦截，绝不污染放电估计模型。
     */
    @Test
    fun testChargingSamplesAreStrictlyIgnored() {
        estimator.reset()
        val chargingSample = PowerSample(
            timestampMs = System.currentTimeMillis(),
            voltageVolts = 4.2f,
            currentAmperes = -1.5f,
            powerWatts = 15.0f, // 充电 15W
            batteryLevelPercent = 50.0f,
            state = ScreenPowerState.CHARGING
        )

        estimator.update(chargingSample)
        assertNull("充电样本不生成 Fast 功率", estimator.getFastPower())
        assertNull("充电样本不生成 Medium 功率", estimator.getMediumPower())
        assertEquals(0, estimator.sampleCount)
    }

    /**
     * 测试多时间尺度在持续样本输入下的渐进收敛与权重演进。
     */
    @Test
    fun testMultiTimescaleConvergenceAndAdaptiveWeights() {
        estimator.reset(initialLongPowerWatts = 3.0) // 7天大样本基准 3.0W
        assertEquals(3.0, estimator.getLongPower()!!, 0.001)

        val baseTs = 1_000_000L
        // 模拟稳定亮屏放电 4.0W 持续 10 个采样周期（间隔 1 秒）
        for (i in 0 until 10) {
            val sample = PowerSample(
                timestampMs = baseTs + i * 1000L,
                voltageVolts = 3.85f,
                currentAmperes = 1.04f,
                powerWatts = 4.0f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_ON
            )
            estimator.update(sample)
        }

        assertNotNull(estimator.getFastPower())
        assertNotNull(estimator.getMediumPower())
        // 刚开始短时间（< 10min），长期基准占比最高（约 70%）
        val (wf, wm, wl) = estimator.calculateAdaptiveWeights()
        assertEquals(1.0, wf + wm + wl, 0.001)
        assertTrue("数据不足10分钟时长期基线占主导", wl > 0.50)

        // 验证冷启动宏观注入（如经过 60 分钟有效稳定运行）
        estimator.seedCurrentSession(durationMs = 3_600_000L, powerWatts = 4.0f)
        val (wf60, wm60, wl60) = estimator.calculateAdaptiveWeights()
        assertEquals(1.0, wf60 + wm60 + wl60, 0.001)
        assertTrue("稳定运行1小时后会话稳态模型(Medium)占最大权重", wm60 >= 0.40)
    }

    /**
     * 测试无历史大样本数据时的优雅降级（Long 为 null 时权重在 Fast 与 Medium 之间自适应归一化）。
     */
    @Test
    fun testNoHistoricalData_NormalizesBetweenFastAndMedium() {
        estimator.reset(initialLongPowerWatts = null)
        estimator.seedCurrentSession(durationMs = 600_000L, powerWatts = 3.5f)

        val (wf, wm, wl) = estimator.calculateAdaptiveWeights()
        assertEquals(0.0, wl, 0.001)
        assertEquals(1.0, wf + wm, 0.001)
        val predicted = estimator.getPredictedPower()
        assertNotNull(predicted)
        assertEquals(3.5, predicted!!, 0.01)
    }
}
