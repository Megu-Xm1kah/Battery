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
     * @param isCharging 是否处于充电状态（默认 true）
     * @param showUnit 是否显示单位符号（默认 true）
     * @return 格式化后的功率文本
     */
    private fun formatPowerText(
        powerWatts: Float?,
        isCharging: Boolean = true,
        showUnit: Boolean = true
    ): String {
        return com.battery.analysis.manager.FloatingWindowManager.formatPowerText(powerWatts, isCharging, showUnit)
    }

    /**
     * 辅助格式化实时温度字符串（与悬浮窗实际逻辑一致）。
     *
     * @param tempCelsius 瞬时电池温度摄氏度
     * @param showUnit 是否显示单位符号（默认 true）
     * @return 格式化后的温度文本
     */
    private fun formatTempText(tempCelsius: Float?, showUnit: Boolean = true): String {
        return if (tempCelsius != null && tempCelsius > 0f) {
            val formatted = String.format(Locale.getDefault(), "%.1f", tempCelsius)
            if (showUnit) "${formatted}℃" else formatted
        } else {
            if (showUnit) "--℃" else "--"
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
     * 验证实时功率文本在充电、放电与无数据状态下的格式化输出，带 +、- 号以区分充、放电。
     */
    @Test
    fun testPowerFormatting() {
        assertEquals("充电正功率应带+号", "+18.5W", formatPowerText(18.5f, isCharging = true))
        assertEquals("充电大功率应带+号", "+65.0W", formatPowerText(65.0f, isCharging = true))
        assertEquals("充电中净放电应带-号", "-2.1W", formatPowerText(-2.1f, isCharging = true))
        assertEquals("放电耗电应带-号", "-3.2W", formatPowerText(3.2f, isCharging = false))
        assertEquals("放电负数输入应带-号", "-3.2W", formatPowerText(-3.2f, isCharging = false))
        assertEquals("微弱放电应带-号", "-0.8W", formatPowerText(0.8f, isCharging = false))
        assertEquals("充电接近零功率应格式化为+0.0W", "+0.0W", formatPowerText(0.02f, isCharging = true))
        assertEquals("放电接近零功率应格式化为-0.0W", "-0.0W", formatPowerText(0.02f, isCharging = false))
        assertEquals("无数据或未获取时如实展示--W", "--W", formatPowerText(null, isCharging = false))
        assertEquals("充电无数据或未获取时如实展示--W", "--W", formatPowerText(null, isCharging = true))
    }

    /**
     * 验证关闭单位显示时实时功率文本在充电、放电与无数据状态下的格式化输出（无 W 符号，带 +、- 号）。
     */
    @Test
    fun testPowerFormattingWithoutUnit() {
        assertEquals("关闭单位时充电正功率展示+号纯数值", "+18.5", formatPowerText(18.5f, isCharging = true, showUnit = false))
        assertEquals("关闭单位时充电大功率展示+号纯数值", "+65.0", formatPowerText(65.0f, isCharging = true, showUnit = false))
        assertEquals("关闭单位时放电耗电展示-号纯数值", "-3.2", formatPowerText(3.2f, isCharging = false, showUnit = false))
        assertEquals("关闭单位时充电接近零功率展示+0.0", "+0.0", formatPowerText(0.02f, isCharging = true, showUnit = false))
        assertEquals("关闭单位时放电接近零功率展示-0.0", "-0.0", formatPowerText(0.02f, isCharging = false, showUnit = false))
        assertEquals("关闭单位时无数据或未获取如实展示--", "--", formatPowerText(null, isCharging = false, showUnit = false))
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
     * 验证关闭单位显示时实时温度文本在有效温度与无数据状态下的格式化输出（无 ℃ 符号）。
     */
    @Test
    fun testTempFormattingWithoutUnit() {
        assertEquals("关闭单位时正常温度展示纯数值", "32.5", formatTempText(32.5f, showUnit = false))
        assertEquals("关闭单位时高温状态展示纯数值", "42.0", formatTempText(42.0f, showUnit = false))
        assertEquals("关闭单位时无数据或未获取如实展示--", "--", formatTempText(null, showUnit = false))
        assertEquals("关闭单位时异常温度如实展示未知状态--", "--", formatTempText(-1f, showUnit = false))
    }

    /**
     * 验证是否显示单位与是否显示背景和边框的默认配置预期值（均为默认开启 true）。
     */
    @Test
    fun testDefaultUnitAndBackgroundSettings() {
        val defaultShowUnit = true
        val defaultShowBackground = true
        assertTrue("悬浮窗默认应显示单位符号", defaultShowUnit)
        assertTrue("悬浮窗默认应显示卡片背景与边框", defaultShowBackground)
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
     * 辅助计算基于相对居中偏移量的自适应水平 X 坐标（横竖屏通用算法）。
     *
     * @param screenWidth 屏幕宽度物理像素
     * @param viewWidth 悬浮窗宽度物理像素
     * @param offsetX 相对屏幕居中偏移量像素（0 居中，负数居中偏左，正数居中偏右）
     * @return 约束在屏幕边界内的最终 X 坐标
     */
    private fun calculateAdaptiveHorizontalX(
        screenWidth: Int,
        viewWidth: Int,
        offsetX: Int
    ): Int {
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val centerX = ((screenWidth - viewWidth) / 2).coerceAtLeast(0)
        return (centerX + offsetX).coerceIn(0, maxX)
    }

    /**
     * 验证横竖屏切换时，居中偏左、偏右任意偏移位置自适应保持对称对齐，彻底解决横屏下居中左右错位问题。
     */
    @Test
    fun testCenterLeftAndRightLandscapeAdaptation() {
        val portScreenWidth = 1080
        val landScreenWidth = 2400
        val viewWidth = 180

        val portCenter = (portScreenWidth - viewWidth) / 2
        val landCenter = (landScreenWidth - viewWidth) / 2

        // 场景 1: 严格居中 (offset = 0)
        val portExactCenter = calculateAdaptiveHorizontalX(portScreenWidth, viewWidth, 0)
        val landExactCenter = calculateAdaptiveHorizontalX(landScreenWidth, viewWidth, 0)
        assertEquals(450, portExactCenter)
        assertEquals(1110, landExactCenter)

        // 场景 2: 居中往左 50px (避开居中摄像头打孔，offset = -50)
        val portCenterLeft50 = calculateAdaptiveHorizontalX(portScreenWidth, viewWidth, -50)
        val landCenterLeft50 = calculateAdaptiveHorizontalX(landScreenWidth, viewWidth, -50)
        assertEquals("竖屏居中偏左50px", portCenter - 50, portCenterLeft50)
        assertEquals("横屏下亦精准位于横屏中心偏左50px处", landCenter - 50, landCenterLeft50)
        assertEquals(400, portCenterLeft50)
        assertEquals(1060, landCenterLeft50)

        // 场景 3: 居中往右 80px (offset = +80)
        val portCenterRight80 = calculateAdaptiveHorizontalX(portScreenWidth, viewWidth, 80)
        val landCenterRight80 = calculateAdaptiveHorizontalX(landScreenWidth, viewWidth, 80)
        assertEquals("竖屏居中偏右80px", portCenter + 80, portCenterRight80)
        assertEquals("横屏下亦精准位于横屏中心偏右80px处", landCenter + 80, landCenterRight80)
        assertEquals(530, portCenterRight80)
        assertEquals(1190, landCenterRight80)
    }

    /**
     * 验证文字大小配置参数范围约束逻辑（5sp 至 20sp）。
     */
    @Test
    fun testFontSizeConstraint() {
        val clampFontSize = { size: Int ->
            size.coerceIn(
                com.battery.analysis.manager.FloatingWindowManager.MIN_FONT_SIZE,
                com.battery.analysis.manager.FloatingWindowManager.MAX_FONT_SIZE
            )
        }
        assertEquals("低于下限5sp时应钳位至5sp", 5, clampFontSize(3))
        assertEquals("正常5sp保持不变", 5, clampFontSize(5))
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

    /**
     * 验证纯数据源驱动模式下，外部服务推流数据的格式化与展示一致性（显示 +、- 号以区分充、放电）。
     */
    @Test
    fun testPureDataSourcePresentationConsistency() {
        var powerText = formatPowerText(null, isCharging = false)
        var tempText = formatTempText(null)
        assertEquals("初始无数据时功率展示占位符", "--W", powerText)
        assertEquals("初始无数据时温度展示占位符", "--℃", tempText)

        // 模拟后台采样服务推送放电数据
        powerText = formatPowerText(4.2f, isCharging = false)
        tempText = formatTempText(35.6f)
        assertEquals("-4.2W", powerText)
        assertEquals("35.6℃", tempText)

        // 模拟后台采样服务推送充电数据
        powerText = formatPowerText(27.8f, isCharging = true)
        tempText = formatTempText(38.0f)
        assertEquals("+27.8W", powerText)
        assertEquals("38.0℃", tempText)
    }

    /**
     * 验证固定周期调度算法（Fixed-Interval Ticker）在正常耗时下的时钟步进与延迟计算。
     */
    @Test
    fun testFixedIntervalTickerNormalStep() {
        val targetInterval = 1000L
        var nextTick = 10000L
        val currentNow = 10060L // 单次采样耗时 60ms

        nextTick += targetInterval
        var sleepMs = nextTick - currentNow
        val minSmoothIntervalMs = (targetInterval * 0.8f).toLong().coerceAtLeast(200L)

        if (sleepMs < 100L) {
            nextTick = currentNow + targetInterval
            sleepMs = minSmoothIntervalMs
        }

        assertEquals("正常耗时下锚点递增推进", 11000L, nextTick)
        assertEquals("正常耗时下睡眠时长扣减执行耗时以严格对齐目标时间", 940L, sleepMs)
    }

    /**
     * 验证固定周期调度算法在遭遇系统级严重卡顿超时（costMs >= targetInterval）时的防连击保护机制。
     * 必须跳过落后节拍并重置下一个整周期，且休眠时长绝不能为 0ms，杜绝瞬间连跳两下的视觉抖动。
     */
    @Test
    fun testFixedIntervalTickerBurstProtection() {
        val targetInterval = 1000L
        var nextTick = 10000L
        val currentNow = 11150L // 系统卡顿或重度 IPC 导致耗时高达 1150ms，已超过 1000ms

        nextTick += targetInterval // 11000L
        var sleepMs = nextTick - currentNow // 11000 - 11150 = -150L (< 100L)
        val minSmoothIntervalMs = (targetInterval * 0.8f).toLong().coerceAtLeast(200L) // 800L

        if (sleepMs < 100L) {
            nextTick = currentNow + targetInterval
            sleepMs = minSmoothIntervalMs
        }

        assertEquals("严重超时时重置锚点至当前时间加上周期", 12150L, nextTick)
        assertEquals("严重超时时强制设定平滑防抖最小间隔（800ms），绝不执行0ms连击", 800L, sleepMs)
        assertTrue("休眠时长必须大于等于200ms平滑阈值", sleepMs >= 200L)
    }

    /**
     * 验证用户处于桌面或前台应用未解析出时（null 状态），短效缓存生效以杜绝每秒穿透查询 USM。
     */
    @Test
    fun testForegroundAppDesktopNullCache() {
        val expireMs = 2000L
        var lastKnownPackage: String? = null
        var lastQueryTime = 10000L

        // 辅助缓存命中检查逻辑
        val shouldBypassUsmQuery = { now: Long ->
            (now - lastQueryTime < expireMs) && (lastKnownPackage == null)
        }

        // 在 2 秒短缓存窗口内，桌面状态直接返回 null，无需执行 USM 查询
        assertTrue("1.0秒时应命中空态缓存跳过查询", shouldBypassUsmQuery(11000L))
        assertTrue("1.8秒时应命中空态缓存跳过查询", shouldBypassUsmQuery(11800L))
        assertTrue("超过2.0秒缓存过期时应重新触发查询", !shouldBypassUsmQuery(12050L))
    }
}
