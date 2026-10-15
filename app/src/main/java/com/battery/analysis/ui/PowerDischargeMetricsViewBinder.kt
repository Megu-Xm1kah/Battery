package com.battery.analysis.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.text.HtmlCompat
import com.battery.analysis.R
import com.battery.analysis.databinding.ItemPowerDischargeSpeedBinding
import com.battery.analysis.databinding.ItemPowerSleepAwakeMetricsBinding
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.manager.PowerUsageManager
import com.battery.analysis.model.PowerDisplayUnit
import com.battery.analysis.model.SevenDaysDischargeStats
import com.battery.analysis.util.BatteryEnergyCalculator
import java.util.Locale

/**
 * 放电速度与息屏休眠指标卡片通用视图绑定辅助工具类。
 * 负责统一格式化放电速度矩阵（亮屏 / 息屏 / 全局）与息屏状态矩阵（息屏 / 唤醒 / 深度睡眠）的核心物理数据，
 * 并支持卡片轻触点击注解弹框，确保主页面与快照详情页展示规范、视觉层级与交互行为高度统一。
 */
object PowerDischargeMetricsViewBinder {

    /**
     * 息屏卡片分类标识。
     */
    const val CARD_SCREEN_OFF = 1

    /**
     * 唤醒卡片分类标识。
     */
    const val CARD_AWAKE = 2

    /**
     * 深度睡眠卡片分类标识。
     */
    const val CARD_DEEP_SLEEP = 3

    /**
     * 将放电速度矩阵（亮屏、息屏、全局）数据绑定至对应的视图组件并配置点击回调。
     *
     * @param binding 放电速度布局绑定对象 [ItemPowerDischargeSpeedBinding]
     * @param overview 核心功耗指标概览数据实体 [PowerOverviewStats]，若无数据传入 null
     * @param totalCapacityWh 设备满电总能量（单位：Wh），若不可用传入 null
     * @param sevenDaysStats 累积 7 天统计放电速度数据实体 [SevenDaysDischargeStats]，若无数据传入 null
     * @param onCardClick 卡片点击事件监听回调，参数依次为被点击卡片视图与行分类标识（[BatteryEnergyCalculator.ROW_SCREEN_ON] 等）
     */
    fun bindDischargeSpeed(
        binding: ItemPowerDischargeSpeedBinding,
        overview: PowerOverviewStats?,
        totalCapacityWh: Float?,
        sevenDaysStats: SevenDaysDischargeStats?,
        onCardClick: ((anchorView: View, rowType: Int) -> Unit)? = null
    ) {
        val context = binding.root.context
        with(binding) {
            // --- 1. 第一行：当前放电速度百分比速率 ---
            if (overview == null) {
                tvScreenOnSpeed.text = "--"
                tvScreenOffSpeed.text = "--"
                tvGlobalSpeed.text = "--"
            } else {
                val totalDurationMs = if (overview.totalDurationMs > 0L) overview.totalDurationMs else parseDurationTextToMs(overview.totalDurationText)
                val onDurationMs = if (overview.screenOnDurationMs > 0L) overview.screenOnDurationMs else parseDurationTextToMs(overview.screenOnDurationText)
                val offDurationMs = if (overview.screenOffDurationMs > 0L) overview.screenOffDurationMs else parseDurationTextToMs(overview.screenOffDurationText)

                val onRate = calculateDischargeRatePercentPerHour(overview.screenOnPowerWatts, totalCapacityWh, onDurationMs)
                val offRate = calculateDischargeRatePercentPerHour(overview.screenOffPowerWatts, totalCapacityWh, offDurationMs)
                val globalRate = calculateDischargeRatePercentPerHour(overview.avgPowerWatts, totalCapacityWh, totalDurationMs)

                tvScreenOnSpeed.text = formatDischargeRateSpannable(onRate)
                tvScreenOffSpeed.text = formatDischargeRateSpannable(offRate)
                tvGlobalSpeed.text = formatDischargeRateSpannable(globalRate)
            }

            // --- 2. 第二行：实际统计放电速度与时长 ---
            tvScreenOn7daysSpeed.text = formatSecondaryDischargeRateSpannable(
                context = context,
                rate = sevenDaysStats?.screenOnDischargeRatePercentPerHour,
                durationMs = sevenDaysStats?.screenOnDurationMs
            )
            tvScreenOff7daysSpeed.text = formatSecondaryDischargeRateSpannable(
                context = context,
                rate = sevenDaysStats?.screenOffDischargeRatePercentPerHour,
                durationMs = sevenDaysStats?.screenOffDurationMs
            )
            tvGlobal7daysSpeed.text = formatSecondaryDischargeRateSpannable(
                context = context,
                rate = sevenDaysStats?.globalDischargeRatePercentPerHour,
                durationMs = sevenDaysStats?.globalDurationMs
            )

            // --- 3. 第三行：充满电使用时间 ---
            tvScreenOnFullDuration.text = formatFullDurationTextWithSuffix(
                context = context,
                ms = sevenDaysStats?.fullChargeScreenOnDurationMs
            )
            tvScreenOffFullDuration.text = formatFullDurationTextWithSuffix(
                context = context,
                ms = sevenDaysStats?.fullChargeScreenOffDurationMs
            )
            tvGlobalFullDuration.text = formatFullDurationTextWithSuffix(
                context = context,
                ms = sevenDaysStats?.fullChargeGlobalDurationMs
            )

            // 卡片点击事件监听
            if (onCardClick != null) {
                cardScreenOnSpeed.setOnClickListener {
                    onCardClick(cardScreenOnSpeed, BatteryEnergyCalculator.ROW_SCREEN_ON)
                }
                cardScreenOffSpeed.setOnClickListener {
                    onCardClick(cardScreenOffSpeed, BatteryEnergyCalculator.ROW_SCREEN_OFF)
                }
                cardGlobalSpeed.setOnClickListener {
                    onCardClick(cardGlobalSpeed, BatteryEnergyCalculator.ROW_GLOBAL)
                }
            }
        }
    }

