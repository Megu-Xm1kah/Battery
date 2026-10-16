package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.domain.estimator.model.PowerRegime

/**
 * 各工况代表性物理放电功率估算器。
 * 遵循严格的能量积分除以持续时间物理做功守恒公式：P_i = Sum(E_k) / Sum(Delta t_k)，
 * 输出具备统计稳定性的各工况代表功率，数据量不足时严禁编造假数据。
 */
class RegimePowerEstimator {

    companion object {
        /** 单工况确立代表功率所需的最少有效持续时长（毫秒，默认 60 秒 = 60,000 ms） */
        const val MIN_REGIME_DURATION_MS: Long = 60_000L
    }

    /**
     * 计算指定工况类别在一组放电物理区间中的代表性物理放电功率（单位：W）。
     *
     * @param intervals 属于该工况的放电物理区间集合
     * @param minDurationMs 确立该工况所需的最小持续毫秒数（默认 60,000 ms）
     * @return 估算得出的物理代表功率（单位：W），若样本时长不足或数据无效则返回 null
     */
    fun estimateRepresentativePower(
        intervals: List<DischargeInterval>,
        minDurationMs: Long = MIN_REGIME_DURATION_MS
    ): Double? {
        if (intervals.isEmpty()) return null

        val totalDurationMs = intervals.sumOf { it.durationMs }
        val totalEnergyWh = intervals.sumOf { it.energyWh }

        if (totalDurationMs < minDurationMs || totalEnergyWh <= 0.0) {
            return null
        }

        val hours = totalDurationMs.toDouble() / 3600000.0
        val p = totalEnergyWh / hours
        return if (p > 0.0 && p.isFinite()) p else null
    }

    /**
     * 批量计算所有已知工况类别的代表性物理放电功率。
     *
     * @param intervalsByRegime 按工况分类归集的放电物理区间 Map
     * @param minDurationMs 确立各工况所需的最小持续毫秒数
     * @return 各工况与其对应代表功率（单位：W）的映射 Map
     */
    fun estimateAllRegimePowers(
        intervalsByRegime: Map<PowerRegime, List<DischargeInterval>>,
        minDurationMs: Long = MIN_REGIME_DURATION_MS
    ): Map<PowerRegime, Double> {
        val result = mutableMapOf<PowerRegime, Double>()
        for ((regime, intervals) in intervalsByRegime) {
            if (regime == PowerRegime.UNKNOWN) continue
            val power = estimateRepresentativePower(intervals, minDurationMs)
            if (power != null) {
                result[regime] = power
            }
        }
        return result
    }
}
