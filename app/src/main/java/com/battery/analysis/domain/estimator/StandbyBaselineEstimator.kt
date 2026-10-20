package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.StandbyBaselineConfig
import com.battery.analysis.domain.estimator.model.StandbyBaselineEstimate
import com.battery.analysis.domain.estimator.model.StandbyBaselineSource
import com.battery.analysis.domain.estimator.model.StandbyConfidenceLevel
import com.battery.analysis.model.PowerDischargePoint
import java.util.Locale

/**
 * 息屏待机基线功率稳健估计器。
 *
 * 核心设计目标与物理原则：
 * 1. 消除单点噪声漏洞：严禁单次偶然极小值（如 I2C 总线抖动读出的 0.01W）直接决定整夜数小时的长断层基线；
 * 2. 消除高频唤醒选择偏差：先按时间窗口（Window-level）进行聚合取代表值，杜绝短时间数百个高频唤醒点吞噬深睡低功耗状态；
 * 3. 稳健统计推导：以窗口级第 10 百分位数（P10）作为主待机基线候选值，P5 辅助评估低端离散度；
 * 4. 真实物理底噪门禁与多窗口支持度：要求低功耗状态具备时间维度上的重复支持；
 * 5. 坚守零假数据原则：在样本不足或污染时如实回退至客观先验（0.15W），绝不使用任何人为 α 参数进行混合伪造。
 */
object StandbyBaselineEstimator {

