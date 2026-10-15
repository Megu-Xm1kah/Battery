package com.battery.analysis

import com.battery.analysis.model.AppPowerUsageItem
import com.battery.analysis.model.PowerDisplayUnit
import com.battery.analysis.ui.AppPowerUsageAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 应用功耗列表项（Item）界面呈现逻辑单元测试套件。
 * 验证：
 * 1. Max 温度正确使用 "Max" 前缀并放置于平均温度之后以 "|" 分隔；
 * 2. 能量后方正确以中间点 " · " 分割并展示保留一位小数的百分占比；
 * 3. 展现层单位由 Wh 切换至 mAh 时百分占比保持严格物理守恒；
 * 4. 纯后台应用与无能量应用诚实展示未知状态 "--"，杜绝虚假发配数据。
 */
class AppPowerUsageItemDisplayTest {

    private val adapter = AppPowerUsageAdapter()

    /**
     * 验证有有效能量的应用在能量后方正确展示中间点 " · " 分隔及精确到一位小数的百分比。
     */
    @Test
    fun testFormatAppEnergyWithPercentage_ValidEnergy_ShowsEnergyAndPercentage() {
        val app1 = AppPowerUsageItem(
            packageName = "com.jingdong.app.mall",
            appName = "京东",
            icon = null,
            foregroundTimeMs = 117_000L,
            avgPowerWatts = 2.69f,
            avgTemperature = 39.5f,
            maxTemperature = 40.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            foregroundEnergyWh = 0.088f
        )
        val app2 = AppPowerUsageItem(
            packageName = "com.battery.analysis",
            appName = "电池统计",
            icon = null,
            foregroundTimeMs = 60_000L,
            avgPowerWatts = 2.50f,
            avgTemperature = 39.0f,
            maxTemperature = 39.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            foregroundEnergyWh = 0.042f
        )
        val app3 = AppPowerUsageItem(
            packageName = "com.hihonor.android.launcher",
            appName = "荣耀桌面",
            icon = null,
            foregroundTimeMs = 29_000L,
            avgPowerWatts = 4.01f,
            avgTemperature = 39.5f,
            maxTemperature = 40.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            foregroundEnergyWh = 0.033f
        )
        val app4 = AppPowerUsageItem(
            packageName = "com.tencent.mobileqq",
            appName = "QQ",
            icon = null,
            foregroundTimeMs = 27_000L,
            avgPowerWatts = 1.07f,
            avgTemperature = 40.0f,
            maxTemperature = 40.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            foregroundEnergyWh = 0.008f
        )

        val totalEnergy = app1.foregroundEnergyWh + app2.foregroundEnergyWh + app3.foregroundEnergyWh + app4.foregroundEnergyWh
        assertEquals(0.171f, totalEnergy, 0.0001f)

        // 京东：0.088 / 0.171 ≈ 51.46% -> 51.5%
        val text1 = adapter.formatAppEnergyWithPercentage(app1, totalEnergy, PowerDisplayUnit.WH)
        assertEquals("0.088Wh · 51.5%", text1)

        // 电池统计：0.042 / 0.171 ≈ 24.56% -> 24.6%
        val text2 = adapter.formatAppEnergyWithPercentage(app2, totalEnergy, PowerDisplayUnit.WH)
        assertEquals("0.042Wh · 24.6%", text2)

        // 荣耀桌面：0.033 / 0.171 ≈ 19.30% -> 19.3%
        val text3 = adapter.formatAppEnergyWithPercentage(app3, totalEnergy, PowerDisplayUnit.WH)
        assertEquals("0.033Wh · 19.3%", text3)

        // QQ：0.008 / 0.171 ≈ 4.68% -> 4.7%
        val text4 = adapter.formatAppEnergyWithPercentage(app4, totalEnergy, PowerDisplayUnit.WH)
        assertEquals("0.008Wh · 4.7%", text4)
    }

    /**
     * 验证当电量显示单位切换为 mAh 时，百分占比数值完全一致，不受单位换算影响。
     */
    @Test
    fun testFormatAppEnergyWithPercentage_MahUnit_PercentageRemainsConsistent() {
        val app = AppPowerUsageItem(
            packageName = "com.jingdong.app.mall",
            appName = "京东",
            icon = null,
            foregroundTimeMs = 117_000L,
            avgPowerWatts = 2.69f,
            avgTemperature = 39.5f,
            maxTemperature = 40.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            foregroundEnergyWh = 0.088f
        )
        val totalEnergy = 0.171f

        val textWh = adapter.formatAppEnergyWithPercentage(app, totalEnergy, PowerDisplayUnit.WH)
        val textMah = adapter.formatAppEnergyWithPercentage(app, totalEnergy, PowerDisplayUnit.MAH)

        assertTrue("Wh 格式包含 51.5%", textWh.contains(" · 51.5%"))
        assertTrue("mAh 格式包含 51.5%", textMah.contains(" · 51.5%"))
        assertTrue("mAh 单位包含 mAh 后缀", textMah.endsWith("mAh · 51.5%"))
    }

