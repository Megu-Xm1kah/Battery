package com.battery.analysis.domain.estimator

import kotlin.math.abs
import kotlin.math.max

/**
 * 功耗状态跃迁与工况突变检测器（Power Regime Change Detector v2.0）。
 * 监测设备从轻载到高负载（如启动大型 3D 游戏、相机持续录像、重负荷运算）或从高负载返回轻载的工况迁移。
 * 升级为工业级「绝对变化 + 相对变化双门限」判定机制，并集成持续物理时间跨度确认与恢复滞回（Hysteresis），
 * 彻底消除在极低待机功耗下微小扰动或界面触控刷新短时脉冲引发的误报，同时敏捷捕捉高功耗区间的显著真实突变。
 *
 * @param minAbsoluteChangeWatts 判定工况突变的最小绝对功耗阈值（单位：W，默认 0.35W，杜绝低功耗误报）
 * @param changeThresholdRatio 判定工况突变的相对比例阈值（默认 0.40，即 40%）
 * @param requiredConsecutiveCycles 确认工况跃迁所需的最少连续高偏离采样周期数（默认 2 次）
 * @param recoveryHysteresisRatio 退出工况跃迁返回稳态所需的滞回容限比例（默认 0.25，即 25%）
 * @param minRegimeDurationSec 确立工况突变所需的最小物理持续稳定做功时间门限（单位：秒，默认 0.0s 兼容基础单测；工业级核心模型如 PowerEstimator 严格指定 >= 60.0s）
 */
