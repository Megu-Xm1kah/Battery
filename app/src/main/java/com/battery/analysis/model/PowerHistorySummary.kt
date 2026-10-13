package com.battery.analysis.model

import android.content.Context
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.util.BatteryEnergyCalculator
import org.json.JSONArray
import java.util.Locale

/**
 * 耗电记录历史列表汇总统计指标实体类。
 * 忠实汇总指定放电历史记录集在亮屏、息屏与全局三个维度的实际物理总时长、消耗能量、平均放电功耗与放电速度。
 * 严禁捏造或虚拟伪造任何保底数据，严格根据真实硬件物理守恒定律进行加权推导。
 *
 * @property recordCount 统计纳入的耗电记录有效条数
 * @property totalDurationMs 全局总放电使用物理时长（单位：毫秒）
 * @property screenOnDurationMs 亮屏使用总物理时长（单位：毫秒）
 * @property screenOffDurationMs 息屏待机总物理时长（单位：毫秒）
 * @property totalEnergyWh 全局整机总消耗物理能量（单位：瓦时 Wh）
 * @property screenOnEnergyWh 亮屏使用总消耗物理能量（单位：瓦时 Wh）
 * @property screenOffEnergyWh 息屏待机总消耗物理能量（单位：瓦时 Wh）
 * @property totalLevelDropPercent 累计消耗的电量百分比总和（如 150 表示累计消耗了 150% 电量）
 * @property avgPowerWatts 综合平均放电功耗（单位：瓦特 W），无有效数据时为 null
 * @property screenOnPowerWatts 亮屏平均放电功耗（单位：瓦特 W），无有效数据时为 null
 * @property screenOffPowerWatts 息屏平均放电功耗（单位：瓦特 W），无有效数据时为 null
 * @property batteryCapacityWh 依据设备底层硬件或设计容量计算的满电总能量（单位：瓦时 Wh），无有效数据时为 null
 * @property globalDischargeRate 综合每小时放电速度百分比（单位：%/h），无有效数据时为 null
 * @property screenOnDischargeRate 亮屏每小时放电速度百分比（单位：%/h），无有效数据时为 null
 * @property screenOffDischargeRate 息屏每小时放电速度百分比（单位：%/h），无有效数据时为 null
 */
