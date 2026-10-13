package com.battery.analysis.model

import android.content.Context
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.util.BatteryEnergyCalculator
import java.util.Locale

/**
 * 充电历史记录汇总统计实体类。
 * 用于本地内存中对用户选择或筛选的充电历史会话集合进行多维统计与物理量守恒汇总，
 * 精准反映亮屏、息屏与总计三维度的持续时长、充入能量（含等效 mAh）、平均充电功率及充电速率。
 *
 * @property recordCount 统计涵盖的充电历史记录条数
 * @property totalDurationMs 累计总充电持续时长（毫秒）
 * @property screenOnDurationMs 累计亮屏充电持续时长（毫秒）
 * @property screenOffDurationMs 累计息屏充电持续时长（毫秒）
 * @property totalEnergyWh 累计总充入能量（单位：Wh）
 * @property screenOnEnergyWh 累计亮屏充入能量（单位：Wh）
 * @property screenOffEnergyWh 累计息屏充入能量（单位：Wh）
 * @property totalLevelGainPercent 累计充入电量百分比净增量（单位：%）
 * @property screenOnLevelGainPercent 累计亮屏充入电量百分比增量（单位：%）
 * @property screenOffLevelGainPercent 累计息屏充入电量百分比增量（单位：%）
 * @property avgPowerWatts 综合平均充电功率（单位：W），无有效数据时为 null
 * @property screenOnPowerWatts 亮屏平均充电功率（单位：W），无有效数据时为 null
 * @property screenOffPowerWatts 息屏平均充电功率（单位：W），无有效数据时为 null
 * @property globalChargingRate 综合平均充电速度（单位：%/h），无有效数据时为 null
 * @property screenOnChargingRate 亮屏平均充电速度（单位：%/h），无有效数据时为 null
 * @property screenOffChargingRate 息屏平均充电速度（单位：%/h），无有效数据时为 null
 * @property maxPowerWatts 记录集合中录得的最高瞬时充电功率（单位：W），无数据时为 null
 * @property maxTemperature 记录集合中录得的最高电池温度（单位：℃），无数据时为 null
 * @property batteryCapacityWh 设备满电设计总能量容量（单位：Wh），用于基准参考，可为 null
 */