    /**
     * 将息屏、唤醒与深度睡眠三状态指标卡片数据绑定至对应的视图组件并配置点击回调。
     * 支持自适应根据传入的电量单位 [unit] 智能调整第二行（时长与主指标）与第三行（对照副指标与功率）的主次排版。
     *
     * @param binding 息屏细分状态布局绑定对象 [ItemPowerSleepAwakeMetricsBinding]
     * @param overview 核心功耗指标概览数据实体 [PowerOverviewStats]，若无数据传入 null
     * @param unit 当前电量呈现单位 [PowerDisplayUnit]（默认为 [PowerDisplayUnit.WH]）
     * @param onCardClick 卡片点击事件监听回调，参数依次为被点击卡片视图与卡片分类标识（[CARD_SCREEN_OFF] 等）
     */
    fun bindSleepAwakeMetrics(
        binding: ItemPowerSleepAwakeMetricsBinding,
        overview: PowerOverviewStats?,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH,
        onCardClick: ((anchorView: View, cardType: Int) -> Unit)? = null
    ) {
        val noRecordText = binding.root.context.getString(R.string.power_metric_no_record)
        val isMahUnit = unit == PowerDisplayUnit.MAH
        with(binding) {
            if (overview == null) {
                tvScreenOffPercent.text = "--"
                tvScreenOffSubtitle.text = noRecordText
                tvScreenOffMah.text = "--"
                tvAwakePercent.text = "--"
                tvAwakeSubtitle.text = noRecordText
                tvAwakeMah.text = "--"
                tvSleepPercent.text = "--"
                tvSleepSubtitle.text = noRecordText
                tvSleepMah.text = "--"
                return
            }

            // 1. 息屏卡片
            if (overview.screenOffDurationMs <= 0L && overview.screenOffEnergyWh <= 0f) {
                tvScreenOffPercent.text = "--"
                tvScreenOffSubtitle.text = noRecordText
                tvScreenOffMah.text = "--"
            } else {
                val offPercent = if (overview.screenOffPercent > 0f) {
                    overview.screenOffPercent
                } else {
                    overview.screenOffAwakePercent + overview.screenOffDeepSleepPercent
                }
                tvScreenOffPercent.text = if (offPercent > 0f) String.format(Locale.US, "%.1f%%", offPercent) else "--"

                val durText = formatSleepAwakeCardDuration(
                    durationMs = if (overview.screenOffDurationMs > 0L) overview.screenOffDurationMs else parseDurationTextToMs(overview.screenOffDurationText),
                    fallbackText = overview.screenOffDurationText
                )
                val offDrainMah = if (overview.screenOffDrainMah > 0f) {
                    overview.screenOffDrainMah
                } else if (overview.screenOffEnergyWh > 0f) {
                    (overview.screenOffEnergyWh * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                } else {
                    0f
                }
                val offPowerWatts = if (overview.screenOffPowerWatts > 0f) {
                    overview.screenOffPowerWatts
                } else if (overview.screenOffDurationMs > 0L && overview.screenOffEnergyWh > 0f) {
                    (overview.screenOffEnergyWh / (overview.screenOffDurationMs.toDouble() / 3600000.0)).toFloat()
                } else {
                    0f
                }
                val offPowerText = if (overview.screenOffDurationMs > 0L || overview.screenOffEnergyWh > 0f) {
                    formatCardPowerWatts(offPowerWatts)
                } else {
                    null
                }

                if (isMahUnit) {
                    val mahSubtitle = if (offDrainMah > 0f) String.format(Locale.US, "%.1fmAh", offDrainMah) else "--"
                    tvScreenOffSubtitle.text = "$durText $mahSubtitle"
                    val energyDisplay = String.format(Locale.US, "%.3f Wh", overview.screenOffEnergyWh)
                    tvScreenOffMah.text = if (offPowerText != null) "$energyDisplay $offPowerText" else energyDisplay
                } else {
                    val energyText = String.format(Locale.US, "%.3fWh", overview.screenOffEnergyWh)
                    tvScreenOffSubtitle.text = "$durText $energyText"
                    if (offDrainMah > 0f) {
                        val mahStr = String.format(Locale.US, "%.1f mAh", offDrainMah)
                        tvScreenOffMah.text = if (offPowerText != null) "$mahStr $offPowerText" else mahStr
                    } else if (overview.screenOffDurationMs > 0L) {
                        val mahStr = "0.0 mAh"
                        tvScreenOffMah.text = if (offPowerText != null) "$mahStr $offPowerText" else mahStr
                    } else {
                        tvScreenOffMah.text = "--"
                    }
                }
            }

            // 2. 唤醒卡片
            if (overview.screenOffAwakeDurationMs <= 0L && overview.screenOffAwakeEnergyWh <= 0f) {
                if (overview.screenOffDurationMs > 0L) {
                    // 放电周期内经历过息屏，但唤醒时长与能耗均为 0（处于 100% 纯深度睡眠挂起状态）
                    tvAwakePercent.text = "0.0%"
                    if (isMahUnit) {
                        val subtitleHtml = "<font color=\"#FFA4A4\">00s</font> <font color=\"#FFA4A4\">0.0mAh</font>"
                        tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvAwakeMah.text = HtmlCompat.fromHtml("<font color=\"#FFA4A4\">0.000 Wh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        val subtitleHtml = "<font color=\"#FFA4A4\">00s</font> <font color=\"#FFA4A4\">0.000Wh</font>"
                        tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvAwakeMah.text = HtmlCompat.fromHtml("<font color=\"#FFA4A4\">0.0 mAh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    }
                } else {
                    tvAwakePercent.text = "--"
                    tvAwakeSubtitle.text = noRecordText
                    tvAwakeMah.text = "--"
                }
            } else {
                val awakePercentStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakePercent > 0f) {
                    String.format(Locale.US, "%.1f%%", overview.screenOffAwakePercent)
                } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                    "<0.1%"
                } else if (overview.screenOffAwakeDurationMs > 0L) {
                    "0.0%"
                } else {
                    "--"
                }
                tvAwakePercent.text = awakePercentStr

                val durText = formatSleepAwakeCardDuration(
                    durationMs = if (overview.screenOffAwakeDurationMs > 0L) overview.screenOffAwakeDurationMs else parseDurationTextToMs(overview.screenOffAwakeDurationText),
                    fallbackText = overview.screenOffAwakeDurationText
                )

                val awakeDrainMah = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeDrainMah > 0f) {
                    overview.screenOffAwakeDrainMah
                } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                    (overview.screenOffAwakeEnergyWh * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                } else {
                    0f
                }
                val awakePowerWatts = if (overview.screenOffAwakePowerWatts > 0f) {
                    overview.screenOffAwakePowerWatts
                } else if (overview.screenOffAwakeDurationMs > 0L && overview.screenOffAwakeEnergyWh > 0f) {
                    (overview.screenOffAwakeEnergyWh / (overview.screenOffAwakeDurationMs.toDouble() / 3600000.0)).toFloat()
                } else {
                    0f
                }
                val awakePowerText = if (overview.screenOffAwakeDurationMs > 0L || overview.screenOffAwakeEnergyWh > 0f) {
                    formatCardPowerWatts(awakePowerWatts)
                } else {
                    null
                }

                if (isMahUnit) {
                    val awakeMahSubtitle = if (overview.isScreenOffDecomposedAvailable && awakeDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", awakeDrainMah)
                    } else if (overview.isScreenOffDecomposedAvailable && awakeDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    val subtitleHtml = "<font color=\"#FFA4A4\">$durText</font> <font color=\"#FFA4A4\">$awakeMahSubtitle</font>"
                    tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)

                    val energyDisplay = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh >= 0.001f) {
                        String.format(Locale.US, "%.3f Wh", overview.screenOffAwakeEnergyWh)
                    } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                        "<0.001 Wh"
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.000 Wh"
                    } else {
                        "--"
                    }
                    val fullText = if (awakePowerText != null) "$energyDisplay $awakePowerText" else energyDisplay
                    val mahHtml = "<font color=\"#FFA4A4\">$fullText</font>"
                    tvAwakeMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                } else {
                    val energyText = if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh >= 0.001f) {
                        String.format(Locale.US, "%.3fWh", overview.screenOffAwakeEnergyWh)
                    } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffAwakeEnergyWh > 0f) {
                        "<0.001Wh"
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        "0.000Wh"
                    } else {
                        "--"
                    }
                    val subtitleHtml = "<font color=\"#FFA4A4\">$durText</font> <font color=\"#FFA4A4\">$energyText</font>"
                    tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)

