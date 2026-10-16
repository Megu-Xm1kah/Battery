package com.battery.analysis.domain.estimator

import kotlin.math.abs

/**
 * 稳健物理功耗统计工具类。
 * 运用工业级中位数、绝对中位差（Median + MAD，Median Absolute Deviation）与截尾统计模型，
 * 对连续功耗采样或区间序列中的突发瞬态孤立尖峰进行识别与自适应抑制，
 * 同时杜绝将持续重载（真实行为变化）误判为离群坏数据。
 */
object RobustPowerStatistics {

    /**
     * MAD 统计标准化缩放系数（对于正态分布，标准差与 MAD 的比例常数为 1.4826）。
     */
    private const val MAD_NORMAL_SCALE = 1.4826

    /**
     * 计算一组数值序列的中位数（Median）。
     *
     * @param values 输入的浮点数列表
     * @return 序列的中位数，若输入列表为空返回 null
     */
    fun calculateMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
        }
    }

    /**
     * 计算一组功耗样本的绝对中位差（MAD）。
     * 公式为：MAD = median(|P_i - median(P)|)。
     *
     * @param values 输入的连续功耗值列表（单位：W）
     * @return 计算得出的绝对中位差，若样本不足或无效返回 null
     */
    fun calculateMad(values: List<Double>): Double? {
        val median = calculateMedian(values) ?: return null
        val deviations = values.map { abs(it - median) }
        return calculateMedian(deviations)
    }

    /**
     * 计算指定功耗数值相对于参考样本集的稳健 Z-Score。
     * 公式为：Z_i = |P_i - Median| / (1.4826 * max(MAD, floor))。
     *
     * @param value 待检测的目标功耗值（单位：W）
     * @param referenceValues 参考滑动样本集（单位：W）
     * @param minMadFloorWatts MAD 的最小下限保护值（单位：W，防止全序列恒定时除零，默认 0.05W）
     * @return 标准化残差 Z-Score 数值，若样本集不足 3 个则返回 0.0
     */
    fun calculateRobustZScore(
        value: Double,
        referenceValues: List<Double>,
        minMadFloorWatts: Double = 0.05
    ): Double {
        if (referenceValues.size < 3) return 0.0
        val median = calculateMedian(referenceValues) ?: return 0.0
        val rawMad = calculateMad(referenceValues) ?: 0.0
        val effectiveMad = rawMad.coerceAtLeast(minMadFloorWatts)
        val deviation = abs(value - median)
        return deviation / (MAD_NORMAL_SCALE * effectiveMad)
    }

    /**
     * 计算序列在剔除最高与最低极端极值后的截尾均值（Trimmed Mean）。
     *
     * @param values 输入的浮点数值列表
     * @param trimRatio 首尾单侧截尾比例（默认 0.10 即各剔除 10% 极值）
     * @return 稳健截尾均值，若有效数据不足返回 null
     */
    fun calculateTrimmedMean(
        values: List<Double>,
        trimRatio: Double = 0.10
    ): Double? {
        val valid = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (valid.isEmpty()) return null
        if (valid.size < 5) return valid.average()

        val trimCount = (valid.size * trimRatio).toInt()
        val trimmed = valid.subList(trimCount, valid.size - trimCount)
        return if (trimmed.isNotEmpty()) trimmed.average() else valid.average()
    }
}