data class PowerHistorySummary(
    val recordCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val screenOnDurationMs: Long = 0L,
    val screenOffDurationMs: Long = 0L,
    val totalEnergyWh: Double = 0.0,
    val screenOnEnergyWh: Double = 0.0,
    val screenOffEnergyWh: Double = 0.0,
    val totalLevelDropPercent: Int = 0,
    val avgPowerWatts: Float? = null,
    val screenOnPowerWatts: Float? = null,
    val screenOffPowerWatts: Float? = null,
    val batteryCapacityWh: Float? = null,
    val globalDischargeRate: Float? = null,
    val screenOnDischargeRate: Float? = null,
    val screenOffDischargeRate: Float? = null
) {

    /**
     * 将给定的毫秒时长格式化为易读的紧凑文本（如 "02d05h"、"14h30m"、"45m20s" 或 "00s"）。
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
     * 获取格式化后的总使用时长文本。
     *
     * @return 总使用时长字符串，无数据时返回 "--"
     */
    fun getFormattedTotalDuration(): String {
        return if (totalDurationMs > 0L) formatDuration(totalDurationMs) else "--"
    }

    /**
     * 获取格式化后的总亮屏时长文本。
     *
     * @return 亮屏时长字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOnDuration(): String {
        return if (screenOnDurationMs > 0L) formatDuration(screenOnDurationMs) else "--"
    }

    /**
     * 获取格式化后的总息屏时长文本。
     *
     * @return 息屏时长字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOffDuration(): String {
        return if (screenOffDurationMs > 0L) formatDuration(screenOffDurationMs) else "--"
    }

    /**
     * 获取格式化后的综合平均放电功耗文本（如 "1.52 W"）。
     *
     * @return 综合平均功耗字符串，无数据时返回 "--"
     */
    fun getFormattedAvgPower(): String {
        return avgPowerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: "--"
    }

    /**
     * 获取格式化后的亮屏平均放电功耗文本（如 "2.15 W"）。
     *
     * @return 亮屏平均功耗字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOnPower(): String {
        return screenOnPowerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: "--"
    }

    /**
     * 获取格式化后的息屏平均放电功耗文本（如 "0.35 W"）。
     *
     * @return 息屏平均功耗字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOffPower(): String {
        return screenOffPowerWatts?.let { String.format(Locale.getDefault(), "%.2f W", it) } ?: "--"
    }

    /**
     * 获取格式化后的综合放电速度文本（如 "7.2%/h"）。
     *
     * @return 综合放电速度字符串，无数据时返回 "--"
     */
    fun getFormattedGlobalDischargeRate(): String {
        return globalDischargeRate?.let { String.format(Locale.getDefault(), "%.1f%%/h", it) } ?: "--"
    }

    /**
     * 获取格式化后的亮屏放电速度文本（如 "10.5%/h"）。
     *
     * @return 亮屏放电速度字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOnDischargeRate(): String {
        return screenOnDischargeRate?.let { String.format(Locale.getDefault(), "%.1f%%/h", it) } ?: "--"
    }

    /**
     * 获取格式化后的息屏放电速度文本（如 "1.8%/h"）。
     *
     * @return 息屏放电速度字符串，无数据时返回 "--"
     */
    fun getFormattedScreenOffDischargeRate(): String {
        return screenOffDischargeRate?.let { String.format(Locale.getDefault(), "%.1f%%/h", it) } ?: "--"
    }

    /**
     * 将能量（Wh）换算为等效物理电荷量（mAh）。
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
         * 严格基于物理守恒定律和真实历史快照记录，汇总计算耗电历史统计指标。
         *
         * @param records 待统计的耗电历史记录实体列表
         * @param totalCapacityWh 设备满电总能量容量（单位：Wh，可选，用于推导放电速度）
         * @return 计算构建的 [PowerHistorySummary] 汇总统计实体
         */
        fun calculate(records: List<PowerUsageRecord>, totalCapacityWh: Float? = null): PowerHistorySummary {
            if (records.isEmpty()) {
                return PowerHistorySummary()
            }

            var sumTotalDur = 0L
            var sumOnDur = 0L
            var sumOffDur = 0L
            var sumTotalEnergy = 0.0
            var sumOnEnergy = 0.0
            var sumOffEnergy = 0.0
            var sumLevelDrop = 0

            for (record in records) {
                val gDur = record.getDurationMs()
                val onDur = record.getScreenOnDurationMs()
                val offDur = record.getScreenOffDurationMs()

                if (gDur > 0L) sumTotalDur += gDur
                if (onDur > 0L) sumOnDur += onDur
                if (offDur > 0L) sumOffDur += offDur

                // 全局能量累加：优先采用真实记录能量，缺失时按平均功耗乘以时长换算
                val gEnergy = if (record.totalEnergyWh > 0f) {
                    record.totalEnergyWh.toDouble()
                } else if (record.avgPowerWatts > 0f && gDur > 0L) {
                    record.avgPowerWatts.toDouble() * (gDur.toDouble() / 3600000.0)
                } else {
                    0.0
                }
                sumTotalEnergy += gEnergy

                // 亮屏能量累加
                val onEnergy = if (record.screenOnEnergyWh > 0f) {
                    record.screenOnEnergyWh.toDouble()
                } else if (record.screenOnPowerWatts > 0f && onDur > 0L) {
                    record.screenOnPowerWatts.toDouble() * (onDur.toDouble() / 3600000.0)
                } else {
                    0.0
                }
                sumOnEnergy += onEnergy

                // 息屏能量累加
                val offEnergy = if (record.screenOffEnergyWh > 0f) {
                    record.screenOffEnergyWh.toDouble()
                } else if (record.screenOffPowerWatts > 0f && offDur > 0L) {
                    record.screenOffPowerWatts.toDouble() * (offDur.toDouble() / 3600000.0)
                } else {
                    0.0
                }
                sumOffEnergy += offEnergy

                // 计算单条记录电量掉电差额（使用实体类缓存的高性能初始电量，彻底避免重复解析大 JSON）
                val startLvl = record.getStartBatteryLevel()
                val drop = startLvl - record.levelPercent
                if (drop > 0) {
                    sumLevelDrop += drop
                }
            }

            // 计算加权平均放电功耗（W = Wh / Hours）
            val avgPower = if (sumTotalDur > 0L && sumTotalEnergy > 0.0) {
                (sumTotalEnergy / (sumTotalDur.toDouble() / 3600000.0)).toFloat()
            } else {
                null
            }

            val screenOnPower = if (sumOnDur > 0L && sumOnEnergy > 0.0) {
                (sumOnEnergy / (sumOnDur.toDouble() / 3600000.0)).toFloat()
            } else {
                null
            }

            val screenOffPower = if (sumOffDur > 0L && sumOffEnergy > 0.0) {
                (sumOffEnergy / (sumOffDur.toDouble() / 3600000.0)).toFloat()
            } else {
                null
            }

            // 根据物理公式推导每小时放电速度 Rate (%/h) = (Power (W) / Capacity (Wh)) * 100%
            val validCapacityWh = totalCapacityWh?.takeIf { it > 0f }

            val globalRate = if (validCapacityWh != null && avgPower != null && avgPower > 0f) {
                (avgPower / validCapacityWh) * 100f
            } else {
                null
            }

            val screenOnRate = if (validCapacityWh != null && screenOnPower != null && screenOnPower > 0f) {
                (screenOnPower / validCapacityWh) * 100f
            } else {
                null
            }

            val screenOffRate = if (validCapacityWh != null && screenOffPower != null && screenOffPower > 0f) {
                (screenOffPower / validCapacityWh) * 100f
            } else {
                null
            }

            return PowerHistorySummary(
                recordCount = records.size,
                totalDurationMs = sumTotalDur,
                screenOnDurationMs = sumOnDur,
                screenOffDurationMs = sumOffDur,
                totalEnergyWh = sumTotalEnergy,
                screenOnEnergyWh = sumOnEnergy,
                screenOffEnergyWh = sumOffEnergy,
                totalLevelDropPercent = sumLevelDrop,
                avgPowerWatts = avgPower,
                screenOnPowerWatts = screenOnPower,
                screenOffPowerWatts = screenOffPower,
                batteryCapacityWh = validCapacityWh,
                globalDischargeRate = globalRate,
                screenOnDischargeRate = screenOnRate,
                screenOffDischargeRate = screenOffRate
            )
        }

        /**
         * 严格基于物理守恒定律和真实历史快照记录，汇总计算耗电历史统计指标。
         * 自动从应用程序上下文中解析读取底层设备真实满充能量与电池标称电压。
         *
         * @param records 待统计的耗电历史记录实体列表
         * @param context 应用程序上下文，用于获取设备真实满充能量与电池标称电压
         * @return 计算构建的 [PowerHistorySummary] 汇总统计实体
         */
        fun calculate(records: List<PowerUsageRecord>, context: Context): PowerHistorySummary {
            if (records.isEmpty()) {
                return PowerHistorySummary()
            }

            // 读取底层设备真实总容量能量（Wh）
            val totalEnergyInfo = PowerUsageManager.getInstance(context).getTotalEnergyInfo()
            val capacityWh = totalEnergyInfo?.totalWh ?: BatteryEnergyCalculator.calculateTotalEnergyWh(
                effectiveCapacityMah = PowerUsageManager.getInstance(context).getEffectiveDeviceCapacityMah(),
                nominalVoltageVolts = BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
            )

            return calculate(records, capacityWh)
        }
    }
}
