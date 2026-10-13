package com.battery.analysis

import com.battery.analysis.model.PowerHistorySummary
import com.battery.analysis.model.PowerUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耗电记录历史列表汇总统计模型与计算逻辑单元测试套件。
 * 针对 [PowerHistorySummary] 进行全方位物理换算与真实性验证，包括：
 * 1. 空列表边界处理；
 * 2. 亮屏、息屏与总计三维度的时长累加与紧凑格式化；
 * 3. 真实物理能量累加与基于能量守恒定律的加权平均功耗（W）计算；
 * 4. 基于电池满充能量容量（Wh）与平均功耗推导的放电速度（%/h）计算；
 * 5. 缺失数据时的零伪造保底与真实 null 保留校验。
 */
class PowerHistorySummaryTest {

    /**
     * 验证当历史记录列表为空时，汇总统计实体忠实返回默认空状态与 null，不产生任何虚假数据。
     */
    @Test
    fun testEmptyListSummary() {
        val summary = PowerHistorySummary.calculate(emptyList(), totalCapacityWh = null)

        assertEquals("记录数必须为 0", 0, summary.recordCount)
        assertEquals("总时长必须为 0", 0L, summary.totalDurationMs)
        assertEquals("亮屏时长必须为 0", 0L, summary.screenOnDurationMs)
        assertEquals("息屏时长必须为 0", 0L, summary.screenOffDurationMs)
        assertEquals("总能量必须为 0.0", 0.0, summary.totalEnergyWh, 0.0001)
        assertEquals("亮屏能量必须为 0.0", 0.0, summary.screenOnEnergyWh, 0.0001)
        assertEquals("息屏能量必须为 0.0", 0.0, summary.screenOffEnergyWh, 0.0001)
        assertEquals("掉电百分比必须为 0", 0, summary.totalLevelDropPercent)

        assertNull("平均功耗必须为 null", summary.avgPowerWatts)
        assertNull("亮屏功耗必须为 null", summary.screenOnPowerWatts)
        assertNull("息屏功耗必须为 null", summary.screenOffPowerWatts)
        assertNull("放电速度必须为 null", summary.globalDischargeRate)
        assertNull("亮屏放电速度必须为 null", summary.screenOnDischargeRate)
        assertNull("息屏放电速度必须为 null", summary.screenOffDischargeRate)

        assertEquals("--", summary.getFormattedTotalDuration())
        assertEquals("--", summary.getFormattedScreenOnDuration())
        assertEquals("--", summary.getFormattedScreenOffDuration())
        assertEquals("--", summary.getFormattedAvgPower())
        assertEquals("--", summary.getFormattedScreenOnPower())
        assertEquals("--", summary.getFormattedScreenOffPower())
        assertEquals("--", summary.getFormattedGlobalDischargeRate())
        assertEquals("--", summary.getFormattedScreenOnDischargeRate())
        assertEquals("--", summary.getFormattedScreenOffDischargeRate())
    }

