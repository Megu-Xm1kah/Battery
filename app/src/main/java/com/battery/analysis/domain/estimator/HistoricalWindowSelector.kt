package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.AdaptiveWindowMetrics
import com.battery.analysis.domain.estimator.model.DataSufficiencyLevel
import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.domain.estimator.model.PowerRegime
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerUsageRecord

/**
 * 自适应历史时间窗口推算结果实体类。
 * 封装选定的自然时间窗口边界、天数、包含的有效放电物理区间集合以及对应的元信息指标。
 *
 * @property startMs 统计时间窗口起始绝对系统时间戳（毫秒）
 * @property endMs 统计时间窗口结束绝对系统时间戳（毫秒）
 * @property windowDays 实际选定的日历历史窗口天数（7、14 或 30 天）
 * @property intervals 落在该窗口内经过边界物理等比裁剪并赋予时间衰减权重的有效放电物理区间列表
 * @property metrics 对应的自适应窗口元信息指标实体 [AdaptiveWindowMetrics]
 */
data class HistoricalWindow(
    val startMs: Long,
    val endMs: Long,
    val windowDays: Int,
    val intervals: List<DischargeInterval>,
    val metrics: AdaptiveWindowMetrics
)

/**
 * 自适应历史时间窗口选择器接口（HistoricalWindowSelector v2.1）。
 * 遵循“最近 7 天优先 + 数据不足时扩展到 14 天 + 仍不足时最多扩展到 30 天”的自适应阶梯分级原则，
 * 绝不固定死板读取 168 小时，也不为了机械凑满 168 小时有效放电而无节制向前读取历史陈旧机况。
 */
interface HistoricalWindowSelector {

    /**
     * 基于当前基准时刻与历史归档记录，自适应推导并选定最佳历史统计时间窗口。
     *
     * @param nowMs 当前统计基准系统毫秒时间戳
     * @param records 设备本地持久化的历史放电记录列表
     * @param currentOverview 当前正在进行的活跃放电会话统计（若无传入 null）
     * @param currentTrendPoints 当前活跃放电会话的时序采样点列表（可选，若提供则优先切片）
     * @return 选定的自适应历史时间窗口结果实体 [HistoricalWindow]
     */
    fun select(
        nowMs: Long,
        records: List<PowerUsageRecord>,
        currentOverview: PowerOverviewStats? = null,
        currentTrendPoints: List<com.battery.analysis.model.PowerDischargePoint>? = null
    ): HistoricalWindow
}

/**
 * 自适应历史时间窗口选择器默认实现类。
 *
 * 三级阶梯架构（v2.1 核心算法）：
 * 1. Level 1（最近 7 天 = 168h）：优先读取 [now - 168h, now]。若有效亮屏时长达标（默认 >= 12h）且至少覆盖 2 个稳定亮屏工况，则立即停止扩展，锁定 7 天窗口；
 * 2. Level 2（扩展到 14 天 = 336h）：若 7 天窗口内样本不足，扩展至 [now - 336h, now]，超出 7 天的部分赋予时间衰减权重。若样本达标则停止扩展；
 * 3. Level 3（最多扩展到 30 天 = 720h）：若 14 天仍不足，扩展至 [now - 720h, now]。30 天为最大硬上限；若 30 天仍不足，停止扩展并如实降低充分性评级与置信度。
 *
 * @param sampleNormalizer 历史样本规范化器实例
 * @param classifier 运行功耗工况分类器实例
 * @param targetScreenOnMs Level 1 与 Level 2 停止扩展所需的有效亮屏物理毫秒门槛（默认 12 小时 = 43,200,000 ms）
 * @param minScreenOnRegimes 停止扩展所需的最少稳定亮屏工况类别数（默认 2 类）
 * @param tauDays 历史样本时间指数衰减半衰常数（单位：天，默认 7.0 天）
 */
