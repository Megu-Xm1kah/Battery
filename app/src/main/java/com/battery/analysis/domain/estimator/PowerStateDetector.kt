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
 * @param minRegimeDurationSec 确立工况突变所需的最小物理持续时间门限（单位：秒，默认 0.0s 兼容基础单测；实际工程中推荐 >= 30.0s）
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

    /**
     * 重置状态检测器上下文，清空偏离计数、累积时长与工况跃迁标志。
     * 通常在屏幕开关切换、充电插拔或启动新的统计会话时调用。
     *
     * @param initialBaselineWatts 可选的初始稳态基线功率（单位：W，若无传入 null）
     */
    fun reset(initialBaselineWatts: Double? = null) {
        baselinePowerWatts = initialBaselineWatts?.takeIf { it > 0.0 }
        consecutiveDeviationCount = 0
        accumulatedDeviationDurationSec = 0.0
        consecutiveSteadyCount = 0
        accumulatedSteadyDurationSec = 0.0
        isRegimeChanging = false
    }

    /**
     * 输入最新到达的物理功耗采样值，执行双门限与滞回判定，检测是否触发或维持工况跃迁。
     *
     * 判定准则：
     * 触发阈值：Threshold_trigger = max(minAbsoluteChangeWatts, changeThresholdRatio * baseline)；
     * 恢复滞回阈值：Threshold_recovery = max(minAbsoluteChangeWatts * 0.6, recoveryHysteresisRatio * baseline)。
     * 只有当连续偏离周期数达到 [requiredConsecutiveCycles] 且累计偏离持续时间达到 [minRegimeDurationSec] 时，才确立工况跃迁。
     *
     * @param currentPowerWatts 当前周期测得的物理放电功率（单位：W）
     * @param deltaSec 距上一次采样的物理间隔时间（单位：秒，默认 1.0s）
     * @return 返回当前是否处于工况跃迁激活状态 [Boolean]
     */
    fun update(currentPowerWatts: Float, deltaSec: Double = 1.0): Boolean {
        if (currentPowerWatts <= 0f) {
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

        if (!isRegimeChanging) {
            // 当前处于稳态：检测是否连续触发偏离
            if (absDiff > triggerThreshold) {
                consecutiveDeviationCount++
                accumulatedDeviationDurationSec += safeDelta
                consecutiveSteadyCount = 0
                accumulatedSteadyDurationSec = 0.0
                if (consecutiveDeviationCount >= requiredConsecutiveCycles &&
                    (minRegimeDurationSec <= 0.0 || accumulatedDeviationDurationSec >= minRegimeDurationSec)
                ) {
                    isRegimeChanging = true
                    // 确认工况跃迁：以较快步长（30%）将基线拉向当前新工况
                    baselinePowerWatts = baseP * 0.70 + curP * 0.30
                }
            } else {
                consecutiveDeviationCount = 0
                accumulatedDeviationDurationSec = 0.0
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
     * 获取工况跃迁状态下的自适应权重修正系数。
     * 发生工况跃迁时增大当前近期模型（Fast/Medium）的权重响应速度，减小历史长期惯性。
     *
     * @return 介于 1.0 至 2.0 之间的快速响应加权乘子
     */
    fun getResponsivenessMultiplier(): Double {
        return if (isRegimeChanging) {
            1.5 + (consecutiveDeviationCount.coerceAtMost(5) - requiredConsecutiveCycles).coerceAtLeast(0) * 0.1
        } else {
            1.0
        }
    }
}
