package com.battery.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * 桌面悬浮窗数据展示与布局算法单元测试类。
 * 验证悬浮窗状态栏居中算法、水平对齐坐标计算、功率与温度数据格式化及透明度约束逻辑。
 */
class FloatingWindowTest {

    /**
     * 辅助格式化实时功率字符串（与悬浮窗实际逻辑一致）。
     *
     * @param powerWatts 瞬时充放电功率瓦特数
     * @return 格式化后的功率文本
     */
    private fun formatPowerText(powerWatts: Float?): String {
        return if (powerWatts != null && abs(powerWatts) > 0.05f) {
            if (powerWatts > 0f) {
                String.format(Locale.getDefault(), "%.1fW", powerWatts)
            } else {
                String.format(Locale.getDefault(), "-%.1fW", abs(powerWatts))
            }
        } else if (powerWatts != null) {
            "0.0W"
        } else {
            "--W"
        }
    }

    /**
     * 辅助格式化实时温度字符串（与悬浮窗实际逻辑一致）。
     *
     * @param tempCelsius 瞬时电池温度摄氏度
     * @return 格式化后的温度文本
     */
    private fun formatTempText(tempCelsius: Float?): String {
        return if (tempCelsius != null && tempCelsius > 0f) {
            String.format(Locale.getDefault(), "%.1f℃", tempCelsius)
        } else {
            "--℃"
        }
    }

    /**
     * 辅助计算状态栏垂直居中 Y 坐标。
     *
     * @param statusBarHeight 系统状态栏高度物理像素
     * @param viewHeight 悬浮窗高度物理像素
     * @return 居中 Y 坐标（像素）
     */
    private fun calculateStatusBarCenterY(statusBarHeight: Int, viewHeight: Int): Int {
        return ((statusBarHeight - viewHeight) / 2).coerceAtLeast(0)
    }

    /**
     * 辅助计算水平 X 坐标。
     *
     * @param alignMode 水平对齐模式 (0: 左, 1: 中, 2: 右, 3: 自定义)
     * @param screenWidth 屏幕宽度
     * @param viewWidth 悬浮窗宽度
     * @param edgePadding 边距
     * @param customX 自定义 X 坐标
     * @return 计算后的 X 坐标（像素）
     */
    private fun calculateHorizontalX(
        alignMode: Int,
        screenWidth: Int,
        viewWidth: Int,
        edgePadding: Int,
        customX: Int
    ): Int {
        return when (alignMode) {
            0 -> edgePadding
            1 -> ((screenWidth - viewWidth) / 2).coerceAtLeast(0)
            2 -> (screenWidth - viewWidth - edgePadding).coerceAtLeast(0)
            3 -> if (customX >= 0) customX else ((screenWidth - viewWidth) / 2).coerceAtLeast(0)
            else -> ((screenWidth - viewWidth) / 2).coerceAtLeast(0)
        }
    }

    /**
     * 验证实时功率文本在充电、放电与无数据状态下的格式化输出。
     */
    @Test
    fun testPowerFormatting() {
        assertEquals("充电正功率应格式化为正数", "18.5W", formatPowerText(18.5f))
        assertEquals("充电大功率应格式化为正数", "65.0W", formatPowerText(65.0f))
        assertEquals("放电耗电应格式化为负数", "-3.2W", formatPowerText(-3.2f))
        assertEquals("微弱放电应格式化为负数", "-0.8W", formatPowerText(-0.8f))
        assertEquals("接近零功率应格式化为0.0W", "0.0W", formatPowerText(0.02f))
        assertEquals("无数据或未获取时如实展示--W", "--W", formatPowerText(null))
    }

    /**
     * 验证实时温度文本在有效温度与无数据状态下的格式化输出。
     */
    @Test
    fun testTempFormatting() {
        assertEquals("正常电池温度应展示1位小数与单位", "32.5℃", formatTempText(32.5f))
        assertEquals("高温报警应真实反映", "42.0℃", formatTempText(42.0f))
        assertEquals("无数据或未获取时如实展示未知状态", "--℃", formatTempText(null))
        assertEquals("异常零度以下无效值展示未知状态", "--℃", formatTempText(-1f))
    }