class DefaultHistoricalWindowSelector(
    private val sampleNormalizer: PowerSampleNormalizer = PowerSampleNormalizer(),
    private val classifier: PowerRegimeClassifier = PowerRegimeClassifier(),
    private val targetScreenOnMs: Long = 12L * 3600L * 1000L,
    private val minScreenOnRegimes: Int = 2,
    private val tauDays: Double = PowerSampleNormalizer.TAU_AGE_DAYS
) : HistoricalWindowSelector {

    companion object {
        /** Level 1：7 天（168 小时）自然毫秒数常量 */
        const val WINDOW_7D_MS: Long = 7L * 24L * 3600L * 1000L

        /** Level 2：14 天（336 小时）自然毫秒数常量 */
        const val WINDOW_14D_MS: Long = 14L * 24L * 3600L * 1000L

        /** Level 3：30 天（720 小时）最大自然毫秒数硬上限常量 */
        const val WINDOW_30D_MS: Long = 30L * 24L * 3600L * 1000L
    }

    /**
     * 判定指定放电物理区间集合是否已满足当前层级样本停止扩展的门控条件。
     * 门控标准：
     * 1. 纯亮屏达标：有效亮屏时间达到目标门槛（如 12h），且至少包含 [minScreenOnRegimes] 个累计时间达到稳定门槛（>= 60s）的亮屏工况；
     * 2. 综合放电达标：有效亮屏 >= 6h、息屏 >= 12h、总有效时长 >= 18h 且至少覆盖 1 类稳定亮屏工况与息屏工况。
     *
     * @param intervals 待评估的放电物理区间集合
     * @return 若样本充足满足门控条件返回 true，否则返回 false
     */
    fun isWindowSufficient(intervals: List<DischargeInterval>): Boolean {
        val onIntervals = intervals.filter { it.isScreenOn }
        val offIntervals = intervals.filter { !it.isScreenOn }
        val totalOnMs = onIntervals.sumOf { it.durationMs }
        val totalOffMs = offIntervals.sumOf { it.durationMs }
        val totalMs = intervals.sumOf { it.durationMs }

        val onDurByRegime = mutableMapOf<PowerRegime, Long>()
        for (interval in onIntervals) {
            val regime = classifier.classify(interval)
            if (regime in setOf(PowerRegime.ON_LIGHT, PowerRegime.ON_NORMAL, PowerRegime.ON_HEAVY)) {
                onDurByRegime[regime] = (onDurByRegime[regime] ?: 0L) + interval.durationMs
            }
        }

        // 单工况累计达到 60 秒方可视为有效稳定工况
        val stableRegimes = onDurByRegime.count { it.value >= RegimePowerEstimator.MIN_REGIME_DURATION_MS }

        return (totalOnMs >= targetScreenOnMs && stableRegimes >= minScreenOnRegimes) ||
               (totalOnMs >= 6L * 3600L * 1000L && totalOffMs >= 12L * 3600L * 1000L && totalMs >= 18L * 3600L * 1000L && stableRegimes >= 1)
    }

    /**
     * 统计指定放电区间集合中覆盖的稳定工况类别数量（包含亮屏与息屏）。
     *
     * @param intervals 待统计的放电物理区间列表
     * @return 达到最小稳定时间阈值（60s）的核心工况类别数量
     */
    fun countStableRegimes(intervals: List<DischargeInterval>): Int {
        val durByRegime = mutableMapOf<PowerRegime, Long>()
        for (interval in intervals) {
            val regime = classifier.classify(interval)
            if (regime != PowerRegime.UNKNOWN) {
                durByRegime[regime] = (durByRegime[regime] ?: 0L) + interval.durationMs
            }
        }
        return durByRegime.count { it.value >= RegimePowerEstimator.MIN_REGIME_DURATION_MS }
    }

    /**
     * 评定样本集合的数据充分性等级。
     * 遵循 v2.1 规范：
     * 1. 充足（SUFFICIENT）：有效亮屏 >= 12h 且至少 2 个稳定亮屏工况；
     * 2. 基本可用（MODERATE）：有效亮屏 >= 3h 且至少 1 个稳定工况；
     * 3. 样本较少（INSUFFICIENT）：有效亮屏 < 3h 或稳定工况缺失。
     *
     * @param intervals 待评定的放电物理区间集合
     * @return 对应的数据充分性等级 [DataSufficiencyLevel]
     */
    fun evaluateSufficiencyLevel(intervals: List<DischargeInterval>): DataSufficiencyLevel {
        val onIntervals = intervals.filter { it.isScreenOn }
        val onMs = onIntervals.sumOf { it.durationMs }

        val onDurByRegime = mutableMapOf<PowerRegime, Long>()
        for (interval in onIntervals) {
            val regime = classifier.classify(interval)
            if (regime in setOf(PowerRegime.ON_LIGHT, PowerRegime.ON_NORMAL, PowerRegime.ON_HEAVY)) {
                onDurByRegime[regime] = (onDurByRegime[regime] ?: 0L) + interval.durationMs
            }
        }
        val stableOnRegimes = onDurByRegime.count { it.value >= RegimePowerEstimator.MIN_REGIME_DURATION_MS }

        return when {
            onMs >= targetScreenOnMs && stableOnRegimes >= minScreenOnRegimes -> DataSufficiencyLevel.SUFFICIENT
            onMs >= 3L * 3600L * 1000L && stableOnRegimes >= 1 -> DataSufficiencyLevel.MODERATE
            else -> DataSufficiencyLevel.INSUFFICIENT
        }
    }

    /**
     * 执行自适应历史时间窗口三级分级选定。
     *
     * @param nowMs 当前统计基准系统毫秒时间戳
     * @param records 设备本地持久化的历史放电记录列表
     * @param currentOverview 当前正在进行的活跃放电会话统计（若无传入 null）
     * @param currentTrendPoints 当前活跃放电会话的时序采样点列表（可选，若提供则优先切片）
     * @return 选定的自适应历史时间窗口聚合实体 [HistoricalWindow]
     */
    override fun select(
        nowMs: Long,
        records: List<PowerUsageRecord>,
        currentOverview: PowerOverviewStats?,
        currentTrendPoints: List<com.battery.analysis.model.PowerDischargePoint>?
    ): HistoricalWindow {
        // --- Level 1：优先尝试最近 7 天 (168 小时) ---
        val intervals7dRaw = sampleNormalizer.extractDischargeIntervals(
            currentOverview = currentOverview,
            allRecords = records,
            maxTargetDurationMs = WINDOW_7D_MS,
            baseTimestamp = nowMs,
            currentTrendPoints = currentTrendPoints
        )
        // 7 天以内的区间为全额权重 1.0
        val intervals7d = intervals7dRaw.map { it.copy(ageWeight = 1.0) }

        if (isWindowSufficient(intervals7d)) {
            val effDischargeMs = intervals7d.sumOf { it.durationMs }
            val onMs = intervals7d.filter { it.isScreenOn }.sumOf { it.durationMs }
            val offMs = intervals7d.filter { !it.isScreenOn }.sumOf { it.durationMs }
            val stableRegimes = countStableRegimes(intervals7d)
            val metrics = AdaptiveWindowMetrics(
                windowDurationMs = WINDOW_7D_MS,
                windowDays = 7.0,
                effectiveDischargeDurationMs = effDischargeMs,
                screenOnDurationMs = onMs,
                screenOffDurationMs = offMs,
                sufficiency = DataSufficiencyLevel.SUFFICIENT,
                regimeCount = stableRegimes
            )
            return HistoricalWindow(
                startMs = nowMs - WINDOW_7D_MS,
                endMs = nowMs,
                windowDays = 7,
                intervals = intervals7d,
                metrics = metrics
            )
        }

        // --- Level 2：7 天不足，扩展到 14 天 (336 小时) ---
        val intervals14dRaw = sampleNormalizer.extractDischargeIntervals(
            currentOverview = currentOverview,
            allRecords = records,
            maxTargetDurationMs = WINDOW_14D_MS,
            baseTimestamp = nowMs,
            currentTrendPoints = currentTrendPoints
        )
        val intervals14d = intervals14dRaw.map {
            val weight = sampleNormalizer.calculateAgeWeight(it.endTimeMs, nowMs, tauDays)
            it.copy(ageWeight = weight)
        }

        if (isWindowSufficient(intervals14d)) {
            val effDischargeMs = intervals14d.sumOf { it.durationMs }
            val onMs = intervals14d.filter { it.isScreenOn }.sumOf { it.durationMs }
            val offMs = intervals14d.filter { !it.isScreenOn }.sumOf { it.durationMs }
            val stableRegimes = countStableRegimes(intervals14d)
            val sufficiency = evaluateSufficiencyLevel(intervals14d)
            val metrics = AdaptiveWindowMetrics(
                windowDurationMs = WINDOW_14D_MS,
                windowDays = 14.0,
                effectiveDischargeDurationMs = effDischargeMs,
                screenOnDurationMs = onMs,
                screenOffDurationMs = offMs,
                sufficiency = sufficiency,
                regimeCount = stableRegimes
            )
            return HistoricalWindow(
                startMs = nowMs - WINDOW_14D_MS,
                endMs = nowMs,
                windowDays = 14,
                intervals = intervals14d,
                metrics = metrics
            )
        }

        // --- Level 3：14 天仍不足，最多扩展到 30 天 (720 小时) ---
        val intervals30dRaw = sampleNormalizer.extractDischargeIntervals(
            currentOverview = currentOverview,
            allRecords = records,
            maxTargetDurationMs = WINDOW_30D_MS,
            baseTimestamp = nowMs,
            currentTrendPoints = currentTrendPoints
        )
        val intervals30d = intervals30dRaw.map {
            val weight = sampleNormalizer.calculateAgeWeight(it.endTimeMs, nowMs, tauDays)
            it.copy(ageWeight = weight)
        }

        val effDischargeMs = intervals30d.sumOf { it.durationMs }
        val onMs = intervals30d.filter { it.isScreenOn }.sumOf { it.durationMs }
        val offMs = intervals30d.filter { !it.isScreenOn }.sumOf { it.durationMs }
        val stableRegimes = countStableRegimes(intervals30d)
        val sufficiency = evaluateSufficiencyLevel(intervals30d)

        val metrics = AdaptiveWindowMetrics(
            windowDurationMs = WINDOW_30D_MS,
            windowDays = 30.0,
            effectiveDischargeDurationMs = effDischargeMs,
            screenOnDurationMs = onMs,
            screenOffDurationMs = offMs,
            sufficiency = sufficiency,
            regimeCount = stableRegimes
        )

        return HistoricalWindow(
            startMs = nowMs - WINDOW_30D_MS,
            endMs = nowMs,
            windowDays = 30,
            intervals = intervals30d,
            metrics = metrics
        )
    }
}