data class ChargingHistorySummary(
    val recordCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val screenOnDurationMs: Long = 0L,
    val screenOffDurationMs: Long = 0L,
    val totalEnergyWh: Double = 0.0,
    val screenOnEnergyWh: Double = 0.0,
    val screenOffEnergyWh: Double = 0.0,
    val totalLevelGainPercent: Int = 0,
    val screenOnLevelGainPercent: Int = 0,
    val screenOffLevelGainPercent: Int = 0,
    val avgPowerWatts: Float? = null,
    val screenOnPowerWatts: Float? = null,
    val screenOffPowerWatts: Float? = null,
    val globalChargingRate: Float? = null,
    val screenOnChargingRate: Float? = null,
    val screenOffChargingRate: Float? = null,
    val maxPowerWatts: Float? = null,
    val maxTemperature: Float? = null,
    val batteryCapacityWh: Float? = null
) {
    /**
     * 将给定的毫秒时长格式化为易读的紧凑时间文本（如 "12d08h"、"05h30m"、"45m20s"、"15s"）。
     *
     * @param durationMs 待格式化的物理时长（单位：毫秒）
     * @return 格式化后的紧凑时间字符串
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "00s"
        val totalSecs = durationMs / 1000L
        val days = totalSecs / 86400L
        val hours = (totalSecs % 86400L) / 3600L
        val minutes = (totalSecs % 3600L) / 60L
        val seconds = totalSecs % 60L

        return when {
            days > 0L -> String.format(Locale.getDefault(), "%dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            minutes > 0L -> {
                if (seconds > 0L) String.format(Locale.getDefault(), "%02dm%02ds", minutes, seconds)
                else String.format(Locale.getDefault(), "%02dm", minutes)
            }
            else -> String.format(Locale.getDefault(), "%02ds", seconds)
        }
    }

    /**
     * 获取格式化后的总充电持续时长文本。
     *
     * @return 总充电时长字符串，无数据时返回 "--"
     */
    fun getFormattedTotalDuration(): String {
        return if (totalDurationMs > 0L) formatDuration(totalDurationMs) else "--"
    }

    /**
     * 获取格式化后的总亮屏充电时长文本。
     *
     * @return 亮屏充电时长字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOnDuration(): String {
        return if (screenOnDurationMs > 0L) formatDuration(screenOnDurationMs) else "--"
    }

    /**
     * 获取格式化后的总息屏充电时长文本。
     *
     * @return 息屏充电时长字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOffDuration(): String {
        return if (screenOffDurationMs > 0L) formatDuration(screenOffDurationMs) else "--"
    }

    /**
     * 获取格式化后的综合平均充电功率文本（如 "38.50 W"）。
     *
     * @return 综合平均充电功率字符串，无数据时返回 "--"
     */
    fun getFormattedAvgPower(): String {
        return avgPowerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: "--"
    }

    /**
     * 获取格式化后的亮屏平均充电功率文本（如 "25.30 W"）。
     *
     * @return 亮屏平均充电功率字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOnPower(): String {
        return screenOnPowerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: "--"
    }

    /**
     * 获取格式化后的息屏平均充电功率文本（如 "45.80 W"）。
     *
     * @return 息屏平均充电功率字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOffPower(): String {
        return screenOffPowerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: "--"
    }

    /**
     * 获取格式化后的综合充电速度文本（如 "65.2%/h"）。
     *
     * @return 综合充电速度字符串，无数据时返回 "--"
     */
    fun getFormattedGlobalChargingRate(): String {
        return globalChargingRate?.let { String.format(Locale.getDefault(), "%.1f%%/h", it) } ?: "--"
    }

    /**
     * 获取格式化后的亮屏充电速度文本（如 "48.0%/h"）。
     *
     * @return 亮屏充电速度字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOnChargingRate(): String {
        return screenOnChargingRate?.let { String.format(Locale.getDefault(), "%.1f%%/h", it) } ?: "--"
    }

    /**
     * 获取格式化后的息屏充电速度文本（如 "75.4%/h"）。
     *
     * @return 息屏充电速度字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOffChargingRate(): String {
        return screenOffChargingRate?.let { String.format(Locale.getDefault(), "%.1f%%/h", it) } ?: "--"
    }

    /**
     * 将充入能量（Wh）换算为等效物理电荷量（mAh）。
     * 依据物理公式 Q(mAh) = (E(Wh) * 1000) / V_nominal 真实折算，严禁虚构伪造。
     *
     * @param energyWh 待换算的物理能量（单位：瓦时 Wh）
     * @param nominalVoltageVolts 电池标称工作电压（单位：伏特 V，默认 3.85V）
     * @return 换算得到的电荷量（单位：毫安时 mAh），若能量非正数则返回 0f
     */
    fun convertEnergyWhToMah(
        energyWh: Double,
        nominalVoltageVolts: Float = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
    ): Float {
        val safeVolt = if (nominalVoltageVolts > 0f) nominalVoltageVolts else BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
        return if (energyWh > 0.0) ((energyWh * 1000.0) / safeVolt).toFloat() else 0f
    }

    companion object {

        /**
         * 严格基于物理守恒定律和真实充电历史快照记录，汇总计算充电历史统计指标（包含设备电池容量推导）。
         *
         * @param records 待统计的充电历史记录实体列表
         * @param context Android 上下文对象，用于获取电池标称容量
         * @return 计算构建的 [ChargingHistorySummary] 汇总统计实体
         */
        fun calculate(records: List<ChargingHistoryRecord>, context: Context): ChargingHistorySummary {
            if (records.isEmpty()) {
                return ChargingHistorySummary()
            }

            val totalEnergyInfo = PowerUsageManager.getInstance(context).getTotalEnergyInfo()
            val capacityWh: Float? = totalEnergyInfo?.totalWh ?: BatteryEnergyCalculator.calculateTotalEnergyWh(
                effectiveCapacityMah = PowerUsageManager.getInstance(context).getEffectiveDeviceCapacityMah(),
                nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
            )

            return calculate(records, capacityWh)
        }

        /**
         * 严格基于物理守恒定律和真实充电历史快照记录，汇总计算充电历史统计指标。
         *
         * @param records 待统计的充电历史记录实体列表
         * @param totalCapacityWh 设备满电总能量容量（单位：Wh，可选，用于基准参考）
         * @return 计算构建的 [ChargingHistorySummary] 汇总统计实体
         */
        fun calculate(records: List<ChargingHistoryRecord>, totalCapacityWh: Float? = null): ChargingHistorySummary {
            if (records.isEmpty()) {
                return ChargingHistorySummary(batteryCapacityWh = totalCapacityWh)
            }

            var sumTotalDur = 0L
            var sumOnDur = 0L
            var sumOffDur = 0L
            var sumTotalEnergy = 0.0
            var sumOnEnergy = 0.0
            var sumOffEnergy = 0.0
            var sumLevelGain = 0
            var sumOnLevelGain = 0
            var sumOffLevelGain = 0

            var maxPower: Float? = null
            var maxTemp: Float? = null

            for (record in records) {
                val gDur = record.durationMs.coerceAtLeast(0L)
                val offDur = record.screenOffDurationMs.coerceAtLeast(0L)
                val onDur = record.getScreenOnDurationMs().coerceAtLeast(0L)

                if (gDur > 0L) sumTotalDur += gDur
                if (onDur > 0L) sumOnDur += onDur
                if (offDur > 0L) sumOffDur += offDur

                // 全局能量累加：优先采用真实记录能量，缺失时按平均功率乘以时长换算
                val gEnergy = if (record.chargedEnergyWh > 0f) {
                    record.chargedEnergyWh.toDouble()
                } else if (record.avgPowerWatts > 0f && gDur > 0L) {
                    record.avgPowerWatts.toDouble() * (gDur.toDouble() / 3600000.0)
                } else {
                    0.0
                }
                sumTotalEnergy += gEnergy

                // 息屏能量累加
                val offEnergy = if (record.screenOffEnergyWh > 0f) {
                    record.screenOffEnergyWh.toDouble()
                } else {
                    0.0
                }
                sumOffEnergy += offEnergy

                // 亮屏能量累加（总能量减息屏能量）
                val onEnergy = (gEnergy - offEnergy).coerceAtLeast(0.0)
                sumOnEnergy += onEnergy

                // 累计电量增量
                if (record.levelGain > 0) {
                    sumLevelGain += record.levelGain
                }
                if (record.screenOffLevelGain > 0) {
                    sumOffLevelGain += record.screenOffLevelGain
                }
                val onGain = record.getScreenOnLevelGain()
                if (onGain > 0) {
                    sumOnLevelGain += onGain
                }

                // 统计峰值功率与最高温度
                if (record.maxPowerWatts > 0f) {
                    maxPower = maxOf(maxPower ?: 0f, record.maxPowerWatts)
                }
                if (record.maxTemperature > 0f) {
                    maxTemp = maxOf(maxTemp ?: 0f, record.maxTemperature)
                }
            }

            // 计算加权平均充电功率（W = Wh / Hours）
            val avgPower = if (sumTotalDur > 0L && sumTotalEnergy > 0.0) {
                (sumTotalEnergy / (sumTotalDur.toDouble() / 3600000.0)).toFloat()
            } else null

            val screenOnPower = if (sumOnDur > 0L && sumOnEnergy > 0.0) {
                (sumOnEnergy / (sumOnDur.toDouble() / 3600000.0)).toFloat()
            } else null

            val screenOffPower = if (sumOffDur > 0L && sumOffEnergy > 0.0) {
                (sumOffEnergy / (sumOffDur.toDouble() / 3600000.0)).toFloat()
            } else null

            // 计算充电速率（%/h = 电量增量百分比 / 小时数）
            val globalRate = if (sumTotalDur > 0L && sumLevelGain > 0) {
                (sumLevelGain.toDouble() / (sumTotalDur.toDouble() / 3600000.0)).toFloat()
            } else null

            val screenOnRate = if (sumOnDur > 0L && sumOnLevelGain > 0) {
                (sumOnLevelGain.toDouble() / (sumOnDur.toDouble() / 3600000.0)).toFloat()
            } else null

            val screenOffRate = if (sumOffDur > 0L && sumOffLevelGain > 0) {
                (sumOffLevelGain.toDouble() / (sumOffDur.toDouble() / 3600000.0)).toFloat()
            } else null

            return ChargingHistorySummary(
                recordCount = records.size,
                totalDurationMs = sumTotalDur,
                screenOnDurationMs = sumOnDur,
                screenOffDurationMs = sumOffDur,
                totalEnergyWh = sumTotalEnergy,
                screenOnEnergyWh = sumOnEnergy,
                screenOffEnergyWh = sumOffEnergy,
                totalLevelGainPercent = sumLevelGain,
                screenOnLevelGainPercent = sumOnLevelGain,
                screenOffLevelGainPercent = sumOffLevelGain,
                avgPowerWatts = avgPower,
                screenOnPowerWatts = screenOnPower,
                screenOffPowerWatts = screenOffPower,
                globalChargingRate = globalRate,
                screenOnChargingRate = screenOnRate,
                screenOffChargingRate = screenOffRate,
                maxPowerWatts = maxPower,
                maxTemperature = maxTemp,
                batteryCapacityWh = totalCapacityWh
            )
        }
    }
}
