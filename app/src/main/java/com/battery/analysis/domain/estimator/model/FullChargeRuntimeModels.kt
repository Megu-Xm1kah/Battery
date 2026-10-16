package com.battery.analysis.domain.estimator.model

/**
 * 满充电池能量的物理获取来源枚举。
 * 遵循系统底层物理数据可靠性优先级定义，真实反映硬件与电量计层的数据来源。
 *
 * @property description 能量来源的可读描述
 */
enum class EnergySource(val description: String) {
    /** 硬件电量计底层原生能量计数器读取推算 (nWh -> Wh) */
    NATIVE_FULL_ENERGY("硬件原生能量计数器"),
    /** 真实满充容量 FCC × 标称工作电压 (Ah * V -> Wh，FCC 已反映电池实际老化衰减) */
    FCC_TIMES_NOMINAL_VOLTAGE("真实满充容量(FCC)折算"),
    /** 出厂设计容量 × 真实健康度 SOH × 标称工作电压 */
    DESIGN_CAPACITY_TIMES_HEALTH("设计容量与健康度折算"),
    /** 能量来源不可用，无法进行物理推导（无假数据兜底） */
    UNAVAILABLE("未知/不可用")
}

/**
 * 样本数据或估计结果的数据质量等级枚举。
 */
enum class DataQuality {
    /** 高质量，具备完整连续采样与明确物理依据 */
    VALID,
    /** 低质量，存在采样间隔偏大、缺乏部分上下文或通过工程近似推导，可降权参与 */
    LOW_QUALITY,
    /** 无效数据，包含非物理值、时间倒退或充电异常，严禁参与模型统计 */
    INVALID
}

/**
 * 满充可用物理能量估算结果实体类。
 *
 * @property energyWh 估算的满充可用物理能量（单位：Wh，若无法推算则为 null，严禁假数据）
 * @property source 满充能量的物理获取来源 [EnergySource]
 * @property quality 数据质量等级 [DataQuality]
 * @property notes 相关的计算依据或降级说明标签集合
 */
data class FullChargeEnergyEstimate(
    val energyWh: Double?,
    val source: EnergySource,
    val quality: DataQuality,
    val notes: Set<String> = emptySet()
)

/**
 * 设备日常放电运行的核心功耗工况分类枚举。
 *
 * @property label 工况的可读标签描述
 */
enum class PowerRegime(val label: String) {
    /** 息屏低载待机（深度休眠底噪） */
    OFF_LOW("息屏待机"),
    /** 息屏活跃唤醒（后台媒体、下载、唤醒锁活跃） */
    OFF_HIGH("息屏唤醒"),
    /** 亮屏轻载（阅读、静态信息浏览） */
    ON_LIGHT("亮屏轻载"),
    /** 亮屏日常中载（即时通信、短视频流、普通应用交互） */
    ON_NORMAL("亮屏日常"),
    /** 亮屏重载（3D 大型游戏、高分辨率视频录制、持续渲染高运算） */
    ON_HEAVY("亮屏重载"),
    /** 未知工况或数据不足以划分类别 */
    UNKNOWN("未知工况")
}

/**
 * 历史放电物理区间实体类。
 * 表达一段不重叠、时间单调的有效放电时间段，作为工况划分与能量积分的基石。
 *
 * @property startTimeMs 区间起始系统时间戳（毫秒）
 * @property endTimeMs 区间结束系统时间戳（毫秒）
 * @property durationMs 区间物理持续时长（毫秒，必须大于 0）
 * @property energyWh 区间内整机消耗的物理能量（单位：Wh）
 * @property powerWatts 区间平均放电物理功率（单位：W）
 * @property isScreenOn 屏幕是否处于亮屏状态（true 为亮屏，false 为息屏）
 * @property quality 区间数据质量等级 [DataQuality]
 */
data class DischargeInterval(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val energyWh: Double,
    val powerWatts: Double,
    val isScreenOn: Boolean,
    val quality: DataQuality = DataQuality.VALID
)

