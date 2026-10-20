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

    /**
     * 测试当仅具备 Medium（当前会话稳态）与 Long（历史大样本），但无 Fast 采样时的自适应权重严格归一化。
     * 验证权重总和恒为 1.0，且预测放电功率真实反映两者的融合做功，绝不归零或丢失比重。
     */
    @Test
    fun testAdaptiveWeightsWithMediumAndLongWithoutFast() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(2.0) // 7天大样本基线 2.0W
        testEstimator.setMediumTermPower(durationMs = 1_800_000L, powerWatts = 3.0f) // 注入稳态 3.0W，持续 30 分钟（无 Fast）

        val (wf, wm, wl) = testEstimator.calculateAdaptiveWeights()
        assertEquals("Fast 权重必须为 0.0", 0.0, wf, 0.0001)
        assertEquals("Medium 与 Long 权重和必须严格归一化为 1.0", 1.0, wm + wl, 0.0001)
        assertTrue("Medium 权重必须大于 0", wm > 0.0)
        assertTrue("Long 权重必须大于 0", wl > 0.0)

        val predicted = testEstimator.getPredictedPower()
        assertNotNull("预测功率不可为空", predicted)
        assertTrue("预测功率必须在 2.0W 至 3.0W 之间", predicted!! in 2.0..3.0)
    }

    /**
     * 测试在仅具备 Medium 与 Long 且无 Fast 时触发工况突变，绝不会将突变权重分配给不存在的 Fast。
     * 验证突变下的权重总和仍为 1.0，Medium 敏捷提升，预测功率不会缩水归零。
     */
    @Test
    fun testRegimeChangeWithoutFast_DoesNotLoseWeightToNonExistentFast() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(2.0)
        testEstimator.setMediumTermPower(durationMs = 1_800_000L, powerWatts = 4.0f)

        // 模拟外部检测器触发工况突变
        testEstimator.notifyRegimeChange(true)

        val (wf, wm, wl) = testEstimator.calculateAdaptiveWeights()
        assertEquals("无 Fast 采样时 Fast 权重即使突变也必须为 0.0", 0.0, wf, 0.0001)
        assertEquals("权重和必须严格归一化为 1.0", 1.0, wm + wl, 0.0001)
        assertTrue("突变时 Medium 权重必须显著放大提升响应速度", wm > 0.50)

        val predicted = testEstimator.getPredictedPower()
        assertNotNull("预测功率不可为空", predicted)
        assertTrue("预测功率必须真实反映高负载（接近 4.0W）", predicted!! > 3.0)
    }

    /**
     * 测试批量注入当前会话采样点（feedSessionTrendSamples）时：
     * 1. 成功激活 Fast 尺度与工况感知；
     * 2. 会话时长 totalSessionDurationMs 保持宏观物理守恒，杜绝双倍累加虚高膨胀；
     * 3. 充电点与无效功率点被安全过滤。
     */
    @Test
    fun testFeedSessionTrendSamplesPreservesDurationAndActivatesFast() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(2.0)
        // 预设宏观时长 30 分钟 (1,800,000 ms)，宏观均值 2.5W
        testEstimator.seedCurrentSession(durationMs = 1_800_000L, powerWatts = 2.5f)

        val baseTs = 10_000_000L
        // 构造最近 5 分钟的高负载游戏采样点（例如 5.0W），每 10 秒一个点，共 6 个点（跨度 50 秒）
        val samples = (0 until 6).map { i ->
            PowerSample(
                timestampMs = baseTs + i * 10_000L,
                voltageVolts = 3.85f,
                powerWatts = 5.0f,
                batteryLevelPercent = 70.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        }

        testEstimator.feedSessionTrendSamples(samples)

        // 验证 Fast 尺度已成功感应到近期高负载并偏向 5.0W
        val fastP = testEstimator.getFastPower()
        assertNotNull("Fast 功率必须已激活生成", fastP)
        assertTrue("Fast 功率应灵敏响应近期 5.0W 高负载 (当前: $fastP)", fastP!! > 2.5)

        // 验证时长守恒：预设了 30 分钟，总时长不会被虚高累加成 30分钟 + 50秒，而是取最大值保持 30 分钟
        assertEquals("会话总时长必须保持物理守恒（30分钟）", 1_800_000L, testEstimator.totalSessionDurationMs)
    }

    /**
     * 测试短时触控或界面刷新尖峰（例如持续 2~3 秒的 4.5W 高功耗）不会误触发工况突变，
     * 并且预测功率保持稳定平滑，杜绝每次刷新跳动几十到上百分钟。
     */
    @Test
    fun testShortTouchSpikeDoesNotTriggerRegimeChange() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(2.0)
        // 预设会话 30 分钟，均值 2.0W
        testEstimator.seedCurrentSession(durationMs = 1_800_000L, powerWatts = 2.0f)

        val baseTs = 20_000_000L
        // 模拟用户下拉刷新或手指滑动屏幕产生的 3 秒触控峰值（3 个 1 秒间隔的点，4.5W）
        val touchSpikeSamples = (0 until 3).map { i ->
            PowerSample(
                timestampMs = baseTs + i * 1_000L,
                voltageVolts = 3.85f,
                powerWatts = 4.5f,
                batteryLevelPercent = 80.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        }

        testEstimator.feedSessionTrendSamples(touchSpikeSamples)

        val predicted = testEstimator.getPredictedPower()
        assertNotNull("预测功率不可为空", predicted)
        // 验证：3 秒的触控脉冲不会误判为工况跃迁，预测功率应平稳受 2.0W 锚定，绝不能被拉升至 3.5W 以上
        assertTrue("短时触控脉冲预测功率应平稳阻尼 (当前: $predicted)", predicted!! < 2.3)
    }

    /**
     * 测试新安装（缺失 Long 尺度大样本基准）的冷启动阶段，
     * 即使遭遇应用初始化等高负载，稳态阻尼机制能够压制极端跳变，保持预测时间合理稳定。
     */
    @Test
    fun testColdStartWithoutLongTermMaintainsStability() {
        val testEstimator = PowerEstimator()
        // 不设置 LongTermPower (模拟新安装)
        // 预设会话刚开始 3 分钟，会话平均 2.2W
        testEstimator.seedCurrentSession(durationMs = 180_000L, powerWatts = 2.2f)

        val (_, wm, wl) = testEstimator.calculateAdaptiveWeights()
        assertEquals("无长期大样本时 Long 权重严格为 0.0", 0.0, wl, 0.0001)
        assertTrue("冷启动期 Medium 会话稳态权重应占据主导 (> 0.60)", wm > 0.60)

        val predicted = testEstimator.getPredictedPower()
        assertNotNull("预测功率不可为空", predicted)
        assertTrue("冷启动预测功率应贴近会话稳态 (当前: $predicted)", predicted!! in 2.0..2.5)
    }

    /**
     * 测试在拔电放电极早期（前 5 分钟，如 90 秒），若因刷新操作或短时操作触发工况突变，
     * 自适应时间阻尼机制能够有效约束突变权重扩散，确保 7 天长期大样本（Long）牢牢守住压舱权重（>= 50%），
     * 预测放电功率保持稳定，彻底杜绝剩余续航发生断崖式蹦床跳变。
     */
    @Test
    fun testEarlyDischargeSpikeDamping_PreventsRuntimeJump() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(2.0) // 7天长期大样本典型功耗 2.0W
        // 模拟刚刚拔电放电 90 秒，会话平均 2.0W
        testEstimator.seedCurrentSession(durationMs = 90_000L, powerWatts = 2.0f)

        // 模拟由于下拉刷新或瞬态脉冲激活了工况突变跃迁
        testEstimator.notifyRegimeChange(true)

        val (wf, wm, wl) = testEstimator.calculateAdaptiveWeights()
        assertEquals("权重和必须严格为 1.0", 1.0, wf + wm + wl, 0.0001)
        assertTrue("拔电极早期（90秒）7天长期习惯基准必须保留压舱地位（wl >= 0.50，当前: $wl）", wl >= 0.50)
        assertTrue("拔电极早期瞬时 Fast 权重应受到平滑时间阻尼压制（wf <= 0.35，当前: $wf）", wf <= 0.35)

        val predicted = testEstimator.getPredictedPower()
        assertNotNull("预测放电功率不可为空", predicted)
        // 即使 Fast 瞬时功率偏高（例如 ~5.0W），预测功率也应当受到 2.0W 锚定，保持在 3.0W 以内
        assertTrue("拔电极早期预测功耗应保持高度稳定阻尼 (当前: $predicted)", predicted!! < 3.0)
    }

    /**
     * 测试当放电会话超过 5 分钟（如 10 分钟）进入成熟期后，若设备真实进入持续高负载工况（如 3D 游戏），
     * 时间阻尼自然解除，突变机制全额恢复敏捷响应能力，Fast 尺度能快速响应新工况。
     */
    @Test
    fun testMatureDischargeRegimeChange_MaintainsHighResponsiveness() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(2.0)
        // 模拟放电已持续 10 分钟 (600,000 ms)，会话平均 2.2W
        testEstimator.seedCurrentSession(durationMs = 600_000L, powerWatts = 2.2f)

        // 持续进入真实高负载工况突变
        testEstimator.notifyRegimeChange(true)

        val (wf, wm, wl) = testEstimator.calculateAdaptiveWeights()
        assertEquals("权重和必须严格为 1.0", 1.0, wf + wm + wl, 0.0001)
        assertTrue("成熟期工况突变应恢复敏捷响应（wf >= 0.40，当前: $wf）", wf >= 0.40)
    }

    /**
     * 测试已稳定放电长达 35 分钟（2,100,000 ms，平均功耗 0.86W）的长会话用机场景：
     * 验证 Medium 尺度作为凝聚了数千焦耳真实物理做功的稳态基石，在遭遇突发高负载或脉冲跃迁时，
     * 依然保留坚实的稳态压舱权重（wm >= 0.20，wf <= 0.60），
     * 预测放电功率保持在稳定合理的区间（< 1.8W），绝不会被单点冲高至 3.5W 导致续航从 14h 暴跌至 4.8h。
     */
    /**
     * 测试已稳定放电长达 35 分钟（2,100,000 ms，平均功耗 0.86W）的长会话用机场景：
     * 验证 Medium 尺度作为凝聚了数千焦耳真实物理做功的稳态基石，在遭遇突发高负载或脉冲跃迁时，
     * 依然保留坚实的稳态压舱权重（wm >= 0.40，wf <= 0.35），
     * 预测放电功率保持在稳定合理的区间（< 1.4W），绝不会被单点冲高至 3.5W 导致续航从 14h 暴跌至 4.8h。
     */
    @Test
    fun testLongSessionMediumBallast_PreventsRuntimeCollapseOnPulse() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(1.94) // 7天大样本典型功耗 1.94W
        // 预设用户当前放电已持续 35 分钟 (2,100,000 ms)，当前会话实测平均 0.86W
        testEstimator.seedCurrentSession(durationMs = 2_100_000L, powerWatts = 0.86f)

        // 模拟短时高功耗脉冲激活突变状态
        testEstimator.notifyRegimeChange(true)

        val (wf, wm, wl) = testEstimator.calculateAdaptiveWeights()
        assertEquals("权重和必须严格归一化为 1.0", 1.0, wf + wm + wl, 0.0001)
        assertTrue("35分钟长会话的 Medium 稳态权重必须保留压舱地位（wm >= 0.40，当前: $wm）", wm >= 0.40)
        assertTrue("Fast 突变权重在长会话稳态支撑下不得极端失控（wf <= 0.35，当前: $wf）", wf <= 0.35)

        // 验证即使 Fast 测得 3.5W，预测功率也受到 0.86W 稳态有力锚定，保持在 1.4W 以内
        val predicted = testEstimator.getPredictedPower()
        assertNotNull("预测放电功率不可为空", predicted)
        assertTrue("预测功率应平稳保持在 1.4W 以内，杜绝暴增至 3.5W (当前: $predicted)", predicted!! < 1.40)
    }

    /**
     * 测试时序回放场景下 App 冷启动脉冲（17.9W）的真实物理瞬态滤波：
     * 仿真设备持续放电 35 分钟（稳态 0.86W），在最末端出现 15.8W 与 17.9W 的真实 App 冷启动瞬态高功耗。
     * 验证：
     * 1. 由于未满足持续稳定做功 >= 60 秒门限，工况突变检测器不误判为 Regime Change；
     * 2. 点集抽稀产生的时间间隔不放大单点冷启动脉冲的物理能量做功；
     * 3. 最终预测放电功率保持在稳定健康的 1.1W~1.3W 区间，彻底根治续航时间从 8h 暴跌至 4h48m 的跳变问题。
     */
    @Test
    fun testColdStartPulseAtEndOfLongSession_DoesNotDistortPredictedPower() {
        val testEstimator = PowerEstimator()
        testEstimator.setLongTermPower(1.94) // 7天历史基准 1.94W
        testEstimator.seedCurrentSession(durationMs = 2_130_000L, powerWatts = 0.86f)

        val samples = mutableListOf<PowerSample>()
        val baseTs = 1_700_000_000_000L

        // 前期 30 个稳定采样点（模拟稳态 0.86W）
        for (i in 0 until 30) {
            samples.add(
                PowerSample(
                    timestampMs = baseTs + i * 30_000L, // 间隔 30 秒（模拟图表抽稀采样）
                    voltageVolts = 3.98f,
                    currentAmperes = 0.21f,
                    powerWatts = 0.86f,
                    batteryLevelPercent = 60.0f - (i * 0.1f),
                    state = ScreenPowerState.SCREEN_ON
                )
            )
        }

        // 最末端注入 App 冷启动真实瞬态高功耗（15.8W 与 17.9W 真实瞬态）
        val lastTs = baseTs + 30 * 30_000L
        samples.add(
            PowerSample(
                timestampMs = lastTs + 15_000L,
                voltageVolts = 3.89f,
                currentAmperes = 4.06f,
                powerWatts = 15.8f,
                batteryLevelPercent = 57.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )
        samples.add(
            PowerSample(
                timestampMs = lastTs + 30_000L,
                voltageVolts = 3.85f,
                currentAmperes = 4.65f,
                powerWatts = 17.9f, // 真实冷启动爆发峰值
                batteryLevelPercent = 57.0f,
                state = ScreenPowerState.SCREEN_ON
            )
        )

        testEstimator.feedSessionTrendSamples(samples)

        val predictedPower = testEstimator.getPredictedPower()
        assertNotNull("预测功率不可为空", predictedPower)
        assertTrue(
            "预测功率应稳定在 1.30W 以内，当前预测功率为: $predictedPower",
            predictedPower!! <= 1.30
        )

        // 验证折算出的亮屏剩余续航（假设可用能量 16.89Wh）
        val usableEnergyWh = 16.89
        val remainingHours = usableEnergyWh / predictedPower
        assertTrue(
            "亮屏剩余续航应平稳保持在 12h 以上（当前: ${remainingHours}h），绝不可腰斩至 4h48m",
            remainingHours >= 12.0
        )
    }
}