                    if (awakeDrainMah >= 0.1f) {
                        val mahText = String.format(Locale.US, "%.1f mAh", awakeDrainMah)
                        val fullText = if (awakePowerText != null) "$mahText $awakePowerText" else mahText
                        val mahHtml = "<font color=\"#FFA4A4\">$fullText</font>"
                        tvAwakeMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else if (overview.screenOffAwakeDurationMs > 0L) {
                        val fullText = if (awakePowerText != null) "0.0 mAh $awakePowerText" else "0.0 mAh"
                        val mahHtml = "<font color=\"#FFA4A4\">$fullText</font>"
                        tvAwakeMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        tvAwakeMah.text = "--"
                    }
                }
            }

            // 3. 深度睡眠卡片
            if (overview.screenOffDeepSleepDurationMs <= 0L && overview.screenOffDeepSleepEnergyWh <= 0f) {
                if (overview.screenOffDurationMs > 0L) {
                    // 放电周期内经历过息屏，但深度睡眠时长为 0（息屏期间 CPU 全程被持有唤醒锁活跃工作）
                    tvSleepPercent.text = "0.0%"
                    if (isMahUnit) {
                        val subtitleHtml = "<font color=\"#B71C1C\">00s</font> <font color=\"#B71C1C\">0.0mAh</font>"
                        tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvSleepMah.text = HtmlCompat.fromHtml("<font color=\"#B71C1C\">0.000 Wh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        val subtitleHtml = "<font color=\"#B71C1C\">00s</font> <font color=\"#B71C1C\">0.000Wh</font>"
                        tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvSleepMah.text = HtmlCompat.fromHtml("<font color=\"#B71C1C\">0.0 mAh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    }
                } else {
                    tvSleepPercent.text = "--"
                    tvSleepSubtitle.text = noRecordText
                    tvSleepMah.text = "--"
                }
            } else {
                val sleepPercentStr = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", overview.screenOffDeepSleepPercent)
                } else {
                    "--"
                }
                tvSleepPercent.text = sleepPercentStr

                val durText = formatSleepAwakeCardDuration(
                    durationMs = if (overview.screenOffDeepSleepDurationMs > 0L) overview.screenOffDeepSleepDurationMs else parseDurationTextToMs(overview.screenOffDeepSleepDurationText),
                    fallbackText = overview.screenOffDeepSleepDurationText
                )

                val sleepDrainMah = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepDrainMah > 0f) {
                    overview.screenOffDeepSleepDrainMah
                } else if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
                    (overview.screenOffDeepSleepEnergyWh * 1000f) / BatteryEnergyCalculator.DEFAULT_NOMINAL_VOLTAGE_VOLTS
                } else {
                    0f
                }
                val deepSleepPowerWatts = if (overview.screenOffDeepSleepPowerWatts > 0f) {
                    overview.screenOffDeepSleepPowerWatts
                } else if (overview.screenOffDeepSleepDurationMs > 0L && overview.screenOffDeepSleepEnergyWh > 0f) {
                    (overview.screenOffDeepSleepEnergyWh / (overview.screenOffDeepSleepDurationMs.toDouble() / 3600000.0)).toFloat()
                } else {
                    0f
                }
                val sleepPowerText = if (overview.screenOffDeepSleepDurationMs > 0L || overview.screenOffDeepSleepEnergyWh > 0f) {
                    formatCardPowerWatts(deepSleepPowerWatts)
                } else {
                    null
                }

                if (isMahUnit) {
                    val sleepMahSubtitle = if (overview.isScreenOffDecomposedAvailable && sleepDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", sleepDrainMah)
                    } else if (overview.isScreenOffDecomposedAvailable && sleepDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (overview.screenOffDeepSleepDurationMs > 0L) {
                        "0.0mAh"
                    } else {
                        "--"
                    }
                    val subtitleHtml = "<font color=\"#B71C1C\">$durText</font> <font color=\"#B71C1C\">$sleepMahSubtitle</font>"
                    tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)

                    val energyDisplay = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
                        String.format(Locale.US, "%.3f Wh", overview.screenOffDeepSleepEnergyWh)
                    } else {
                        "--"
                    }
                    val fullText = if (sleepPowerText != null) "$energyDisplay $sleepPowerText" else energyDisplay
                    val mahHtml = "<font color=\"#B71C1C\">$fullText</font>"
                    tvSleepMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                } else {
                    val energyText = if (overview.isScreenOffDecomposedAvailable && overview.screenOffDeepSleepEnergyWh > 0f) {
                        String.format(Locale.US, "%.3fWh", overview.screenOffDeepSleepEnergyWh)
                    } else {
                        "--"
                    }
                    val subtitleHtml = "<font color=\"#B71C1C\">$durText</font> <font color=\"#B71C1C\">$energyText</font>"
                    tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)

                    if (sleepDrainMah > 0f) {
                        val mahText = String.format(Locale.US, "%.1f mAh", sleepDrainMah)
                        val fullText = if (sleepPowerText != null) "$mahText $sleepPowerText" else mahText
                        val mahHtml = "<font color=\"#B71C1C\">$fullText</font>"
                        tvSleepMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else if (overview.screenOffDeepSleepDurationMs > 0L) {
                        val fullText = if (sleepPowerText != null) "0.0 mAh $sleepPowerText" else "0.0 mAh"
                        val mahHtml = "<font color=\"#B71C1C\">$fullText</font>"
                        tvSleepMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        tvSleepMah.text = "--"
                    }
                }
            }

            // 绑定点击事件监听
            if (onCardClick != null) {
                cardScreenOff.setOnClickListener {
                    onCardClick(cardScreenOff, CARD_SCREEN_OFF)
                }
                cardAwake.setOnClickListener {
                    onCardClick(cardAwake, CARD_AWAKE)
                }
                cardDeepSleep.setOnClickListener {
                    onCardClick(cardDeepSleep, CARD_DEEP_SLEEP)
                }
            }
        }
    }

    /**
     * 根据放电平均功耗与电池总容量能量计算每小时放电百分比速率（%/h）。
     *
     * 遵循物理学定义：
     * 功率 P (W) = 能量 E (Wh) / 时间 t (h)；
     * 每小时放电百分比 Rate (%/h) = (P (W) / 电池总容量 E_total (Wh)) * 100%。
     * 严禁编写任何假数据或虚拟钳位，当功率或电池总能量无效时返回 null。
     *
     * @param powerWatts 放电平均功率（单位：W）
     * @param totalCapacityWh 电池标称/满充总能量容量（单位：Wh）
     * @param durationMs 对应时段实际持续时长（毫秒）
     * @return 计算得到的每小时放电百分比（单位：%/h），若数据缺失或非正数则返回 null
     */
    fun calculateDischargeRatePercentPerHour(
        powerWatts: Float,
        totalCapacityWh: Float?,
        durationMs: Long
    ): Float? {
        if (totalCapacityWh == null || totalCapacityWh <= 0f) {
            return null
        }
        if (powerWatts <= 0f || durationMs <= 0L) {
            return null
        }
        val rate = (powerWatts / totalCapacityWh) * 100f
        return if (rate > 0f) rate else null
    }

    /**
     * 将放电速度数值与单位转换为复合富文本，数值部分保持较大字号（16sp），
     * 单位部分（"%/h"）缩小至 11sp，以图文混排高质感样式呈现。
     *
     * @param rate 放电速率百分比数值（单位：%/h），若为空或非正数则返回 "--"
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatDischargeRateSpannable(rate: Float?): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numberStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val fullText = "$numberStr$unitStr"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            AbsoluteSizeSpan(11, true),
            numberStr.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * 将实际统计放电速度与有效时长组合格式化为复合富文本。
     * 放电百分比数值与单位的文字样式大小完全对齐第一行放电速度百分比（数值 16sp 加粗高亮突出，"%/h" 单位 11sp 加粗微缩），
     * 实际有效统计时长紧随其后（如 "5.2%/h 1d08h"），采用次要文本颜色与紧凑字号（10sp 常规字重）呈现。
     * 若数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象，用于解析系统主题次要文字颜色
     * @param rate 统计放电速率百分比（单位：%/h）
     * @param durationMs 7 天窗口内实际有效统计时长（单位：毫秒）
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatSecondaryDischargeRateSpannable(
        context: Context,
        rate: Float?,
        durationMs: Long?
    ): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numberStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val durStr = formatActualStatsDurationText(durationMs)
        val fullText = if (durStr.isNotBlank()) "$numberStr$unitStr $durStr" else "$numberStr$unitStr"
        val spannable = SpannableString(fullText)

        // "%/h" 单位字号微缩至 11sp，与第一行放电速度单位样式及大小（11sp bold）完全一致
        spannable.setSpan(
            AbsoluteSizeSpan(11, true),
            numberStr.length,
            numberStr.length + unitStr.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 时长部分保持次要紧凑字号（10sp）、常规字重与主题次要文本颜色
        if (durStr.isNotBlank()) {
            val durStart = numberStr.length + unitStr.length + 1
            val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            val secondaryColor = typedArray.getColor(0, 0x8A000000.toInt())
            typedArray.recycle()

            spannable.setSpan(
                AbsoluteSizeSpan(10, true),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.NORMAL),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(secondaryColor),
                durStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /**
     * 将充满电可用时长与满电说明标签组合格式化为紧凑富文本。
     * 时长数值位于前面（如 "19h14m"），满电说明标签位于后面（如 "19h14m 满电续航"）。
     * 若时长无效或数据缺失则展示 "--"。
     *
     * @param context Android 上下文对象
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的富文本对象 [CharSequence]
     */
    fun formatFullDurationTextWithSuffix(context: Context, ms: Long?): CharSequence {
        if (ms == null || ms <= 0L) {
            return "--"
        }
        val durText = formatFullDurationText(ms)
        if (durText == "--") return "--"
        val label = context.getString(R.string.power_discharge_full_label)
        val fullText = "$durText $label"
        val spannable = SpannableString(fullText)

        // 时长数值加粗突出
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            durText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // 满电标签字号微缩至 10sp
        val labelStart = durText.length + 1
        spannable.setSpan(
            AbsoluteSizeSpan(10, true),
            labelStart,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * 将满电使用时间物理毫秒数格式化为紧凑易读的时长文本。
     * 当持续时间大于等于 1 天时输出为 "XdXXh" 格式（如 05d05h）；
     * 当持续时间大于等于 1 小时时输出为 "XhXXm" 格式（如 19h14m）；
     * 否则输出为 "Xm" 格式（如 45m）。
     *
     * @param ms 充满电可用物理时长毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回 "--"
     */
    fun formatFullDurationText(ms: Long): String {
        if (ms <= 0L) return "--"
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes)
        }
    }

    /**
     * 将 7 天滑动窗口内实际有效统计时长（毫秒）格式化为紧凑时长文本。
     * 当持续时间大于等于 7 天时显示为 "7d"；
     * 当持续时间大于等于 1 天时显示为 "XdXXh" 格式（如 01d08h、05d19h）；
     * 当持续时间大于等于 1 小时时显示为 "XhXXm" 格式（如 04h30m、18h20m）；
     * 否则显示为 "Xm" 格式（如 25m、05m）。
     *
     * @param ms 实际有效统计时长物理毫秒数
     * @return 格式化后的紧凑时长字符串，数据无效时返回空字符串
     */
    fun formatActualStatsDurationText(ms: Long?): String {
        if (ms == null || ms <= 0L) return ""
        val totalSec = ms / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            days >= 7L -> "7d"
            days > 0L -> String.format(Locale.getDefault(), "%02dd%02dh", days, hours)
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            else -> String.format(Locale.getDefault(), "%02dm", minutes.coerceAtLeast(1L))
        }
    }

    /**
     * 格式化卡片平均放电功耗数值为紧凑单位文本。
     * 遵循物理客观真值，当平均功耗大于等于 0.01W 时保留两位小数（如 "0.25W"、"2.05W"）；
     * 当平均功耗在 (0W, 0.01W) 微弱区间时保留三位小数（如 "0.008W"），杜绝四舍五入为 0.00W；
     * 当功耗等于 0W 时展示为 "0.00W"；数据缺失时返回 null。
     *
     * @param watts 物理实测或能量积分推导的平均放电功率（单位：W）
     * @return 格式化后的紧凑功耗字符串（如 "0.25W"），数值无效时返回 null
     */
    fun formatCardPowerWatts(watts: Float?): String? {
        if (watts == null || watts < 0f || watts.isNaN()) return null
        return if (watts >= 0.01f) {
            String.format(Locale.US, "%.2fW", watts)
        } else if (watts > 0f) {
            String.format(Locale.US, "%.3fW", watts)
        } else {
            "0.00W"
        }
    }

    /**
     * 将包含中英文字符或冒号格式的时长文本解析换算为对应的物理毫秒数。
     *
     * @param text 待解析的时长文本内容
     * @return 解析得出的时长毫秒数值，若为空或无法解析返回 0L
     */
    fun parseDurationTextToMs(text: String): Long {
        if (text.isBlank() || text == "--") return 0L
        var totalMs = 0L
        try {
            val dMatch = Regex("(\\d+)\\s*(?:天|d)").find(text)
            val hMatch = Regex("(\\d+)\\s*(?:小时|h)").find(text)
            val mMatch = Regex("(\\d+)\\s*(?:分|m)").find(text)
            val sMatch = Regex("(\\d+)\\s*(?:秒|s)").find(text)

            if (dMatch != null || hMatch != null || mMatch != null || sMatch != null) {
                dMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 86400000L }
                hMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 3600000L }
                mMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 60000L }
                sMatch?.groupValues?.get(1)?.toLongOrNull()?.let { totalMs += it * 1000L }
                return totalMs
            }

            val colonMatch = Regex("(\\d+):(\\d+)(?::(\\d+))?").find(text)
            if (colonMatch != null) {
                val parts = colonMatch.destructured
                if (parts.component3().isNotEmpty()) {
                    val h = parts.component1().toLongOrNull() ?: 0L
                    val m = parts.component2().toLongOrNull() ?: 0L
                    val s = parts.component3().toLongOrNull() ?: 0L
                    return (h * 3600L + m * 60L + s) * 1000L
                } else {
                    val m = parts.component1().toLongOrNull() ?: 0L
                    val s = parts.component2().toLongOrNull() ?: 0L
                    return (m * 60L + s) * 1000L
                }
            }
        } catch (_: Exception) {}
        return totalMs
    }

    /**
     * 将息屏、唤醒与深度睡眠三状态指标卡片的持续时长毫秒数格式化为紧凑易读的时长文本。
     * 遵循业务呈现规范：
     * 1. 若持续时间超过 1 小时（hours > 0），则不显示秒数，输出时分格式（如 "06h53m"）；
     * 2. 否则（不足 1 小时，hours == 0），则显示秒数：
     *    - 持续时间大于等于 1 分钟时输出分秒格式（如 "19m01s"）；
     *    - 持续时间小于 1 分钟时输出秒数格式（如 "45s"、"00s"）。
     *
     * @param durationMs 物理持续时长毫秒数值
     * @param fallbackText 当时长毫秒数值小于等于 0 时的备用文本（若非空则优先解析物理毫秒值，无法解析时作为兜底展示）
     * @return 格式化后的紧凑卡片时长字符串
     */
    fun formatSleepAwakeCardDuration(durationMs: Long, fallbackText: String = ""): String {
        val effectiveMs = if (durationMs > 0L) {
            durationMs
        } else if (fallbackText.isNotBlank()) {
            parseDurationTextToMs(fallbackText)
        } else {
            0L
        }

        if (effectiveMs <= 0L) {
            return if (fallbackText.isNotBlank() && fallbackText != "0s" && fallbackText != "00s") {
                fallbackText
            } else {
                "00s"
            }
        }

        val totalSec = effectiveMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L

        return when {
            hours > 0L -> String.format(Locale.getDefault(), "%02dh%02dm", hours, minutes)
            minutes > 0L -> String.format(Locale.getDefault(), "%02dm%02ds", minutes, seconds)
            else -> String.format(Locale.getDefault(), "%02ds", seconds)
        }
    }

    /**
     * 响应时间轴游标滑动事件，以极轻量无重排方式高频局部刷新放电速度与息屏睡眠卡片。
     * 仅执行文本级直接更新，不重新调用布局度量，零 GC、零测量抖动。
     * 支持根据当前所选电量单位（Wh 或 mAh）自适应切换息屏、唤醒与深度睡眠卡片的第一行主显单位。
     *
     * @param speedBinding 放电速度布局绑定对象 [ItemPowerDischargeSpeedBinding]
     * @param sleepBinding 息屏细分状态布局绑定对象 [ItemPowerSleepAwakeMetricsBinding]
     * @param subStats 阶段子区间累计统计快照对象 [com.battery.analysis.timeline.util.SubIntervalOverviewStats]
     * @param unit 当前电量显示单位 [PowerDisplayUnit]，默认为 [PowerDisplayUnit.WH]
     */
    fun updateCursorInspectionStats(
        speedBinding: ItemPowerDischargeSpeedBinding,
        sleepBinding: ItemPowerSleepAwakeMetricsBinding,
        subStats: com.battery.analysis.timeline.util.SubIntervalOverviewStats,
        unit: PowerDisplayUnit = PowerDisplayUnit.WH
    ) {
        val isMahUnit = (unit == PowerDisplayUnit.MAH)

        // 1. 刷新放电速度概览卡片第一行（当前阶段放电速度速率 %/h）
        with(speedBinding) {
            tvScreenOnSpeed.text = formatDischargeRateSpannable(subStats.screenOnDischargeRate)
            tvScreenOffSpeed.text = formatDischargeRateSpannable(subStats.screenOffDischargeRate)
            tvGlobalSpeed.text = formatDischargeRateSpannable(subStats.globalDischargeRate)
        }

        // 2. 刷新息屏、唤醒与深度睡眠三卡片指标
        val noRecordText = sleepBinding.root.context.getString(R.string.power_metric_no_record)
        with(sleepBinding) {
            // 2.1 息屏卡片
            if (subStats.screenOffDurationMs <= 0L && subStats.screenOffEnergyWh <= 0f) {
                tvScreenOffPercent.text = "--"
                tvScreenOffSubtitle.text = noRecordText
                tvScreenOffMah.text = "--"
            } else {
                tvScreenOffPercent.text = if (subStats.screenOffPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", subStats.screenOffPercent)
                } else "--"

                val durText = formatSleepAwakeCardDuration(subStats.screenOffDurationMs)
                val energyText = String.format(Locale.US, "%.3fWh", subStats.screenOffEnergyWh)
                val offPowerText = formatCardPowerWatts(subStats.screenOffPowerWatts)
                val mahStr = String.format(Locale.US, "%.1f mAh", subStats.screenOffDrainMah)

                if (isMahUnit) {
                    val mahSubtitle = if (subStats.screenOffDrainMah > 0f) String.format(Locale.US, "%.1fmAh", subStats.screenOffDrainMah) else "--"
                    val energyDisplay = String.format(Locale.US, "%.3f Wh", subStats.screenOffEnergyWh)
                    tvScreenOffSubtitle.text = "$durText $mahSubtitle"
                    tvScreenOffMah.text = if (offPowerText != null) "$energyDisplay $offPowerText" else energyDisplay
                } else {
                    tvScreenOffSubtitle.text = "$durText $energyText"
                    tvScreenOffMah.text = if (offPowerText != null) "$mahStr $offPowerText" else mahStr
                }
            }

            // 2.2 唤醒卡片
            if (subStats.screenOffAwakeDurationMs <= 0L && subStats.screenOffAwakeEnergyWh <= 0f) {
                if (subStats.screenOffDurationMs > 0L) {
                    tvAwakePercent.text = "0.0%"
                    if (isMahUnit) {
                        val subtitleHtml = "<font color=\"#FFA4A4\">00s</font> <font color=\"#FFA4A4\">0.0mAh</font>"
                        tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvAwakeMah.text = HtmlCompat.fromHtml("<font color=\"#FFA4A4\">0.000 Wh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        val subtitleHtml = "<font color=\"#FFA4A4\">00s</font> <font color=\"#FFA4A4\">0.000Wh</font>"
                        tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvAwakeMah.text = HtmlCompat.fromHtml("<font color=\"#FFA4A4\">0.0 mAh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    }
                } else {
                    tvAwakePercent.text = "--"
                    tvAwakeSubtitle.text = noRecordText
                    tvAwakeMah.text = "--"
                }
            } else {
                val awakePercentStr = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakePercent > 0f) {
                    String.format(Locale.US, "%.1f%%", subStats.screenOffAwakePercent)
                } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh > 0f) {
                    "<0.1%"
                } else if (subStats.screenOffAwakeDurationMs > 0L) {
                    "0.0%"
                } else "--"
                tvAwakePercent.text = awakePercentStr

                val durText = formatSleepAwakeCardDuration(subStats.screenOffAwakeDurationMs)
                val energyText = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh >= 0.001f) {
                    String.format(Locale.US, "%.3fWh", subStats.screenOffAwakeEnergyWh)
                } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh > 0f) {
                    "<0.001Wh"
                } else "0.000Wh"

                val awakePowerText = formatCardPowerWatts(subStats.screenOffAwakePowerWatts)
                val mahStr = String.format(Locale.US, "%.1f mAh", subStats.screenOffAwakeDrainMah)

                if (isMahUnit) {
                    val awakeMahSubtitle = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", subStats.screenOffAwakeDrainMah)
                    } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (subStats.screenOffAwakeDurationMs > 0L) {
                        "0.0mAh"
                    } else "--"
                    val energyDisplay = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh >= 0.001f) {
                        String.format(Locale.US, "%.3f Wh", subStats.screenOffAwakeEnergyWh)
                    } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffAwakeEnergyWh > 0f) {
                        "<0.001 Wh"
                    } else if (subStats.screenOffAwakeDurationMs > 0L) {
                        "0.000 Wh"
                    } else "--"
                    val subtitleHtml = "<font color=\"#FFA4A4\">$durText</font> <font color=\"#FFA4A4\">$awakeMahSubtitle</font>"
                    tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    val fullText = if (awakePowerText != null) "$energyDisplay $awakePowerText" else energyDisplay
                    val mahHtml = "<font color=\"#FFA4A4\">$fullText</font>"
                    tvAwakeMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                } else {
                    val subtitleHtml = "<font color=\"#FFA4A4\">$durText</font> <font color=\"#FFA4A4\">$energyText</font>"
                    tvAwakeSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    val fullText = if (awakePowerText != null) "$mahStr $awakePowerText" else mahStr
                    val mahHtml = "<font color=\"#FFA4A4\">$fullText</font>"
                    tvAwakeMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                }
            }

            // 2.3 深度睡眠卡片
            if (subStats.screenOffDeepSleepDurationMs <= 0L && subStats.screenOffDeepSleepEnergyWh <= 0f) {
                if (subStats.screenOffDurationMs > 0L) {
                    tvSleepPercent.text = "0.0%"
                    if (isMahUnit) {
                        val subtitleHtml = "<font color=\"#B71C1C\">00s</font> <font color=\"#B71C1C\">0.0mAh</font>"
                        tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvSleepMah.text = HtmlCompat.fromHtml("<font color=\"#B71C1C\">0.000 Wh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    } else {
                        val subtitleHtml = "<font color=\"#B71C1C\">00s</font> <font color=\"#B71C1C\">0.000Wh</font>"
                        tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        tvSleepMah.text = HtmlCompat.fromHtml("<font color=\"#B71C1C\">0.0 mAh 0.00W</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                    }
                } else {
                    tvSleepPercent.text = "--"
                    tvSleepSubtitle.text = noRecordText
                    tvSleepMah.text = "--"
                }
            } else {
                val sleepPercentStr = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepPercent > 0f) {
                    String.format(Locale.US, "%.1f%%", subStats.screenOffDeepSleepPercent)
                } else "--"
                tvSleepPercent.text = sleepPercentStr

                val durText = formatSleepAwakeCardDuration(subStats.screenOffDeepSleepDurationMs)
                val energyText = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepEnergyWh > 0f) {
                    String.format(Locale.US, "%.3fWh", subStats.screenOffDeepSleepEnergyWh)
                } else "--"

                val sleepPowerText = formatCardPowerWatts(subStats.screenOffDeepSleepPowerWatts)
                val mahStr = String.format(Locale.US, "%.1f mAh", subStats.screenOffDeepSleepDrainMah)

                if (isMahUnit) {
                    val sleepMahSubtitle = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepDrainMah >= 0.1f) {
                        String.format(Locale.US, "%.1fmAh", subStats.screenOffDeepSleepDrainMah)
                    } else if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepDrainMah > 0f) {
                        "<0.1mAh"
                    } else if (subStats.screenOffDeepSleepDurationMs > 0L) {
                        "0.0mAh"
                    } else "--"
                    val energyDisplay = if (subStats.isScreenOffDecomposedAvailable && subStats.screenOffDeepSleepEnergyWh > 0f) {
                        String.format(Locale.US, "%.3f Wh", subStats.screenOffDeepSleepEnergyWh)
                    } else "--"
                    val subtitleHtml = "<font color=\"#B71C1C\">$durText</font> <font color=\"#B71C1C\">$sleepMahSubtitle</font>"
                    tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    val fullText = if (sleepPowerText != null) "$energyDisplay $sleepPowerText" else energyDisplay
                    val mahHtml = "<font color=\"#B71C1C\">$fullText</font>"
                    tvSleepMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                } else {
                    val subtitleHtml = "<font color=\"#B71C1C\">$durText</font> <font color=\"#B71C1C\">$energyText</font>"
                    tvSleepSubtitle.text = HtmlCompat.fromHtml(subtitleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    val fullText = if (sleepPowerText != null) "$mahStr $sleepPowerText" else mahStr
                    val mahHtml = "<font color=\"#B71C1C\">$fullText</font>"
                    tvSleepMah.text = HtmlCompat.fromHtml(mahHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                }
            }
        }
    }
}
