package com.battery.analysis.util

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.battery.analysis.R

/**
 * 对话框窗口样式与自适应尺寸调度助手。
 * 统一管理应用内所有现代化弹窗的窗口动画、半透明背景、屏幕宽度占比与自适应高度测量，
 * 严格确保未超过整体高度 90% 时自适应紧凑包裹内容（无多余留白），超过 90% 时将整体高度限制在 90% 并内部平滑滚动。
 */
object DialogWindowHelper {

    /**
     * 为弹窗统一配置窗口样式，并根据内容实际展开高度智能调度自适应包裹或 90% 最大高度限制。
     *
     * @param dialog 待配置样式的 [AlertDialog] 实例
     * @param dialogView 弹窗的根视图 [View]
     * @param widthRatio 弹窗宽度相对屏幕可用物理宽度的比例（默认为 0.92，汇总表格等宽弹窗可传 0.95）
     * @param maxHeightRatio 弹窗允许占用的最大屏幕高度比例（默认为 0.90f）
     */
    fun applyAdaptiveDialogWindowStyle(
        dialog: AlertDialog,
        dialogView: View,
        widthRatio: Double = 0.92,
        maxHeightRatio: Float = 0.90f
    ) {
        val window = dialog.window ?: return
        window.setWindowAnimations(R.style.Animation_Dialog_CenterPop)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setGravity(Gravity.CENTER)

        val displayMetrics = dialogView.resources.displayMetrics
        val targetWidth = (displayMetrics.widthPixels * widthRatio).toInt()
        val maxHeight = (displayMetrics.heightPixels * maxHeightRatio).toInt()

        // 测量弹窗全部内容自然展开时的物理总高度
        val naturalHeight = measureNaturalContentHeight(dialogView, targetWidth)

        if (naturalHeight > maxHeight) {
            // 超过最大高度限制：整体高度锁定为 90%，并为中间承载内容的滚动视图开启 weight=1 撑满剩余高度
            window.setLayout(targetWidth, maxHeight)
            dialogView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeight
            )
            configureScrollContainer(dialogView, enableWeight = true)
        } else {
            // 未超过最大高度限制：自适应高度包裹内容（WRAP_CONTENT），去除任何 weight 强制拉伸，杜绝底部留白
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialogView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            configureScrollContainer(dialogView, enableWeight = false)
        }
    }

    /**
     * 测量并计算弹窗所有子视图在自然展开状态下的真实物理像素总高度。
     *
     * @param dialogView 弹窗根视图 [View]
     * @param targetWidth 弹窗分配的固定像素宽度
     * @return 弹窗全部内容自然展开时的真实像素高度
     */
    private fun measureNaturalContentHeight(dialogView: View, targetWidth: Int): Int {
        // 情况 1：根视图本身即为滚动视图（如 ScrollView / NestedScrollView，如悬浮窗设置弹窗）
        if (dialogView is ScrollView || dialogView is NestedScrollView) {
            val child = (dialogView as ViewGroup).getChildAt(0) ?: return dialogView.measuredHeight
            val contentWidthSpec = View.MeasureSpec.makeMeasureSpec(
                targetWidth - dialogView.paddingLeft - dialogView.paddingRight,
                View.MeasureSpec.EXACTLY
            )
            val contentHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            child.measure(contentWidthSpec, contentHeightSpec)
            return child.measuredHeight + dialogView.paddingTop + dialogView.paddingBottom
        }

        // 情况 2：根视图为垂直线性布局，包含顶部标题、中间滚动区与底部操作栏
        if (dialogView is LinearLayout && dialogView.orientation == LinearLayout.VERTICAL) {
            var totalHeight = dialogView.paddingTop + dialogView.paddingBottom
            val contentWidth = targetWidth - dialogView.paddingLeft - dialogView.paddingRight
            val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
            val unspecHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            for (i in 0 until dialogView.childCount) {
                val child = dialogView.getChildAt(i)
                if (child.visibility == View.GONE) continue

                val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                val marginH = (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)

                if (child is ScrollView || child is NestedScrollView) {
                    val scrollChild = (child as ViewGroup).getChildAt(0)
                    if (scrollChild != null) {
                        val innerWidthSpec = View.MeasureSpec.makeMeasureSpec(
                            contentWidth - child.paddingLeft - child.paddingRight - (lp?.leftMargin ?: 0) - (lp?.rightMargin ?: 0),
                            View.MeasureSpec.EXACTLY
                        )
                        scrollChild.measure(innerWidthSpec, unspecHeightSpec)
                        totalHeight += scrollChild.measuredHeight + child.paddingTop + child.paddingBottom + marginH
                    } else {
                        child.measure(widthSpec, unspecHeightSpec)
                        totalHeight += child.measuredHeight + marginH
                    }
                } else if (child is FrameLayout && (child.findViewById<View>(R.id.rv_power_history) != null || child.findViewById<View>(R.id.rv_charging_history) != null)) {
                    // 历史记录列表容器：测量空数据提示或默认展开高度
                    child.measure(widthSpec, unspecHeightSpec)
                    totalHeight += child.measuredHeight + marginH
                } else {
                    child.measure(widthSpec, unspecHeightSpec)
                    totalHeight += child.measuredHeight + marginH
                }
            }
            return totalHeight
        }

        // 情况 3：通用默认测量兜底
        val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        dialogView.measure(widthSpec, heightSpec)
        return dialogView.measuredHeight
    }

    /**
     * 针对中间承载长内容的滚动容器，根据是否超过 90% 高度动态切换其 LayoutParams 的权重与高度模式。
     *
     * @param root 弹窗根视图 [View]
     * @param enableWeight 是否启用权重撑满模式（true 为 0dp + weight=1，false 为 WRAP_CONTENT + weight=0）
     */
    private fun configureScrollContainer(root: View, enableWeight: Boolean) {
        if (root !is ViewGroup) return

        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ScrollView || child is NestedScrollView ||
                (child is FrameLayout && (child.findViewById<View>(R.id.rv_power_history) != null || child.findViewById<View>(R.id.rv_charging_history) != null))
            ) {
                val lp = child.layoutParams
                if (lp is LinearLayout.LayoutParams) {
                    if (enableWeight) {
                        lp.height = 0
                        lp.weight = 1f
                    } else {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.weight = 0f
                    }
                    child.layoutParams = lp
                }
                return
            }
        }
    }
}
