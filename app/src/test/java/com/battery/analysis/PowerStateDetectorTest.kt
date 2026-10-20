package com.battery.analysis

import com.battery.analysis.domain.estimator.PowerStateDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功耗状态跃迁与工况突变检测器（PowerStateDetector）单元测试套件。
 * 验证基线维护、单次扰动滤波、连续高偏离工况跃迁确认与平稳状态恢复机制。
 */
class PowerStateDetectorTest {

    private val detector = PowerStateDetector(
        changeThresholdRatio = 0.50,
        requiredConsecutiveCycles = 2,
        minRegimeDurationSec = 0.0
    )

    /**
     * 测试单次偶发高功耗波动不触发工况迁移，只有连续多个周期高偏离才触发 Regime Change。
     */
    @Test
    fun testRegimeChangeRequiresConsecutiveCycles() {
        // 初始基线 2.5W
        detector.reset(2.5)
        assertEquals(2.5, detector.baselinePowerWatts!!, 0.001)

        // 第 1 周期骤升至 7.0W（进入大型 3D 游戏）：单次高负荷，判定为疑似，不立即进入跃迁
        val cycle1 = detector.update(7.0f)
        assertFalse("单周期不触发工况跃迁", cycle1)
        assertEquals(1, detector.consecutiveDeviationCount)
        assertEquals(1.0, detector.getResponsivenessMultiplier(), 0.01)

        // 第 2 周期持续 7.0W：满足连续 2 周期阈值，正式确认为新工况跃迁
        val cycle2 = detector.update(7.0f)
        assertTrue("连续 2 周期确认工况跃迁", cycle2)
        assertTrue("处于跃迁激活状态", detector.isRegimeChanging)
        assertTrue("响应系数提升至 1.5 以上", detector.getResponsivenessMultiplier() >= 1.5)

        // 持续高负荷更新基线已向 7.0W 靠拢
        assertTrue("基线向高功耗平滑靠拢", detector.baselinePowerWatts!! > 2.5)

        // 退出游戏回到 2.6W，偏离新基线，经过恢复逐步重置
        detector.update(2.6f)
        detector.update(2.6f)
        // 验证重置机制
        detector.reset(2.5)
        assertFalse(detector.isRegimeChanging)
        assertEquals(0, detector.consecutiveDeviationCount)
    }

    /**
     * 测试工业级工况突变检测器在持续稳定做功 >= 60 秒门限下的严格判定：
     * 1. 即使发生冷启动高达 17.9W 的瞬态峰值，若仅持续数秒（< 60s），绝对不触发工况突变；
     * 2. 点集抽稀产生数十秒间隔时，极端脉冲时间步长受到真实物理约束（<= 2s），不虚构累计持续时长；
     * 3. 只有当真正稳定做功达到 60 秒门限时，才确立进入新工况。
     */
    @Test
    fun testRegimeChangeRequiresSustainedWorkForAtLeast60Seconds() {
        val sixtySecDetector = PowerStateDetector(
            changeThresholdRatio = 0.40,
            requiredConsecutiveCycles = 2,
            minRegimeDurationSec = 60.0 // 工业级持续稳定做功 >= 60 秒门限
        )
        sixtySecDetector.reset(0.86) // 稳态基线 0.86W

        // 1. 模拟 App 冷启动：瞬态 17.9W，持续 2 秒（2 个 1 秒采样点）
        sixtySecDetector.update(15.8f, deltaSec = 1.0)
        assertFalse("冷启动第1秒不触发工况跃迁", sixtySecDetector.isRegimeChanging)
        sixtySecDetector.update(17.9f, deltaSec = 1.0)
        assertFalse("冷启动仅持续2秒，未达60秒持续做功门限，严禁触发工况跃迁", sixtySecDetector.isRegimeChanging)

        // 2. 模拟点集抽稀：间隔 30 秒的点捕获了 17.9W 瞬态，物理步长约束防止虚假膨胀为 60 秒
        sixtySecDetector.reset(0.86)
        sixtySecDetector.update(17.9f, deltaSec = 30.0)
        sixtySecDetector.update(17.9f, deltaSec = 30.0)
        assertFalse("抽稀点间隔不应将瞬态脉冲虚假放大为持续60秒做功", sixtySecDetector.isRegimeChanging)

        // 3. 模拟真实进入持续高负载（如 3D 游戏 6.0W 持续做功 >= 60 秒）
        sixtySecDetector.reset(0.86)
        for (sec in 1..59) {
            sixtySecDetector.update(6.0f, deltaSec = 1.0)
            assertFalse("持续做功第 ${sec} 秒（未满60秒）不触发跃迁", sixtySecDetector.isRegimeChanging)
        }
        // 第 60 秒：持续稳定做功达到 60 秒门限
        val confirmed = sixtySecDetector.update(6.0f, deltaSec = 1.0)
        assertTrue("持续稳定做功达到 60 秒，正式确立工况跃迁", confirmed)
        assertTrue(sixtySecDetector.isRegimeChanging)
    }

    /**
     * 测试无效与 0 功率采样点安全防御，不引起除零崩溃。
     */
    @Test
    fun testInvalidPowerSafety() {
        detector.reset(null)
        val res = detector.update(0f)
        assertFalse(res)
        assertEquals(null, detector.baselinePowerWatts)
    }
}
