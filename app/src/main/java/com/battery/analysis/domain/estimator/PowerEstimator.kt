package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.PowerSample
import com.battery.analysis.domain.estimator.model.ScreenPowerState
import kotlin.math.abs
import kotlin.math.exp

/**
 * 自适应多时间尺度放电功耗估计器。
 * 融合 Fast（最近 ~5 分钟操作）、Medium（当前会话 ~30-60 分钟稳态）与 Long（近 7 天大样本习惯）三时间尺度，
 * 并结合 MAD 异常抑制与工况突变检测，动态求解未来放电的代表性预测功率。
 *
 * @param anomalyDetector 异常功耗检测器（用于抑制孤立采样尖峰）
 * @param stateDetector 工况跃迁检测器（用于感应进入游戏或退出高负载）
 */
class PowerEstimator(
    private val anomalyDetector: PowerAnomalyDetector = PowerAnomalyDetector(),
    private val stateDetector: PowerStateDetector = PowerStateDetector()
) {

    companion object {
        /** Fast 模型时间常数（秒）：5 分钟 = 300 秒 */
        private const val TAU_FAST_SEC = 300.0
        /** Medium 模型时间常数（秒）：30 分钟 = 1800 秒 */
        private const val TAU_MEDIUM_SEC = 1800.0
        /** 滑动对照窗口最大样本量上限 */
        private const val MAX_WINDOW_SIZE = 30
    }

    /** 最近样本滑动窗口，用于计算 Median + MAD */
    private val recentSamples = mutableListOf<Float>()

    /** 上次采样时间戳（毫秒） */
    private var lastSampleTimestampMs: Long = 0L

    /** 当前跟踪的有效统计总物理时长（毫秒） */
    var totalSessionDurationMs: Long = 0L
        private set

    /** Fast 尺度（~5min）EWMA 估算功率（单位：W） */
    private var fastPowerWatts: Double? = null

    /** Medium 尺度（~30-60min）会话估算功率（单位：W） */
    private var mediumPowerWatts: Double? = null

    /** Long 尺度（近 7 天）大样本加权基准功率（单位：W） */
    private var longPowerWatts: Double? = null

    /** 累计有效物理样本点总数 */
    var sampleCount: Int = 0
        private set

    /**
     * 重置估计器所有内部状态与缓存数据。
     *
     * @param initialLongPowerWatts 可选的初始 7 天长期基准功耗（单位：W）
     */
    fun reset(initialLongPowerWatts: Double? = null) {
        recentSamples.clear()
        lastSampleTimestampMs = 0L
        totalSessionDurationMs = 0L
        fastPowerWatts = null
        mediumPowerWatts = null
        longPowerWatts = initialLongPowerWatts?.takeIf { it > 0.0 }
        sampleCount = 0
        stateDetector.reset(initialLongPowerWatts)
    }

    /**
     * 设置或更新过去 7 天长期历史大样本基准功率。
     *
     * @param powerWatts 7 天加权平均放电功率（单位：W，若无历史数据传入 null）
     */
    fun setLongTermPower(powerWatts: Double?) {
        longPowerWatts = powerWatts?.takeIf { it > 0.0 }
        if (stateDetector.baselinePowerWatts == null && longPowerWatts != null) {
            stateDetector.reset(longPowerWatts)
        }
    }

    /**
     * 直接注入当前放电会话的已统计宏观均值（用于快速冷启动同步）。
     *
     * @param durationMs 当前会话已持续物理时长（毫秒）
     * @param powerWatts 当前会话测得的平均物理功率（单位：W）
     */
    fun seedCurrentSession(durationMs: Long, powerWatts: Float) {
        if (durationMs > 0L && powerWatts > 0f) {
            totalSessionDurationMs = durationMs
            val p = powerWatts.toDouble()
            fastPowerWatts = p
            mediumPowerWatts = p
            sampleCount = sampleCount.coerceAtLeast(1)
            stateDetector.reset(p)
        }
    }

    /**
     * 向估计器注入一个新的物理电池采样点并更新各时间尺度模型。
     * 充电状态数据将被严格拦截，不进入放电功耗模型。
     *
     * @param sample 包含电压、功率、状态与时间戳的物理采样数据包 [PowerSample]
     */
    fun update(sample: PowerSample) {
        // 充电状态数据严禁污染放电估计器
        if (sample.state == ScreenPowerState.CHARGING || sample.powerWatts <= 0f) {
            return
        }

        val power = sample.powerWatts
        val currentTs = sample.timestampMs

        // 1. 计算自适应采样间隔与时长累加
        val deltaSec = if (lastSampleTimestampMs > 0L && currentTs > lastSampleTimestampMs) {
            ((currentTs - lastSampleTimestampMs) / 1000.0).coerceIn(0.1, 300.0)
        } else {
            1.0 // 默认首帧或微小间隔 1s
        }
        lastSampleTimestampMs = currentTs
        totalSessionDurationMs += (deltaSec * 1000.0).toLong()
        sampleCount++

        // 2. 工况迁移检测（先识别是否已处于跃迁或发生跃迁触发）
        stateDetector.update(power)
        val isChanging = stateDetector.isRegimeChanging
        val responsiveness = stateDetector.getResponsivenessMultiplier()

        // 3. MAD 异常检验与置信度权重计算（联动工况跃迁标志，跃迁激活期不将持续高负载误杀为尖峰）
        val sampleWeight = anomalyDetector.getSampleWeight(power, recentSamples, isChanging)
        recentSamples.add(power)
        if (recentSamples.size > MAX_WINDOW_SIZE) {
            recentSamples.removeAt(0)
        }

        // 4. Fast 模型更新（EWMA，动态 alpha = 1 - exp(-dt / tau)）
        val alphaFast = (1.0 - exp(-deltaSec / TAU_FAST_SEC)).coerceIn(0.05, 0.60) * sampleWeight * responsiveness
        val effectiveAlphaFast = alphaFast.coerceIn(0.01, 0.95)
        fastPowerWatts = if (fastPowerWatts == null) {
            power.toDouble()
        } else {
            (effectiveAlphaFast * power.toDouble()) + ((1.0 - effectiveAlphaFast) * fastPowerWatts!!)
        }

        // 5. Medium 模型更新（更平缓的 EWMA）
        val alphaMedium = (1.0 - exp(-deltaSec / TAU_MEDIUM_SEC)).coerceIn(0.01, 0.30) * sampleWeight
        val effectiveAlphaMedium = alphaMedium.coerceIn(0.005, 0.50)
        mediumPowerWatts = if (mediumPowerWatts == null) {
            power.toDouble()
        } else {
            (effectiveAlphaMedium * power.toDouble()) + ((1.0 - effectiveAlphaMedium) * mediumPowerWatts!!)
        }
    }

    /**
     * 获取 Fast 尺度估算功率。
     *
     * @return Fast 估算放电功率（单位：W，缺失时为 null）
     */
    fun getFastPower(): Double? = fastPowerWatts?.takeIf { it > 0.0 }

    /**
     * 获取 Medium 尺度估算功率。
     *
     * @return Medium 估算放电功率（单位：W，缺失时为 null）
     */
    fun getMediumPower(): Double? = mediumPowerWatts?.takeIf { it > 0.0 }

    /**
     * 获取 Long 尺度估算功率。
     *
     * @return Long 估算放电功率（单位：W，缺失时为 null）
     */
    fun getLongPower(): Double? = longPowerWatts?.takeIf { it > 0.0 }

    /**
     * 结合当前已积累的数据量、多尺度间方差稳定性与工况迁移状态，
     * 自适应计算 Fast/Medium/Long 三个模型的动态归一化权重。
     * 满足 w_fast + w_medium + w_long == 1.0 且均 >= 0.0。
     *
     * @return 包含 (w_fast, w_medium, w_long) 且和为 1.0 的权重三元组 [Triple]
     */
    fun calculateAdaptiveWeights(): Triple<Double, Double, Double> {
        val hasFast = fastPowerWatts != null && fastPowerWatts!! > 0.0
        val hasMedium = mediumPowerWatts != null && mediumPowerWatts!! > 0.0
        val hasLong = longPowerWatts != null && longPowerWatts!! > 0.0

        if (!hasFast && !hasMedium && !hasLong) {
            return Triple(0.0, 0.0, 0.0)
        }

        // 若部分数据源缺失，退化处理
        if (!hasLong && hasFast && hasMedium) {
            val total = 0.40 + 0.60
            return Triple(0.40 / total, 0.60 / total, 0.0)
        } else if (!hasLong && hasFast) {
            return Triple(1.0, 0.0, 0.0)
        } else if (!hasLong && hasMedium) {
            return Triple(0.0, 1.0, 0.0)
        } else if (hasLong && !hasFast && !hasMedium) {
            return Triple(0.0, 0.0, 1.0)
        } else if (hasLong && hasFast && !hasMedium) {
            val total = 0.60 + 0.40
            return Triple(0.60 / total, 0.0, 0.40 / total)
        }

        // 1. 基础时间连续权重插值分配（依据当前会话已持续物理时长平滑过渡，杜绝阶跃跳变）
        val (baseWf, baseWm, baseWl) = interpolateBaselineWeights(totalSessionDurationMs)

        // 2. 稳定性修正与工况突变响应（升级为凸组合模型，天然保持权重和严格归一为 1.0）：
        // 若检测到工况跃迁激活（如进入 3D 游戏或启动重载相机），提高对 Fast 敏捷度的信任程度 gamma，并同比例平滑压制 Medium 与 Long
        val (finalWf, finalWm, finalWl) = if (stateDetector.isRegimeChanging) {
            val mult = stateDetector.getResponsivenessMultiplier()
            // 计算响应因子 gamma，范围在 0.40 至 0.85 之间
            val gamma = (0.30 + (mult - 1.0) * 0.40).coerceIn(0.40, 0.85)
            val wfPrime = baseWf + gamma * (1.0 - baseWf)
            val wmPrime = (1.0 - gamma) * baseWm
            val wlPrime = (1.0 - gamma) * baseWl
            Triple(wfPrime, wmPrime, wlPrime)
        } else {
            // 稳态下若 Fast 与 Medium 差异很小（稳定运行），温和降低 Long 依赖
            val f = fastPowerWatts ?: 0.0
            val m = mediumPowerWatts ?: 0.0
            if (f > 0.0 && m > 0.0 && abs(f - m) / m < 0.15) {
                val shift = baseWl * 0.15
                Triple(baseWf, baseWm + shift, baseWl - shift)
            } else {
                Triple(baseWf, baseWm, baseWl)
            }
        }

        // 严格消除微小浮点精度误差，确保 wf + wm + wl == 1.0
        val sum = finalWf + finalWm + finalWl
        return if (sum > 0.0) {
            Triple(finalWf / sum, finalWm / sum, finalWl / sum)
        } else {
            Triple(0.0, 0.0, 1.0)
        }
    }

    /**
     * 根据会话持续物理时长在预设时间锚点间进行连续线性插值，求解平滑连续的基础权重分布。
     * 锚点：
     * - 0min: (0.10, 0.20, 0.70)
     * - 10min: (0.25, 0.30, 0.45)
     * - 30min: (0.40, 0.40, 0.20)
     * - >=60min: (0.30, 0.50, 0.20)
     *
     * @param durationMs 当前放电会话物理持续毫秒数
     * @return 连续平滑插值后的 (wFast, wMedium, wLong) 基础权重三元组，和恒等于 1.0
     */
    fun interpolateBaselineWeights(durationMs: Long): Triple<Double, Double, Double> {
        return when {
            durationMs <= 0L -> Triple(0.10, 0.20, 0.70)
            durationMs < 600_000L -> {
                // 0 ~ 10 分钟平滑插值
                val p = durationMs.toDouble() / 600_000.0
                val wf = 0.10 + p * (0.25 - 0.10)
                val wm = 0.20 + p * (0.30 - 0.20)
                val wl = 0.70 + p * (0.45 - 0.70)
                Triple(wf, wm, wl)
            }
            durationMs < 1_800_000L -> {
                // 10 ~ 30 分钟平滑插值
                val p = (durationMs - 600_000L).toDouble() / 1_200_000.0
                val wf = 0.25 + p * (0.40 - 0.25)
                val wm = 0.30 + p * (0.40 - 0.30)
                val wl = 0.45 + p * (0.20 - 0.45)
                Triple(wf, wm, wl)
            }
            durationMs < 3_600_000L -> {
                // 30 ~ 60 分钟平滑插值
                val p = (durationMs - 1_800_000L).toDouble() / 1_800_000.0
                val wf = 0.40 + p * (0.30 - 0.40)
                val wm = 0.40 + p * (0.50 - 0.40)
                val wl = 0.20
                Triple(wf, wm, wl)
            }
            else -> Triple(0.30, 0.50, 0.20)
        }
    }

    /**
     * 计算并获取经过三时间尺度自适应加权后的最终综合预测放电功率。
     *
     * @return 预测的放电物理功率（单位：W，若无有效数据返回 null）
     */
    fun getPredictedPower(): Double? {
        val (wf, wm, wl) = calculateAdaptiveWeights()
        val f = fastPowerWatts ?: 0.0
        val m = mediumPowerWatts ?: 0.0
        val l = longPowerWatts ?: 0.0

        val predicted = (wf * f) + (wm * m) + (wl * l)
        return if (predicted > 0.0) predicted else null
    }

    /**
     * 计算当前功耗预测模型的综合可信度指数（Confidence）。
     *
     * @return 介于 0.0 至 1.0 之间的置信度指数
     */
    fun getConfidence(): Double {
        val hasCurrent = (fastPowerWatts != null || mediumPowerWatts != null)
        val hasLong = (longPowerWatts != null)

        if (!hasCurrent && !hasLong) return 0.0

        var baseScore = when {
            totalSessionDurationMs >= 3_600_000L -> 0.85
            totalSessionDurationMs >= 1_800_000L -> 0.70
            totalSessionDurationMs >= 600_000L -> 0.55
            totalSessionDurationMs > 0L -> 0.40
            else -> 0.30
        }

        if (hasLong) baseScore += 0.10
        if (stateDetector.isRegimeChanging) baseScore -= 0.15

        return baseScore.coerceIn(0.10, 1.0)
    }
}
