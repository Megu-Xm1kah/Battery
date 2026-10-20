package com.battery.analysis.domain.estimator.model

/**
 * 息屏待机基线功率来源枚举。
 * 遵循系统零假数据原则，严格标注待机基线是来源于真实实测窗口统计，还是在数据缺失时回退的客观先验。
 */
enum class StandbyBaselineSource {
    /** 来源于高/中置信实测时间窗口第 10 百分位数 (P10) */
    MEASURED_WINDOW_P10,
    /** 设备历史实测校准先验 (保留扩展) */
    DEVICE_CALIBRATED_PRIOR,
    /** 默认初始先验 (无可靠实测样本或样本被严重污染时回退的初始先验基线) */
    DEFAULT_STANDBY_PRIOR
}

/**
 * 息屏待机基线置信度等级枚举。
 */
enum class StandbyConfidenceLevel {
    /**
     * 高置信度：
     * 有效时间窗口数量充足、覆盖时间充分、低功耗区间有多个有效时间窗口重复支持且分布离散度稳定。
     */
    HIGH,

    /**
     * 中置信度：
     * 具备一定数量的有效时间窗口支持，样本未检测到明显的异常污染，
     * 允许直接采信实测值，但在诊断信息中保留中等置信度标识（绝不进行人为平滑合成）。
     */
    MEDIUM,

    /**
     * 低置信度：
     * 有效窗口过少、时间跨度过短、或全部样本均受活跃任务严重污染，
     * 无法从当前样本中提取真实待机基线，必须安全回退至先验并记录原因。
     */
    LOW
}

/**
 * 息屏待机基线功率估计详细结果数据类。
 *
 * @property baselinePowerWatts 最终推导出的待机基线功率（单位：瓦特 W）
 * @property source 待机基线功率来源 [StandbyBaselineSource]
 * @property confidence 估计结果置信度等级 [StandbyConfidenceLevel]
 * @property validWindowCount 参与有效统计的时间窗口总数
 * @property coverageDurationMs 样本覆盖的实际物理时间跨度（单位：毫秒）
 * @property p5Watts 窗口级代表功率第 5 百分位数（单位：W，用于诊断低端离散度与下界，可为 null）
 * @property p10Watts 窗口级代表功率第 10 百分位数（单位：W，默认实测候选基线，可为 null）
 * @property p50Watts 窗口级代表功率中位数（单位：W，用于观察典型窗口分布，可为 null）
 * @property madWatts 窗口级代表功率绝对中位差 MAD（单位：W，衡量窗口间稳健离散度，可为 null）
 * @property rawMinWatts 原始采样点中的物理极小值（单位：W，仅用于异常诊断，严禁直接支配基线）
 * @property fallbackReason 触发回退先验的具体原因说明（若无回退为 null）
 */
data class StandbyBaselineEstimate(
    val baselinePowerWatts: Double,
    val source: StandbyBaselineSource,
    val confidence: StandbyConfidenceLevel,
    val validWindowCount: Int,
    val coverageDurationMs: Long,
    val p5Watts: Double? = null,
    val p10Watts: Double? = null,
    val p50Watts: Double? = null,
    val madWatts: Double? = null,
    val rawMinWatts: Double? = null,
    val fallbackReason: String? = null
)

/**
 * 息屏待机基线估计引擎配置参数类。
 *
 * @property defaultPriorWatts 缺少可靠实测样本时的初始默认待机先验功率（单位：W，默认 0.15W）
 * @property suspiciousPollutionThresholdWatts 疑似活跃任务污染判定门限（单位：W，默认 0.20W，高于此门限触发污染风险诊断）
 * @property baselineQuantile 用于作为待机基线的主分位点（默认 0.10 即 P10）
 * @property diagnosticLowerQuantile 用于诊断低端离散度的下界分位点（默认 0.05 即 P5）
 * @property baseWindowDurationMs 基础时间聚合窗口长度（单位：毫秒，默认 60,000ms 即 1 分钟）
 * @property minSamplesPerWindow 单个时间窗口判定为有效所需的最少有效采样点数（默认 2 个）
 * @property minWindowsForHighConfidence 判定为高置信度所需的最少有效时间窗口数（默认 5 个）
 * @property minWindowsForMediumConfidence 判定为中置信度所需的最少有效时间窗口数（默认 2 个）
 * @property minLowPowerSupportWindows 采信实测低基线所需的低功耗有效窗口重复支持数（默认至少 2 个窗口）
 */
data class StandbyBaselineConfig(
    val defaultPriorWatts: Double = 0.15,
    val suspiciousPollutionThresholdWatts: Double = 0.20,
    val baselineQuantile: Double = 0.10,
    val diagnosticLowerQuantile: Double = 0.05,
    val baseWindowDurationMs: Long = 60_000L,
    val minSamplesPerWindow: Int = 2,
    val minWindowsForHighConfidence: Int = 5,
    val minWindowsForMediumConfidence: Int = 2,
    val minLowPowerSupportWindows: Int = 2
)
