package com.battery.analysis.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.databinding.ItemChargingHistoryHeaderBinding
import com.battery.analysis.model.ChargingHistorySummary
import java.util.Locale

/**
 * 充电记录历史列表头部总结看板适配器。
 * 挂载于主 RecyclerView 的顶部第 0 项（配合 ConcatAdapter 扁平化架构），
 * 负责展示指定充电历史集合的亮屏、息屏与总计三维度的时长、平均充电功率及充电速度汇总信息，
 * 并支持卡片触控交互弹出高精度统计明细弹窗。
 *
 * @param onHeaderClick 列表头部看板卡片点击事件回调函数，传入当前的统计汇总对象
 */
class ChargingHistoryHeaderAdapter(
    private val onHeaderClick: ((ChargingHistorySummary) -> Unit)? = null
) : RecyclerView.Adapter<ChargingHistoryHeaderAdapter.HeaderViewHolder>() {

    /**
     * 当前展示的充电历史记录汇总统计实体。
     */
    var summary: ChargingHistorySummary = ChargingHistorySummary()
        private set

    /**
     * 更新头部看板汇总统计数据，并触发视图增量局部刷新。
     *
     * @param newSummary 最新计算的充电历史汇总指标实体
     */
    fun updateSummary(newSummary: ChargingHistorySummary) {
        this.summary = newSummary
        notifyItemChanged(0)
    }

    /**
     * 头部总结卡片 ViewHolder 视图持有者。
     *
     * @param binding 头部看板视图绑定对象
     */
    inner class HeaderViewHolder(
        val binding: ItemChargingHistoryHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root)

    /**
     * 创建头部 ViewHolder 实例。
     *
     * @param parent 父容器视图组
     * @param viewType 视图类型
     * @return 初始化的 [HeaderViewHolder]
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemChargingHistoryHeaderBinding.inflate(inflater, parent, false)
        return HeaderViewHolder(binding)
    }

    /**
     * 绑定头部看板视图数据。
     *
     * @param holder 头部视图持有者
     * @param position 列表项下标
     */
    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        val b = holder.binding

        // 1. 亮屏维度（时长、功率、速度）
        b.tvChargingSummaryScreenOnDuration.text = summary.getFormattedScreenOnDuration()
        b.tvChargingSummaryScreenOnPower.text = summary.getFormattedScreenOnPower()
        b.tvChargingSummaryScreenOnSpeed.text = formatChargingRateSpannable(summary.screenOnChargingRate)

        // 2. 息屏维度（时长、功率、速度）
        b.tvChargingSummaryScreenOffDuration.text = summary.getFormattedScreenOffDuration()
        b.tvChargingSummaryScreenOffPower.text = summary.getFormattedScreenOffPower()
        b.tvChargingSummaryScreenOffSpeed.text = formatChargingRateSpannable(summary.screenOffChargingRate)

        // 3. 总计维度（时长、功率、速度）
        b.tvChargingSummaryTotalDuration.text = summary.getFormattedTotalDuration()
        b.tvChargingSummaryTotalPower.text = summary.getFormattedAvgPower()
        b.tvChargingSummaryTotalSpeed.text = formatChargingRateSpannable(summary.globalChargingRate)

        // 4. 点击弹出详细统计明细弹窗
        b.cardChargingHistorySummary.setOnClickListener {
            onHeaderClick?.invoke(summary)
        }
    }

    /**
     * 返回头部条目数量，固定为 1。
     *
     * @return 条目数 1
     */
    override fun getItemCount(): Int = 1

    /**
     * 将充电速度百分比速率格式化为图文混排富文本（数值部分突出，单位 "%/h" 微缩至 11sp）。
     *
     * @param rate 每小时充电百分比速率（单位：%/h），若为空或非正数返回 "--"
     * @return 图文混排的 [CharSequence]
     */
    private fun formatChargingRateSpannable(rate: Float?): CharSequence {
        if (rate == null || rate <= 0f) {
            return "--"
        }
        val numStr = String.format(Locale.getDefault(), "%.1f", rate)
        val unitStr = "%/h"
        val fullText = "$numStr$unitStr"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            AbsoluteSizeSpan(11, true),
            numStr.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }
}
