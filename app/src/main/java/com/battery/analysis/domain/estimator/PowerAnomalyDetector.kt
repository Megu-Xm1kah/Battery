package com.battery.analysis.domain.estimator

import kotlin.math.abs

/**
 * 稳健统计异常功耗检测器。
 * 采用工业级中位数与绝对中位差（Median + MAD，Median Absolute Deviation）统计模型，
 * 对连续功耗采样序列中的突发瞬态尖峰或离群数据进行识别与自适应降权，
 * 避免短时偶发高负载（如短时启动相机、应用冷启动或垃圾回收）直接污染长效续航预测。
 */
class PowerAnomalyDetector(
    private val zScoreThreshold: Double = 3.0,
    private val minMadFloorWatts: Double = 0.05
) {

    /**
     * 计算一组数值序列的中位数。
     *
     * @param values 输入的连续浮点数列表
     * @return 序列的中位数，若输入列表为空则返回 null
     */
    fun calculateMedian(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0f
        }
    }

    /**
     * 计算一组功耗样本的绝对中位差（MAD）。
     * 公式为：MAD = median(|P_i - median(P)|)。
     *
     * @param values 输入的连续功耗值列表（单位：W）
     * @return 计算得出的绝对中位差，若样本不足则返回 null
     */
    fun calculateMad(values: List<Float>): Double? {
        val median = calculateMedian(values) ?: return null
        val absoluteDeviations = values.map { abs(it - median) }
        val madMedian = calculateMedian(absoluteDeviations) ?: return null
        return madMedian.toDouble()
    }

    /**
     * 计算指定功耗采样点相对于历史滑动窗口的标准化残差 Z-Score。
     * 公式为：Z_i = |P_i - m| / (1.4826 * max(MAD, floor))。
     *
     * @param powerWatts 待检测的目标功耗采样点（单位：W）
     * @param windowWatts 历史对照参考滑动窗口中的功耗采样点集合（单位：W）
     * @return 标准化残差 Z-Score 数值，若对照窗口样本不足 3 个则返回 0.0（不视为异常）
     */
    fun calculateZScore(powerWatts: Float, windowWatts: List<Float>): Double {
        if (windowWatts.size < 3) return 0.0
        val median = calculateMedian(windowWatts) ?: return 0.0
        val rawMad = calculateMad(windowWatts) ?: 0.0
        val effectiveMad = rawMad.coerceAtLeast(minMadFloorWatts)
        val deviation = abs(powerWatts - median).toDouble()
        return deviation / (1.4826 * effectiveMad)
    }

    /**
     * 判断指定功耗值在给定历史窗口背景下是否构成统计离群异常点。
     * 若当前已被工况突变检测器确认处于工况跃迁激活期（如进入 3D 游戏或启动重载应用），
     * 则判定该高功耗为真实工况转变，不视为统计异常离群点。
     *
     * @param powerWatts 待检测的目标功耗采样点（单位：W）
     * @param windowWatts 历史对照参考滑动窗口集合（单位：W）
     * @param isRegimeChanging 当前是否已处于工况跃迁激活状态（默认 false）
     * @return 若构成统计异常离群点返回 true，处于工况跃迁或在容限内返回 false
     */
    fun isAnomaly(
        powerWatts: Float,
        windowWatts: List<Float>,
        isRegimeChanging: Boolean = false
    ): Boolean {
        if (isRegimeChanging) return false
        val z = calculateZScore(powerWatts, windowWatts)
        return z > zScoreThreshold
    }

    /**
     * 计算该采样点在功耗模型累加中应当分配的衰减置信度权重因子。
     * 正常数据因子为 1.0；若检测出严重偏离的单次异常尖峰，则自适应衰减至低权重（如 0.15），
     * 既保留物理真实输入，又有效抑制瞬态跳变对模型的污染。
     * 若当前处于工况跃迁状态，表明属于持续性真实工况转变，权重保持 1.0 不予衰减。
     *
     * @param powerWatts 待计算权重的目标功耗值（单位：W）
     * @param windowWatts 历史对照参考滑动窗口集合（单位：W）
     * @param isRegimeChanging 当前是否已处于工况跃迁激活状态（默认 false）
     * @return 介于 0.15 至 1.0 之间的置信度权重因子
     */
    fun getSampleWeight(
        powerWatts: Float,
        windowWatts: List<Float>,
        isRegimeChanging: Boolean = false
    ): Double {
        if (isRegimeChanging) return 1.0
        val z = calculateZScore(powerWatts, windowWatts)
        return when {
            z <= zScoreThreshold -> 1.0
            z >= zScoreThreshold * 2.0 -> 0.15
            else -> {
                // 在 threshold ~ 2*threshold 之间线性平滑过渡
                val progress = (z - zScoreThreshold) / zScoreThreshold
                1.0 - 0.85 * progress
            }
        }
    }
}