class PowerStateDetector(
    private val minAbsoluteChangeWatts: Double = 0.35,
    private val changeThresholdRatio: Double = 0.40,
    private val requiredConsecutiveCycles: Int = 2,
    private val recoveryHysteresisRatio: Double = 0.25,
    private val minRegimeDurationSec: Double = 0.0
) {

    /** 动态维护的历史稳态基线功耗（单位：W） */
    var baselinePowerWatts: Double? = null
        private set

    /** 当前已连续出现高偏离的周期计数 */
    var consecutiveDeviationCount: Int = 0
        private set

    /** 当前偏离累积的物理持续时长（单位：秒） */
    var accumulatedDeviationDurationSec: Double = 0.0
        private set

    /** 当前连续在恢复容限内的稳态计数（用于滞回平滑退出） */
    private var consecutiveSteadyCount: Int = 0

    /** 当前稳态累积的物理持续时长（单位：秒） */
    private var accumulatedSteadyDurationSec: Double = 0.0

    /** 当前是否正处于工况迁移跃迁状态 */
    var isRegimeChanging: Boolean = false
        private set

    /** 工况突变退出后的平滑阻尼衰减比例（1.0 连续平滑衰减至 0.0，杜绝权重阶跃跳变） */
    var recoveryDecayRatio: Double = 0.0
        private set

    /**
     * 重置状态检测器上下文，清空偏离计数、累积时长、衰减过渡与工况跃迁标志。
     * 通常在屏幕开关切换、充电插拔或启动新的统计会话时调用。
     *
     * @param initialBaselineWatts 可选的初始稳态基线功率（单位：W，若无传入 null）
     */
    fun reset(initialBaselineWatts: Double? = null) {
        baselinePowerWatts = initialBaselineWatts?.takeIf { it.isFinite() && it > 0.0 }
        consecutiveDeviationCount = 0
        accumulatedDeviationDurationSec = 0.0
        consecutiveSteadyCount = 0
        accumulatedSteadyDurationSec = 0.0
        isRegimeChanging = false
        recoveryDecayRatio = 0.0
    }

    /**
     * 输入最新到达的物理功耗采样值，执行双门限与滞回判定，检测是否触发或维持工况跃迁。
     *
     * 判定准则与物理第一性原理：
     * 1. 触发阈值：Threshold_trigger = max(minAbsoluteChangeWatts, changeThresholdRatio * baseline)；
     * 2. 恢复滞回阈值：Threshold_recovery = max(minAbsoluteChangeWatts * 0.6, recoveryHysteresisRatio * baseline)；
     * 3. 持续稳定做功校验：确立工况突变必须要求设备在该工况下持续稳定做功达到 [minRegimeDurationSec]（>= 60 秒）；
     * 4. 极端脉冲物理步长防膨胀：针对 App 冷启动等高达 15W~18W 的瞬态峰值（偏离基线 3.5 倍以上且 > 5W），
     *    由于真实物理冷启动做功仅持续 1~2 秒，即便在点集抽稀导致采样点时间差达到数十秒时，
     *    单次脉冲点计入持续做功时间的物理步长严格限制在真实脉冲持续期内（<= 2.0s），
     *    彻底杜绝抽稀采样点因采样间隔拉大而把单点 1~2 秒的冷启动误判为持续 60 秒做功并触发虚假工况跃迁。
     *
     * @param currentPowerWatts 当前周期测得的物理放电功率（单位：W）
     * @param deltaSec 距上一次采样的物理间隔时间（单位：秒，默认 1.0s）
     * @return 返回当前是否处于工况跃迁激活状态 [Boolean]
     */
    fun update(currentPowerWatts: Float, deltaSec: Double = 1.0): Boolean {
        if (!currentPowerWatts.isFinite() || currentPowerWatts <= 0f) {
            consecutiveDeviationCount = 0
            accumulatedDeviationDurationSec = 0.0
            consecutiveSteadyCount = 0
            accumulatedSteadyDurationSec = 0.0
            isRegimeChanging = false
            return false
        }

        val curP = currentPowerWatts.toDouble()
        val baseP = baselinePowerWatts

        if (baseP == null || baseP <= 0.0) {
            // 首个有效样本直接确立为初始基线
            baselinePowerWatts = curP
            consecutiveDeviationCount = 0
            accumulatedDeviationDurationSec = 0.0
            consecutiveSteadyCount = 0
            accumulatedSteadyDurationSec = 0.0
            isRegimeChanging = false
            return false
        }

        val absDiff = abs(curP - baseP)
        val triggerThreshold = max(minAbsoluteChangeWatts, baseP * changeThresholdRatio)
        val recoveryThreshold = max(minAbsoluteChangeWatts * 0.60, baseP * recoveryHysteresisRatio)
        val safeDelta = deltaSec.coerceIn(0.01, 300.0)

        // 极端脉冲有效持续物理时间步长修正：
        // 若功耗极大偏离基线（超过基线 3.5 倍且 > 5.0W，典型如冷启动 15W~18W），真实物理脉冲上限为 2.0s，
        // 绝不因抽稀点间隔 10s~30s 就将单点脉冲按几十秒持续做功累加
        val effectiveDelta = if (curP > max(5.0, baseP * 3.5)) {
            safeDelta.coerceAtMost(2.0)
        } else {
            safeDelta
        }

        if (!isRegimeChanging) {
            // 当前处于稳态：检测是否连续触发偏离并持续做功达到 60 秒门限
            if (absDiff > triggerThreshold) {
                consecutiveDeviationCount++
                accumulatedDeviationDurationSec += effectiveDelta
                consecutiveSteadyCount = 0
                accumulatedSteadyDurationSec = 0.0
                recoveryDecayRatio = 0.0 // 出现新偏离时立即终止衰减过渡
                if (consecutiveDeviationCount >= requiredConsecutiveCycles &&
                    (minRegimeDurationSec <= 0.0 || accumulatedDeviationDurationSec >= minRegimeDurationSec)
                ) {
                    isRegimeChanging = true
                    // 确认工况跃迁（持续做功达到门限）：以较快步长（30%）将基线拉向当前新工况
                    baselinePowerWatts = baseP * 0.70 + curP * 0.30
                }
            } else {
                consecutiveDeviationCount = 0
                accumulatedDeviationDurationSec = 0.0
                // 平滑阻尼衰减更新：半衰期约 60 秒 (tau = 60.0s)，逐步收敛回 0.0
                if (recoveryDecayRatio > 0.0) {
                    recoveryDecayRatio *= kotlin.math.exp(-safeDelta / 60.0)
                    if (recoveryDecayRatio < 0.01) {
                        recoveryDecayRatio = 0.0
                    }
                }
                // 稳态平缓更新基线（惯性 EWMA，95% 历史 + 5% 当前）
                baselinePowerWatts = baseP * 0.95 + curP * 0.05
            }
        } else {
            // 当前已处于跃迁状态：以较快速度跟进新工况基线，并利用滞回门限判定退出
            baselinePowerWatts = baseP * 0.70 + curP * 0.30
            if (absDiff <= recoveryThreshold) {
                consecutiveSteadyCount++
                accumulatedSteadyDurationSec += safeDelta
                if (consecutiveSteadyCount >= requiredConsecutiveCycles &&
                    (minRegimeDurationSec <= 0.0 || accumulatedSteadyDurationSec >= minRegimeDurationSec)
                ) {
                    isRegimeChanging = false
                    recoveryDecayRatio = 1.0 // 确立退出跃迁，开启连续平滑阻尼衰减过渡，杜绝突变权重大幅跳变
                    consecutiveDeviationCount = 0
                    accumulatedDeviationDurationSec = 0.0
                    consecutiveSteadyCount = 0
                    accumulatedSteadyDurationSec = 0.0
                }
            } else {
                consecutiveSteadyCount = 0
                accumulatedSteadyDurationSec = 0.0
                consecutiveDeviationCount = (consecutiveDeviationCount + 1).coerceAtMost(10)
            }
        }

        return isRegimeChanging
    }

    /**
     * 获取工况跃迁状态或突变平滑衰减过渡期的自适应权重修正系数。
     * 发生工况跃迁时增大当前近期模型（Fast/Medium）的权重响应速度，减小历史长期惯性；
     * 退出跃迁后通过指数阻尼平滑回落，杜绝阶跃式时间跳变。
     *
     * @return 介于 1.0 至 2.0 之间的快速响应加权乘子
     */
    fun getResponsivenessMultiplier(): Double {
        return when {
            isRegimeChanging -> {
                1.5 + (consecutiveDeviationCount.coerceAtMost(5) - requiredConsecutiveCycles).coerceAtLeast(0) * 0.1
            }
            recoveryDecayRatio > 0.0 -> {
                // 退出跃迁后的平滑阻尼过渡期，从 1.5 连续渐进回落至 1.0
                1.0 + 0.5 * recoveryDecayRatio
            }
            else -> {
                1.0
            }
        }
    }
}