/**
 * 满电续航算法模型版本标识枚举。
 */
enum class RuntimeModelVersion {
    /** 现有基线算法：168 小时历史全局能量加权平均线性推算 */
    FULL_RUNTIME_V1,
    /** 升级算法：有效历史窗口 + 工况条件化行为占比与稳健能量模型 */
    FULL_RUNTIME_V2
}

/**
 * 满电续航估计置信度等级枚举。
 *
 * @property description 可读置信度描述
 */
enum class RuntimeConfidenceLevel(val description: String) {
    /** 具备多天充足历史且工况覆盖完整 */
    HIGH("可信度高"),
    /** 历史数据覆盖数小时以上，具备典型参考价值 */
    MEDIUM("可信度中等"),
    /** 历史样本较少，数据处于积累阶段 */
    LOW("可信度较低"),
    /** 数据严重缺失或未获取到能量，无法可靠估算 */
    UNAVAILABLE("数据不足");

    companion object {
        /**
         * 根据连续打分（0.0 ~ 1.0）推导对应的可信度等级枚举。
         *
         * @param score 介于 0.0 至 1.0 之间的置信度分数
         * @return 对应的可信度等级枚举 [RuntimeConfidenceLevel]
         */
        fun fromScore(score: Double): RuntimeConfidenceLevel {
            return when {
                score <= 0.0 -> UNAVAILABLE
                score >= 0.70 -> HIGH
                score >= 0.45 -> MEDIUM
                else -> LOW
            }
        }
    }
}

/**
 * 满电续航算法统一输出聚合实体类。
 * 封装全局综合、纯亮屏与纯息屏待机三维度的满电理论可用时长与对应预测功耗。
 *
 * @property fullRuntimeMs 预测的设备满电理论可用续航物理毫秒数（全局综合，数据不足时为 null，严禁假数据）
 * @property fullEnergyWh 计算采用的满充可用物理总能量（单位：Wh，不可用时为 null）
 * @property predictedPowerWatts 预测的未来全局典型放电综合功率（单位：W，不可用时为 null）
 * @property modelVersion 产生本结果的算法模型版本 [RuntimeModelVersion]
 * @property confidenceLevel 预测结果的数据置信度等级 [RuntimeConfidenceLevel]
 * @property energySource 满充物理能量的数据获取来源 [EnergySource]
 * @property effectiveHistoryMs 统计实际覆盖的有效历史放电物理毫秒数（上限 168 小时）
 * @property usedRegimeModel 是否成功启用了工况条件化模型（若为 false 则表明已安全降级至全局基准）
 * @property fallbackReason 降级或降权的具体物理原因说明（若运行完整工况模型则为 null）
 * @property screenOnFullRuntimeMs 基于亮屏工况（轻/中/重载）典型加权推算的满电纯亮屏可用物理毫秒数（v2.0，无有效亮屏数据为 null）
 * @property screenOffFullRuntimeMs 基于息屏休眠与唤醒解构稳态加权推算的满电纯待机可用物理毫秒数（v2.0，无有效息屏数据为 null）
 * @property predictedScreenOnPowerWatts 预测的亮屏典型物理放电功率（单位：W，不可用时为 null）
 * @property predictedScreenOffPowerWatts 预测的息屏典型物理放电功率（单位：W，不可用时为 null）
 */
data class FullChargeRuntimeResult(
    val fullRuntimeMs: Long?,
    val fullEnergyWh: Double?,
    val predictedPowerWatts: Double?,
    val modelVersion: RuntimeModelVersion,
    val confidenceLevel: RuntimeConfidenceLevel,
    val energySource: EnergySource,
    val effectiveHistoryMs: Long,
    val usedRegimeModel: Boolean,
    val fallbackReason: String? = null,
    val screenOnFullRuntimeMs: Long? = null,
    val screenOffFullRuntimeMs: Long? = null,
    val predictedScreenOnPowerWatts: Double? = null,
    val predictedScreenOffPowerWatts: Double? = null
)
