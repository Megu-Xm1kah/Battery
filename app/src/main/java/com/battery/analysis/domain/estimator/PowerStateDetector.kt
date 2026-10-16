package com.battery.analysis.domain.estimator

import kotlin.math.abs

/**
 * 功耗状态跃迁与工况突变检测器（Power Regime Change Detector）。
 * 监测设备从轻载到高负载（如启动 3D 游戏、相机拍摄、高分辨率视频录制）或从高负载回到轻载的工况迁移。
 * 当连续多个采样周期偏离基线功耗达到显著阈值（默认 50%）时，判定为进入新工况，
 * 驱动多时间尺度估计引擎提升响应灵敏度，平滑过渡至新状态。
 *
 * @param changeThresholdRatio 判定偏离基线的相对比率阈值（默认 0.50，即 50%）
 * @param requiredConsecutiveCycles 确认工况跃迁所需的最少连续高偏离采样周期数（默认 2 次）
 */
class PowerStateDetector(
    private val changeThresholdRatio: Double = 0.50,
    private val requiredConsecutiveCycles: Int = 2
) {

    /** 动态维护的历史稳态基线功耗（单位：W） */
    var baselinePowerWatts: Double? = null
        private set

    /** 当前已连续出现高偏离的周期计数 */
    var consecutiveDeviationCount: Int = 0
        private set

    /** 当前是否正处于工况迁移跃迁状态 */
    var isRegimeChanging: Boolean = false
        private set

    /**
     * 重置状态检测器上下文，清空连续偏离计数与工况跃迁标志。
     * 通常在屏幕开关切换、充电断开或开始新的统计会话时调用。
     *
     * @param initialBaselineWatts 可选的初始稳态基线功率（单位：W，若无传入 null）
     */
    fun reset(initialBaselineWatts: Double? = null) {
        baselinePowerWatts = initialBaselineWatts?.takeIf { it > 0.0 }
        consecutiveDeviationCount = 0
        isRegimeChanging = false
    }

    /**
     * 输入最新到达的功耗采样值，检测是否触发或维持工况跃迁。
     *
     * @param currentPowerWatts 当前周期测得的物理放电功率（单位：W）
     * @return 返回当前是否处于工况跃迁激活状态 [Boolean]
     */
    fun update(currentPowerWatts: Float): Boolean {
        if (currentPowerWatts <= 0f) {
            consecutiveDeviationCount = 0
            isRegimeChanging = false
            return false
        }

        val curP = currentPowerWatts.toDouble()
        val baseP = baselinePowerWatts

        if (baseP == null || baseP <= 0.0) {
            // 首个有效样本直接确立为初始基线
            baselinePowerWatts = curP
            consecutiveDeviationCount = 0
            isRegimeChanging = false
            return false
        }

        val relativeDiff = abs(curP - baseP) / baseP
        if (relativeDiff > changeThresholdRatio) {
            consecutiveDeviationCount++
            if (consecutiveDeviationCount >= requiredConsecutiveCycles) {
                isRegimeChanging = true
                // 当确认工况跃迁后，逐步将基线拉向当前新工况（按 30% 步长自适应迁移）
                baselinePowerWatts = baseP * 0.70 + curP * 0.30
            }
        } else {
            // 偏离在容限内，恢复稳态，逐步更新基线（惯性 EWMA，95% 历史 + 5% 当前）
            consecutiveDeviationCount = 0
            isRegimeChanging = false
            baselinePowerWatts = baseP * 0.95 + curP * 0.05
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
            1.5 + (consecutiveDeviationCount.coerceAtMost(5) - requiredConsecutiveCycles) * 0.1
        } else {
            1.0
        }
    }
}