    /**
     * 验证多条真实放电快照累加计算时的总时长、亮屏时长、息屏时长、能量以及平均功耗准确度。
     */
    @Test
    fun testPowerHistorySummaryCalculations() {
        // 构造记录 1：总时长 2 小时，亮屏 30 分钟 (0.5h)，息屏 1.5 小时
        // 亮屏消耗能量 1.0 Wh (亮屏功耗 2.0 W)，息屏消耗能量 0.45 Wh (息屏功耗 0.3 W)，整机总能量 1.45 Wh (综合功耗 0.725 W)
        // 起始 100%，终止 90%，掉电 10%
        val record1 = PowerUsageRecord(
            id = 1000L,
            recordTime = "2026-10-12 10:00:00",
            levelPercent = 90,
            voltageVolts = 4.0f,
            temperature = 32.0f,
            energyWh = 18.0f,
            isCharging = false,
            avgPowerWatts = 0.725f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.3f,
            screenOnDurationText = "30m",
            screenOffDurationText = "01h30m",
            totalDurationText = "02h00m",
            remainingScreenOnText = "10h",
            remainingCompositeText = "20h",
            remainingScreenOffText = "50h",
            isShizukuRealData = true,
            appCount = 5,
            trendPointsJson = "[{\"ts\":1000,\"lvl\":100,\"pwr\":0.725,\"screenOn\":true},{\"ts\":8200000,\"lvl\":90,\"pwr\":0.725,\"screenOn\":false}]",
            appListJson = "[]",
            screenOnEnergyWh = 1.0f,
            screenOffEnergyWh = 0.45f,
            totalEnergyWh = 1.45f
        )

        // 构造记录 2：总时长 1 小时，亮屏 30 分钟 (0.5h)，息屏 30 分钟 (0.5h)
        // 亮屏消耗能量 1.2 Wh (亮屏功耗 2.4 W)，息屏消耗能量 0.15 Wh (息屏功耗 0.3 W)，整机总能量 1.35 Wh (综合功耗 1.35 W)
        // 起始 90%，终止 82%，掉电 8%
        val record2 = PowerUsageRecord(
            id = 2000L,
            recordTime = "2026-10-12 14:00:00",
            levelPercent = 82,
            voltageVolts = 3.9f,
            temperature = 33.0f,
            energyWh = 16.0f,
            isCharging = false,
            avgPowerWatts = 1.35f,
            screenOnPowerWatts = 2.4f,
            screenOffPowerWatts = 0.3f,
            screenOnDurationText = "30m",
            screenOffDurationText = "30m",
            totalDurationText = "01h00m",
            remainingScreenOnText = "8h",
            remainingCompositeText = "15h",
            remainingScreenOffText = "40h",
            isShizukuRealData = true,
            appCount = 4,
            trendPointsJson = "[{\"ts\":2000,\"lvl\":90,\"pwr\":1.35,\"screenOn\":true},{\"ts\":5600000,\"lvl\":82,\"pwr\":1.35,\"screenOn\":false}]",
            appListJson = "[]",
            screenOnEnergyWh = 1.2f,
            screenOffEnergyWh = 0.15f,
            totalEnergyWh = 1.35f
        )

        val records = listOf(record1, record2)
        // 传入电池总能量 20.0 Wh
        val summary = PowerHistorySummary.calculate(records, totalCapacityWh = 20.0f)

        assertEquals("记录数应为 2", 2, summary.recordCount)

        // 时长累加验证：总计 3 小时 (10800000ms)，亮屏 1 小时 (3600000ms)，息屏 2 小时 (7200000ms)
        assertEquals("总时长累加应为 3 小时", 3 * 3600000L, summary.totalDurationMs)
        assertEquals("亮屏时长累加应为 1 小时", 1 * 3600000L, summary.screenOnDurationMs)
        assertEquals("息屏时长累加应为 2 小时", 2 * 3600000L, summary.screenOffDurationMs)

        // 能量累加验证：
        // 亮屏能量 = 1.0 + 1.2 = 2.2 Wh
        // 息屏能量 = 0.45 + 0.15 = 0.6 Wh
        // 总能量 = 1.45 + 1.35 = 2.8 Wh
        assertEquals(2.2, summary.screenOnEnergyWh, 0.001)
        assertEquals(0.6, summary.screenOffEnergyWh, 0.001)
        assertEquals(2.8, summary.totalEnergyWh, 0.001)

        // 掉电百分比累加验证：10 + 8 = 18%
        assertEquals(18, summary.totalLevelDropPercent)

        // 平均放电功耗验证：
        // 亮屏平均功耗 = 2.2 Wh / 1.0 h = 2.20 W
        // 息屏平均功耗 = 0.6 Wh / 2.0 h = 0.30 W
        // 综合平均功耗 = 2.8 Wh / 3.0 h ≈ 0.9333 W
        assertNotNull(summary.screenOnPowerWatts)
        assertEquals(2.20f, summary.screenOnPowerWatts!!, 0.01f)

        assertNotNull(summary.screenOffPowerWatts)
        assertEquals(0.30f, summary.screenOffPowerWatts!!, 0.01f)

        assertNotNull(summary.avgPowerWatts)
        assertEquals(0.9333f, summary.avgPowerWatts!!, 0.01f)

        // 放电速度（%/h）验证：(Power / 20.0Wh) * 100%
        // 亮屏速度 = (2.20 / 20.0) * 100% = 11.0%/h
        // 息屏速度 = (0.30 / 20.0) * 100% = 1.5%/h
        // 综合速度 = (0.9333 / 20.0) * 100% ≈ 4.67%/h -> 格式化为 4.7%/h
        assertNotNull(summary.screenOnDischargeRate)
        assertEquals(11.0f, summary.screenOnDischargeRate!!, 0.01f)

        assertNotNull(summary.screenOffDischargeRate)
        assertEquals(1.5f, summary.screenOffDischargeRate!!, 0.01f)

        assertNotNull(summary.globalDischargeRate)
        assertEquals(4.6667f, summary.globalDischargeRate!!, 0.01f)

        // 格式化文本验证
        assertEquals("03h00m", summary.getFormattedTotalDuration())
        assertEquals("01h00m", summary.getFormattedScreenOnDuration())
        assertEquals("02h00m", summary.getFormattedScreenOffDuration())
        assertEquals("2.20 W", summary.getFormattedScreenOnPower())
        assertEquals("0.30 W", summary.getFormattedScreenOffPower())
        assertEquals("0.93 W", summary.getFormattedAvgPower())
        assertEquals("11.0%/h", summary.getFormattedScreenOnDischargeRate())
        assertEquals("1.5%/h", summary.getFormattedScreenOffDischargeRate())
        assertEquals("4.7%/h", summary.getFormattedGlobalDischargeRate())
    }