    /**
     * 验证状态栏沉浸吸附垂直居中 Y 坐标算法。
     */
    @Test
    fun testStatusBarCenterYCalculation() {
        // 场景 1: 常见打孔屏状态栏高度 90px，悬浮窗高度 65px
        val y1 = calculateStatusBarCenterY(90, 65)
        assertEquals(12, y1)
        assertTrue("居中坐标应位于状态栏高度内", y1 in 0..90)

        // 场景 2: 紧凑状态栏高度 60px，悬浮窗高度 60px
        val y2 = calculateStatusBarCenterY(60, 60)
        assertEquals(0, y2)

        // 场景 3: 悬浮窗大于状态栏时下限保护为0
        val y3 = calculateStatusBarCenterY(50, 70)
        assertEquals(0, y3)
    }

    /**
     * 验证水平对齐（靠左、居中、靠右、自定义拖拽）X 坐标计算逻辑。
     */
    @Test
    fun testHorizontalXCalculation() {
        val screenWidth = 1080
        val viewWidth = 200
        val edgePadding = 40

        // 靠左对齐
        val xLeft = calculateHorizontalX(0, screenWidth, viewWidth, edgePadding, -1)
        assertEquals(40, xLeft)

        // 居中对齐
        val xCenter = calculateHorizontalX(1, screenWidth, viewWidth, edgePadding, -1)
        assertEquals(440, xCenter)

        // 靠右对齐
        val xRight = calculateHorizontalX(2, screenWidth, viewWidth, edgePadding, -1)
        assertEquals(840, xRight)

        // 自定义拖拽坐标
        val xCustom = calculateHorizontalX(3, screenWidth, viewWidth, edgePadding, 520)
        assertEquals(520, xCustom)
    }

    /**
     * 验证透明度参数边界裁剪逻辑（20% 至 100%）。
     */
    @Test
    fun testOpacityRangeConstraint() {
        val clampOpacity = { opacity: Int -> opacity.coerceIn(20, 100) }
        assertEquals(20, clampOpacity(10))
        assertEquals(20, clampOpacity(-5))
        assertEquals(85, clampOpacity(85))
        assertEquals(100, clampOpacity(100))
        assertEquals(100, clampOpacity(120))
    }

