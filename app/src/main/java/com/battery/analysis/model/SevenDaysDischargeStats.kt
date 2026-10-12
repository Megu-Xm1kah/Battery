package com.battery.analysis.model

/**
 * 累积 7 天物理使用时间核心放电速度、实际统计时长与满电续航统计实体类。
 * 封装亮屏、息屏与全局三个独立维度的累积 7 天（168 小时）使用时间加权平均放电速率（%/h）、实际有效累积物理时长（毫秒）及满电理论可用时长（毫秒）。
 *
 * @property screenOnDischargeRatePercentPerHour 亮屏累积 7 天加权平均放电速度（单位：%/h），数据缺失时为 null
 * @property screenOffDischargeRatePercentPerHour 息屏累积 7 天加权平均放电速度（单位：%/h），数据缺失时为 null
 * @property globalDischargeRatePercentPerHour 全局累积 7 天加权平均放电速度（单位：%/h），数据缺失时为 null
 * @property screenOnDurationMs 亮屏实际累积有效统计时长（单位：毫秒，上限 7 天使用时长），无数据时为 null
 * @property screenOffDurationMs 息屏实际累积有效统计时长（单位：毫秒，上限 7 天待机时长），无数据时为 null
 * @property globalDurationMs 全局实际累积有效统计时长（单位：毫秒，上限 7 天使用时长），无数据时为 null
 * @property fullChargeScreenOnDurationMs 基于累积亮屏放电速度推算的充满电理论连续亮屏使用时长（单位：毫秒），数据缺失时为 null
 * @property fullChargeScreenOffDurationMs 基于累积息屏放电速度推算的充满电理论待机时长（单位：毫秒），数据缺失时为 null
 * @property fullChargeGlobalDurationMs 基于累积全局放电速度推算的充满电理论综合使用时长（模式1全局加权，单位：毫秒），数据缺失时为 null
 * @property sceneScreenOnRatio 统计窗口内真实日常亮屏时间占比（取值 0.0f ~ 1.0f），无数据时为 null
 * @property sceneCompositeRatePercentPerHour 模式2场景融合加权放电速率（单位：%/h），数据缺失时为 null
 * @property fullChargeSceneCompositeDurationMs 模式2基于日常亮屏与待机场景占空比融合推算的充满电理论综合续航时长（单位：毫秒），数据缺失时为 null
 */
data class SevenDaysDischargeStats(
    val screenOnDischargeRatePercentPerHour: Float? = null,
    val screenOffDischargeRatePercentPerHour: Float? = null,
    val globalDischargeRatePercentPerHour: Float? = null,
    val screenOnDurationMs: Long? = null,
    val screenOffDurationMs: Long? = null,
    val globalDurationMs: Long? = null,
    val fullChargeScreenOnDurationMs: Long? = null,
    val fullChargeScreenOffDurationMs: Long? = null,
    val fullChargeGlobalDurationMs: Long? = null,
    val sceneScreenOnRatio: Float? = null,
    val sceneCompositeRatePercentPerHour: Float? = null,
    val fullChargeSceneCompositeDurationMs: Long? = null
)