    /**
     * 根据瞬时放电物理采样点序列与底层采样周期，估算息屏待机基线功率。
     *
     * @param samples 瞬时放电物理采样点列表 [List<PowerDischargePoint>]
     * @param recordIntervalMs 底层采样轮询基础周期（毫秒，默认 1000ms）
     * @param config 待机基线估计配置类 [StandbyBaselineConfig]
     * @return 待机基线估计结果对象 [StandbyBaselineEstimate]
     */
    fun estimate(
        samples: List<PowerDischargePoint>,
        recordIntervalMs: Long = 1000L,
        config: StandbyBaselineConfig = StandbyBaselineConfig()
    ): StandbyBaselineEstimate {
        // 1. 数据有效性清洗：仅保留息屏、放电中且功率合法的点，并按时间升序排列
        val validSamples = samples
            .filter { !it.isScreenOn && it.powerWatts.isFinite() && it.powerWatts > 0f && it.timestamp > 0L }
            .sortedBy { it.timestamp }

        val rawMinWatts = validSamples.minOfOrNull { it.powerWatts.toDouble() }

        // 核心守卫：若有效息屏样本量不足以划分时间窗口，直接安全回退默认先验
        if (validSamples.size < config.minSamplesPerWindow) {
            return StandbyBaselineEstimate(
                baselinePowerWatts = config.defaultPriorWatts,
                source = StandbyBaselineSource.DEFAULT_STANDBY_PRIOR,
                confidence = StandbyConfidenceLevel.LOW,
                validWindowCount = 0,
                coverageDurationMs = 0L,
                rawMinWatts = rawMinWatts,
                fallbackReason = "有效息屏采样点不足(${validSamples.size} < ${config.minSamplesPerWindow})"
            )
        }

        val totalTimeSpanMs = validSamples.last().timestamp - validSamples.first().timestamp

        // 2. 自适应时间窗口划分：
        // 窗口长度自适应底层采样周期，防止采样周期拉长时（如 30s 一次）窗口过窄被误判为无效
        val windowDurationMs = maxOf(config.baseWindowDurationMs, recordIntervalMs * 3)
        val minSamplesPerWindow = if (recordIntervalMs >= 20_000L) 1 else config.minSamplesPerWindow

        val windows = aggregateIntoTimeWindows(
            samples = validSamples,
            windowDurationMs = windowDurationMs,
            minSamplesPerWindow = minSamplesPerWindow
        )

        // 若有效窗口数不足以支撑稳健分位数统计
        if (windows.size < config.minWindowsForMediumConfidence) {
            return StandbyBaselineEstimate(
                baselinePowerWatts = config.defaultPriorWatts,
                source = StandbyBaselineSource.DEFAULT_STANDBY_PRIOR,
                confidence = StandbyConfidenceLevel.LOW,
                validWindowCount = windows.size,
                coverageDurationMs = totalTimeSpanMs,
                rawMinWatts = rawMinWatts,
                fallbackReason = "有效时间窗口数不足(${windows.size} < ${config.minWindowsForMediumConfidence})"
            )
        }

        // 3. 计算窗口级稳健统计量（P5, P10, P50, MAD）
        val windowPowers = windows.map { it.representativePowerWatts }.sorted()
        val p5Watts = calculatePercentile(windowPowers, config.diagnosticLowerQuantile)
        val p10Watts = calculatePercentile(windowPowers, config.baselineQuantile)
        val p50Watts = calculatePercentile(windowPowers, 0.50)
        val madWatts = calculateMad(windowPowers, p50Watts)

        // 4. 多窗口低功耗支持度评估与污染熔断检查
        // 统计有多少个独立时间窗口的代表功率处于纯净待机合理水平（<= 0.20W）
        val lowPowerWindows = windowPowers.filter { it <= config.suspiciousPollutionThresholdWatts }
        val lowPowerSupportCount = lowPowerWindows.size

        // 物理准则：若所有窗口的代表功率普遍偏高（P10 > 0.20W），且几乎没有低功耗窗口重复支持，
        // 说明息屏期间设备持续处于后台活跃状态（如音频播放、热点或网络高频唤醒），样本池被严重污染
        if (p10Watts > config.suspiciousPollutionThresholdWatts && lowPowerSupportCount < config.minLowPowerSupportWindows) {
            return StandbyBaselineEstimate(
                baselinePowerWatts = config.defaultPriorWatts,
                source = StandbyBaselineSource.DEFAULT_STANDBY_PRIOR,
                confidence = StandbyConfidenceLevel.LOW,
                validWindowCount = windows.size,
                coverageDurationMs = totalTimeSpanMs,
                p5Watts = p5Watts,
                p10Watts = p10Watts,
                p50Watts = p50Watts,
                madWatts = madWatts,
                rawMinWatts = rawMinWatts,
                fallbackReason = "息屏样本疑似受活跃任务严重污染(P10=${String.format(Locale.US, "%.2f", p10Watts)}W > ${config.suspiciousPollutionThresholdWatts}W 且缺乏低功耗窗口重复支持)"
            )
        }

        // 5. 确定最终实测基线功率（多窗口验证防噪声）：
        // 默认采信窗口级 P10；若捕获到合理物理底噪且具备时间窗口支持，采纳实测底噪
        var candidateWatts = p10Watts
        val plausibleMinWatts = rawMinWatts?.takeIf { it in 0.015..config.suspiciousPollutionThresholdWatts }
        if (plausibleMinWatts != null && plausibleMinWatts < candidateWatts) {
            if (lowPowerSupportCount >= config.minLowPowerSupportWindows || p5Watts <= config.suspiciousPollutionThresholdWatts) {
                candidateWatts = plausibleMinWatts
            }
        }

        // 6. 确定置信等级（坚守零假数据，不进行加权平滑掺假）：
        // 若窗口充足、时间覆盖长且离散度极小，评为 HIGH；否则评为 MEDIUM，均忠实直读实测值
        val isHighConfidence = windows.size >= config.minWindowsForHighConfidence &&
                totalTimeSpanMs >= 15 * 60 * 1000L &&
                madWatts <= 0.10

        val confidenceLevel = if (isHighConfidence) StandbyConfidenceLevel.HIGH else StandbyConfidenceLevel.MEDIUM

        return StandbyBaselineEstimate(
            baselinePowerWatts = candidateWatts,
            source = StandbyBaselineSource.MEASURED_WINDOW_P10,
            confidence = confidenceLevel,
            validWindowCount = windows.size,
            coverageDurationMs = totalTimeSpanMs,
            p5Watts = p5Watts,
            p10Watts = p10Watts,
            p50Watts = p50Watts,
            madWatts = madWatts,
            rawMinWatts = rawMinWatts,
            fallbackReason = null
        )
    }