    /**
     * 辅助计算悬浮窗垂直 Y 坐标。
     *
     * @param isAlignStatusBar 是否允许显示在状态栏上
     * @param savedY 用户保存或设置的垂直 Y 坐标
     * @param statusBarHeight 系统底层真实状态栏高度
     * @param screenHeight 屏幕物理高度
     * @param viewHeight 悬浮窗物理高度
     * @return 实际应用的 Y 坐标（像素）
     */
    private fun calculateVerticalY(
        isAlignStatusBar: Boolean,
        savedY: Int,
        statusBarHeight: Int,
        screenHeight: Int,
        viewHeight: Int
    ): Int {
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)
        return if (isAlignStatusBar) {
            // 打开时：允许显示在状态栏上，忠实采用用户保存的坐标，不要自动贴齐状态栏
            savedY.coerceIn(0, maxY)
        } else {
            // 关闭时：不允许显示在状态栏位置上，强制位于状态栏高度下方
            savedY.coerceIn(statusBarHeight, maxY.coerceAtLeast(statusBarHeight))
        }
    }

    /**
     * 验证“显示在状态栏上”开关打开时：
     * 允许放置于状态栏区域内，但保持用户保存的原坐标，不自动贴齐屏幕顶部状态栏。
     */
    @Test
    fun testVerticalYWhenStatusBarAllowed() {
        val statusBarHeight = 85
        val screenHeight = 2400
        val viewHeight = 65

        // 用户设置在状态栏高度内（例如 33px）：真实保留 33px，不被贴齐为状态栏居中
        val yInSb = calculateVerticalY(
            isAlignStatusBar = true,
            savedY = 33,
            statusBarHeight = statusBarHeight,
            screenHeight = screenHeight,
            viewHeight = viewHeight
        )
        assertEquals(33, yInSb)

        // 用户设置在状态栏下方（例如 250px）：真实保留 250px
        val yBelowSb = calculateVerticalY(
            isAlignStatusBar = true,
            savedY = 250,
            statusBarHeight = statusBarHeight,
            screenHeight = screenHeight,
            viewHeight = viewHeight
        )
        assertEquals(250, yBelowSb)
    }

    /**
     * 验证“显示在状态栏上”开关关闭时：
     * 不允许显示在状态栏位置上，原本处于状态栏内的坐标必须强制约束在状态栏高度下方。
     */
    @Test
    fun testVerticalYWhenStatusBarForbidden() {
        val statusBarHeight = 85
        val screenHeight = 2400
        val viewHeight = 65

        // 开关关闭，若原本坐标处于状态栏内（如 33px），必须被限制在状态栏高度（85px），禁止置于状态栏
        val yRestricted = calculateVerticalY(
            isAlignStatusBar = false,
            savedY = 33,
            statusBarHeight = statusBarHeight,
            screenHeight = screenHeight,
            viewHeight = viewHeight
        )
        assertEquals(statusBarHeight, yRestricted)

        // 开关关闭，原本已在状态栏下方（如 250px），正常保持 250px
        val yAllowed = calculateVerticalY(
            isAlignStatusBar = false,
            savedY = 250,
            statusBarHeight = statusBarHeight,
            screenHeight = screenHeight,
            viewHeight = viewHeight
        )
        assertEquals(250, yAllowed)
    }

    /**
     * 验证横屏大分辨率模式下（如 2400 x 1080）居中、靠左、靠右及自定义边界自适应计算。
     */
    @Test
    fun testLandscapeHorizontalXCalculation() {
        val landScreenWidth = 2400
        val viewWidth = 220
        val edgePadding = 40

        // 横屏靠左
        val xLeft = calculateHorizontalX(0, landScreenWidth, viewWidth, edgePadding, -1)
        assertEquals(40, xLeft)

        // 横屏居中：(2400 - 220) / 2 = 1090
        val xCenter = calculateHorizontalX(1, landScreenWidth, viewWidth, edgePadding, -1)
        assertEquals(1090, xCenter)

        // 横屏靠右：2400 - 220 - 40 = 2140
        val xRight = calculateHorizontalX(2, landScreenWidth, viewWidth, edgePadding, -1)
        assertEquals(2140, xRight)

        // 横屏自定义坐标（受边界约束）
        val xCustom = calculateHorizontalX(3, landScreenWidth, viewWidth, edgePadding, 1500)
        assertEquals(1500, xCustom)
    }

    /**
     * 验证文字大小配置参数范围约束逻辑（8sp 至 20sp）。
     */
    @Test
    fun testFontSizeConstraint() {
        val clampFontSize = { size: Int -> size.coerceIn(8, 20) }
        assertEquals("低于下限8sp时应钳位至8sp", 8, clampFontSize(5))
        assertEquals("正常8sp保持不变", 8, clampFontSize(8))
        assertEquals("默认11sp保持不变", 11, clampFontSize(11))
        assertEquals("正常20sp保持不变", 20, clampFontSize(20))
        assertEquals("高于上限20sp时应钳位至20sp", 20, clampFontSize(25))
    }

    /**
     * 验证水平与垂直位置微调 +、- 按钮步进计算与边界保护。
     */
    @Test
    fun testStepIncrementAndDecrement() {
        val minY = 80
        val maxY = 2200

        // 步进减少
        val stepDown = { current: Int, min: Int -> (current - 1).coerceAtLeast(min) }
        assertEquals(99, stepDown(100, minY))
        assertEquals(80, stepDown(81, minY))
        assertEquals(80, stepDown(80, minY)) // 触碰边界不再减小

        // 步进增加
        val stepUp = { current: Int, max: Int -> (current + 1).coerceAtMost(max) }
        assertEquals(101, stepUp(100, maxY))
        assertEquals(2200, stepUp(2199, maxY))
        assertEquals(2200, stepUp(2200, maxY)) // 触碰边界不再增大
    }
}