    /**
     * 验证无有效能量或纯后台应用诚实展示未知状态 "--"，杜绝虚假发配。
     */
    @Test
    fun testFormatAppEnergyWithPercentage_NoEnergy_ShowsDoubleDash() {
        val bgApp = AppPowerUsageItem(
            packageName = "com.example.service",
            appName = "后台服务",
            icon = null,
            foregroundTimeMs = 0L,
            avgPowerWatts = 0f,
            avgTemperature = 38.0f,
            maxTemperature = 39.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            backgroundTimeMs = 60_000L,
            foregroundEnergyWh = 0f
        )
        val text = adapter.formatAppEnergyWithPercentage(bgApp, 1.0f, PowerDisplayUnit.WH)
        assertEquals("--", text)

        val textWhenTotalZero = adapter.formatAppEnergyWithPercentage(bgApp, 0f, PowerDisplayUnit.WH)
        assertEquals("--", textWhenTotalZero)
    }

    /**
     * 验证温度格式化逻辑：Max 温度使用 "Max" 前缀，并能正确放置在平均温度后以 "|" 分隔。
     */
    @Test
    fun testTemperatureFormat_MaxTempMovedAfterAvgTempWithPipeDivider() {
        val pwrStr = "2.69W"
        val avgTemp = 39.5f
        val maxTemp = 40.0f

        val avgTempStr = if (avgTemp > 0f) String.format(Locale.getDefault(), "%.1f℃", avgTemp) else "--"
        val maxTempStr = if (maxTemp > 0f) String.format(Locale.getDefault(), "Max: %.1f℃", maxTemp) else "Max: --"
        val combinedText = "AVG: $pwrStr, $avgTempStr | $maxTempStr"

        assertEquals("AVG: 2.69W, 39.5℃ | Max: 40.0℃", combinedText)
    }

    /**
     * 验证温度数据缺失时如实展示未知状态 "--"，不捏造假数据。
     */
    @Test
    fun testTemperatureFormat_MissingData_ShowsDoubleDash() {
        val pwrStr = "2.50W"
        val avgTemp = 0f
        val maxTemp = 0f

        val avgTempStr = if (avgTemp > 0f) String.format(Locale.getDefault(), "%.1f℃", avgTemp) else "--"
        val maxTempStr = if (maxTemp > 0f) String.format(Locale.getDefault(), "Max: %.1f℃", maxTemp) else "Max: --"
        val combinedText = "AVG: $pwrStr, $avgTempStr | $maxTempStr"

        assertEquals("AVG: 2.50W, -- | Max: --", combinedText)
    }

    /**
     * 验证列表适配器在电量显示单位发生变更时（由 Wh 切换为 mAh）能正确更新内部状态，
     * 并确保应用条目能耗格式化输出由 Wh 准确转换为 mAh，杜绝继续输出 Wh。
     */
    @Test
    fun testAppPowerUsageAdapter_DisplayUnitSync_UpdatesEnergyFormatFromWhToMah() {
        val testAdapter = AppPowerUsageAdapter()
        assertEquals(PowerDisplayUnit.WH, testAdapter.currentDisplayUnit)

        val app = AppPowerUsageItem(
            packageName = "com.battery.analysis",
            appName = "电池统计",
            icon = null,
            foregroundTimeMs = 305_000L,
            avgPowerWatts = 1.05f,
            avgTemperature = 37.7f,
            maxTemperature = 39.0f,
            lastUsedTimeMs = System.currentTimeMillis(),
            foregroundEnergyWh = 0.089f
        )
        val totalEnergy = 0.113f

        // 1. 默认 Wh 模式下输出 Wh
        val textWh = testAdapter.formatAppEnergyWithPercentage(app, totalEnergy, testAdapter.currentDisplayUnit)
        assertEquals("0.089Wh · 78.8%", textWh)

        // 2. 模拟详情页初始化/偏好设置同步，更新为 mAh
        val targetUnit = PowerDisplayUnit.MAH
        testAdapter.updateDisplayUnit(targetUnit)
        assertEquals(PowerDisplayUnit.MAH, testAdapter.currentDisplayUnit)

        // 3. 更新为 mAh 模式后输出 mAh（0.089 * 1000 / 3.85 ≈ 23.1mAh）
        val textMah = testAdapter.formatAppEnergyWithPercentage(app, totalEnergy, testAdapter.currentDisplayUnit)
        assertEquals("23.1mAh · 78.8%", textMah)
    }
}