    /**
     * 验证放电速度百分比速率（%/h）在给定满电容量时的物理推导公式与格式化输出。
     */
    @Test
    fun testDischargeRateCalculation() {
        // 假设电池满充总能量为 20.0 Wh
        // 亮屏功耗 2.0 W -> 亮屏速度 = (2.0 / 20.0) * 100% = 10.0%/h
        // 息屏功耗 0.4 W -> 息屏速度 = (0.4 / 20.0) * 100% = 2.0%/h
        // 综合功耗 1.0 W -> 综合速度 = (1.0 / 20.0) * 100% = 5.0%/h
        val summary = PowerHistorySummary(
            recordCount = 10,
            totalDurationMs = 10 * 3600000L,
            screenOnDurationMs = 3 * 3600000L,
            screenOffDurationMs = 7 * 3600000L,
            totalEnergyWh = 10.0,
            screenOnEnergyWh = 6.0,
            screenOffEnergyWh = 2.8,
            avgPowerWatts = 1.0f,
            screenOnPowerWatts = 2.0f,
            screenOffPowerWatts = 0.4f,
            batteryCapacityWh = 20.0f,
            globalDischargeRate = 5.0f,
            screenOnDischargeRate = 10.0f,
            screenOffDischargeRate = 2.0f
        )

        assertEquals("10.0%/h", summary.getFormattedScreenOnDischargeRate())
        assertEquals("2.0%/h", summary.getFormattedScreenOffDischargeRate())
        assertEquals("5.0%/h", summary.getFormattedGlobalDischargeRate())
    }

    /**
     * 验证各时间量级下的紧凑时长格式化算法（天、时、分、秒）。
     */
    @Test
    fun testFormatDurationVariants() {
        val summary = PowerHistorySummary()

        // 1. 0 毫秒 -> "00s"
        assertEquals("00s", summary.formatDuration(0L))

        // 2. 45 秒 -> "45s"
        assertEquals("45s", summary.formatDuration(45 * 1000L))

        // 3. 5 分钟 20 秒 -> "05m20s"
        assertEquals("05m20s", summary.formatDuration((5 * 60 + 20) * 1000L))

        // 4. 15 分钟整 -> "15m"
        assertEquals("15m", summary.formatDuration(15 * 60 * 1000L))

        // 5. 3 小时 45 分钟 -> "03h45m"
        assertEquals("03h45m", summary.formatDuration((3 * 3600 + 45 * 60) * 1000L))

        // 6. 2 天 5 小时 -> "2d05h"
        assertEquals("2d05h", summary.formatDuration((2 * 86400 + 5 * 3600) * 1000L))
    }

    /**
     * 验证能量 Wh 转换为等效物理电荷量 mAh 的物理换算准确度。
     */
    @Test
    fun testConvertEnergyWhToMah() {
        val summary = PowerHistorySummary()

        // 3.85V 标称电压下，3.85 Wh 能量恰好等效为 1000 mAh
        val mah1 = summary.convertEnergyWhToMah(3.85, 3.85f)
        assertEquals("3.85 Wh 应精确折算为 1000 mAh", 1000f, mah1, 0.01f)

        // 0.0 Wh 能量返回 0f
        val mahZero = summary.convertEnergyWhToMah(0.0, 3.85f)
        assertEquals("0 Wh 应返回 0 mAh", 0f, mahZero, 0.001f)

        // 负数能量返回 0f
        val mahNeg = summary.convertEnergyWhToMah(-1.0, 3.85f)
        assertEquals("负能量应安全返回 0 mAh", 0f, mahNeg, 0.001f)
    }
}