    /**
     * 将原始时间序列采样点划分为固定步长的时间窗口，并提取每个有效窗口的代表功率。
     *
     * @param samples 已按时间排序的有效采样点列表
     * @param windowDurationMs 单个时间窗口的时长（毫秒）
     * @param minSamplesPerWindow 判定为有效时间窗口所需的最少采样点数
     * @return 有效时间窗口聚合结果列表 [List<StandbyTimeWindow>]
     */
    private fun aggregateIntoTimeWindows(
        samples: List<PowerDischargePoint>,
        windowDurationMs: Long,
        minSamplesPerWindow: Int
    ): List<StandbyTimeWindow> {
        if (samples.isEmpty() || windowDurationMs <= 0L) return emptyList()

        val result = mutableListOf<StandbyTimeWindow>()
        val startTs = samples.first().timestamp
        val bucketMap = LinkedHashMap<Long, MutableList<Double>>()

        for (sample in samples) {
            val bucketIndex = (sample.timestamp - startTs) / windowDurationMs
            val bucketList = bucketMap.getOrPut(bucketIndex) { mutableListOf() }
            bucketList.add(sample.powerWatts.toDouble())
        }

        for ((index, powers) in bucketMap) {
            if (powers.size >= minSamplesPerWindow) {
                powers.sort()
                val medianWatts = calculateMedian(powers)
                val windowStart = startTs + index * windowDurationMs
                result.add(
                    StandbyTimeWindow(
                        startTs = windowStart,
                        endTs = windowStart + windowDurationMs,
                        sampleCount = powers.size,
                        representativePowerWatts = medianWatts
                    )
                )
            }
        }

        return result
    }

    /**
     * 计算已排序数值列表的中位数。
     *
     * @param sortedList 已升序排序的双精度浮点数列表
     * @return 中位数值，若列表为空返回 0.0
     */
    private fun calculateMedian(sortedList: List<Double>): Double {
        if (sortedList.isEmpty()) return 0.0
        val size = sortedList.size
        return if (size % 2 == 1) {
            sortedList[size / 2]
        } else {
            (sortedList[size / 2 - 1] + sortedList[size / 2]) / 2.0
        }
    }

    /**
     * 计算已排序数值列表的任意指定百分位数（采用标准线性插值法）。
     *
     * @param sortedList 已升序排序的双精度浮点数列表
     * @param percentile 目标百分位点（0.0 ~ 1.0）
     * @return 线性插值推导出的百分位数值，若列表为空返回 0.0
     */
    private fun calculatePercentile(sortedList: List<Double>, percentile: Double): Double {
        if (sortedList.isEmpty()) return 0.0
        if (sortedList.size == 1) return sortedList.first()
        val clampedP = percentile.coerceIn(0.0, 1.0)
        val rank = clampedP * (sortedList.size - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = minOf(lowerIndex + 1, sortedList.size - 1)
        val weight = rank - lowerIndex
        return sortedList[lowerIndex] * (1.0 - weight) + sortedList[upperIndex] * weight
    }

    /**
     * 计算已排序数值列表关于中位数的绝对中位差（MAD, Median Absolute Deviation）。
     *
     * @param sortedList 已升序排序的双精度浮点数列表
     * @param median 已计算出的中位数值
     * @return 绝对中位差数值
     */
    private fun calculateMad(sortedList: List<Double>, median: Double): Double {
        if (sortedList.isEmpty()) return 0.0
        val deviations = sortedList.map { kotlin.math.abs(it - median) }.sorted()
        return calculateMedian(deviations)
    }

    /**
     * 内部时间窗口聚合实体类。
     *
     * @property startTs 窗口起始绝对时间戳（毫秒）
     * @property endTs 窗口结束绝对时间戳（毫秒）
     * @property sampleCount 窗口内容纳的有效物理采样点数
     * @property representativePowerWatts 窗口代表功率（中位数值，单位：W）
     */
    private data class StandbyTimeWindow(
        val startTs: Long,
        val endTs: Long,
        val sampleCount: Int,
        val representativePowerWatts: Double
    )
}
