package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.PowerRegime

/**
 * 用户典型用机行为工况时间占比概率估算器。
 * 严格基于各工况实际覆盖的有效物理持续时长计算时间概率分布，
 * 彻底消除因采样率不均衡（如亮屏高频、息屏低频）导致的样本计数偏置，
 * 确保各工况概率有限、非负且归一化和严格等于 1.0。
 */
class UsageProfileEstimator {

    /**
     * 根据各工况实际有效物理持续时长计算归一化时间占比概率。
     * 遵循持续时间加权物理守恒原则，严禁按采样点数量计数。
     *
     * @param durationByRegime 各工况累计有效物理毫秒数的映射 Map
     * @return 归一化后的各工况时间占比概率 Map（各项和为 1.0，若无有效数据返回空 Map）
     */
    fun computeTimeRatios(durationByRegime: Map<PowerRegime, Long>): Map<PowerRegime, Double> {
        val validEntries = durationByRegime.filter { it.key != PowerRegime.UNKNOWN && it.value > 0L }
        val totalMs = validEntries.values.sum().toDouble()
        if (totalMs <= 0.0) return emptyMap()

        val rawRatios = validEntries.mapValues { it.value.toDouble() / totalMs }
        val sum = rawRatios.values.sum()
        return if (sum > 0.0) {
            rawRatios.mapValues { it.value / sum }
        } else {
            emptyMap()
        }
    }

    /**
     * 根据各工况经时间衰减加权后的有效持续时长计算归一化时间占比概率。
     * 适用于自适应历史时间窗口模型（超出 7 天的历史工况按指数权重平滑衰减）。
     *
     * @param durationByRegime 各工况经时间衰减加权后的有效持续毫秒数映射 Map
     * @return 归一化后的各工况时间占比概率 Map（各项和为 1.0，若无有效数据返回空 Map）
     */
    fun computeWeightedTimeRatios(durationByRegime: Map<PowerRegime, Double>): Map<PowerRegime, Double> {
        val validEntries = durationByRegime.filter { it.key != PowerRegime.UNKNOWN && it.value > 0.0 }
        val totalMs = validEntries.values.sum()
        if (totalMs <= 0.0) return emptyMap()

        val rawRatios = validEntries.mapValues { it.value / totalMs }
        val sum = rawRatios.values.sum()
        return if (sum > 0.0) {
            rawRatios.mapValues { it.value / sum }
        } else {
            emptyMap()
        }
    }

    /**
     * 融合长期历史行为分布（近 168 小时）与近期行为分布（近 24 小时），
     * 赋予模型对用户近期作息微小漂移的温和适应能力。
     * 遵循保守设计：近期分布影响权重上限严格限制（最大不超过 20%），数据不足时不引入偏置。
     *
     * @param longTermRatios 长期 168 小时工况时间占比分布
     * @param recentRatios 近期 24 小时工况时间占比分布（若数据不足可为空）
     * @param recentWeightFactor 近期权重因子（取值 0.0 至 0.20 之间，默认 0.15）
     * @return 融合归一化后的未来典型行为工况时间占比概率分布 Map
     */
    fun blendRecentAndLongTermRatios(
        longTermRatios: Map<PowerRegime, Double>,
        recentRatios: Map<PowerRegime, Double>?,
        recentWeightFactor: Double = 0.15
    ): Map<PowerRegime, Double> {
        if (longTermRatios.isEmpty()) return recentRatios ?: emptyMap()
        if (recentRatios.isNullOrEmpty()) return longTermRatios

        val beta = recentWeightFactor.coerceIn(0.0, 0.20)
        val allRegimes = longTermRatios.keys + recentRatios.keys
        val blended = mutableMapOf<PowerRegime, Double>()

        for (regime in allRegimes) {
            val pLong = longTermRatios[regime] ?: 0.0
            val pRecent = recentRatios[regime] ?: 0.0
            val pBlend = (1.0 - beta) * pLong + beta * pRecent
            if (pBlend > 0.0) {
                blended[regime] = pBlend
            }
        }

        val sum = blended.values.sum()
        return if (sum > 0.0) {
            blended.mapValues { it.value / sum }
        } else {
            longTermRatios
        }
    }
}
