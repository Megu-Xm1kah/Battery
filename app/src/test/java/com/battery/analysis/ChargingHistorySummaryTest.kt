package com.battery.analysis

import com.battery.analysis.model.ChargingHistoryRecord
import com.battery.analysis.model.ChargingHistorySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 充电记录历史列表汇总统计模型与计算逻辑单元测试套件。
 * 针对 [ChargingHistorySummary] 进行全方位物理换算与真实性验证，包括：
 * 1. 空列表边界处理；
 * 2. 亮屏、息屏与总计三维度的时长累加与紧凑格式化；
 * 3. 充入物理能量累加与基于能量守恒定律的加权平均充电功率（W）计算；
 * 4. 充电速度（%/h）与峰值参数（最高功率、最高温度）统计计算；
 * 5. 能量 Wh 至等效电荷量 mAh 的物理转换准确度；
 * 6. 缺失数据时的零伪造保底与真实 null 保留校验。
 */
class ChargingHistorySummaryTest {

    /**
     * 验证当历史记录列表为空时，汇总统计实体忠实返回默认空状态与 null，不产生任何虚假数据。
     */
    @Test
    fun testEmptyListSummary() {
        val summary = ChargingHistorySummary.calculate(emptyList(), totalCapacityWh = null)

        assertEquals("记录数必须为 0", 0, summary.recordCount)
        assertEquals("总时长必须为 0", 0L, summary.totalDurationMs)
        assertEquals("亮屏时长必须为 0", 0L, summary.screenOnDurationMs)
        assertEquals("息屏时长必须为 0", 0L, summary.screenOffDurationMs)
        assertEquals("总能量必须为 0.0", 0.0, summary.totalEnergyWh, 0.0001)
        assertEquals("亮屏能量必须为 0.0", 0.0, summary.screenOnEnergyWh, 0.0001)
        assertEquals("息屏能量必须为 0.0", 0.0, summary.screenOffEnergyWh, 0.0001)
        assertEquals("充入电量百分比必须为 0", 0, summary.totalLevelGainPercent)
        assertEquals("亮屏充入电量必须为 0", 0, summary.screenOnLevelGainPercent)
        assertEquals("息屏充入电量必须为 0", 0, summary.screenOffLevelGainPercent)

        assertNull("平均功率必须为 null", summary.avgPowerWatts)
        assertNull("亮屏功率必须为 null", summary.screenOnPowerWatts)
        assertNull("息屏功率必须为 null", summary.screenOffPowerWatts)
        assertNull("充电速度必须为 null", summary.globalChargingRate)
        assertNull("亮屏充电速度必须为 null", summary.screenOnChargingRate)
        assertNull("息屏充电速度必须为 null", summary.screenOffChargingRate)
        assertNull("最高功率必须为 null", summary.maxPowerWatts)
        assertNull("最高温度必须为 null", summary.maxTemperature)

        assertEquals("--", summary.getFormattedTotalDuration())
        assertEquals("--", summary.getFormattedScreenOnDuration())
        assertEquals("--", summary.getFormattedScreenOffDuration())
        assertEquals("--", summary.getFormattedAvgPower())
        assertEquals("--", summary.getFormattedScreenOnPower())
        assertEquals("--", summary.getFormattedScreenOffPower())
        assertEquals("--", summary.getFormattedGlobalChargingRate())
        assertEquals("--", summary.getFormattedScreenOnChargingRate())
        assertEquals("--", summary.getFormattedScreenOffChargingRate())
    }

    /**
     * 验证多条真实充电记录累加计算时的总时长、亮屏时长、息屏时长、能量、平均功率及充电速度准确度。
     */
    @Test
    fun testChargingHistorySummaryCalculations() {
        // 构造记录 1：总时长 1 小时 (3600000ms)，息屏 45 分钟 (2700000ms)，亮屏 15 分钟 (900000ms = 0.25h)
        // 总充入能量 40.0 Wh，息屏充入能量 35.0 Wh，亮屏能量 = 40.0 - 35.0 = 5.0 Wh
        // 电量增量 50%，息屏增量 42%，亮屏增量 8%
        // 最高功率 65.0 W，最高温度 38.5 ℃
        val record1 = ChargingHistoryRecord(
            id = 1000L,
            recordTime = "2026-10-12 10:00:00",
            startTimestamp = 1000L,
            endTimestamp = 3601000L,
            durationMs = 3600000L,
            startLevel = 20,
            endLevel = 70,
            levelGain = 50,
            chargedEnergyWh = 40.0f,
            avgPowerWatts = 40.0f,
            maxPowerWatts = 65.0f,
            maxTemperature = 38.5f,
            chargeType = "交流快充",
            screenOffDurationMs = 2700000L,
            screenOffLevelGain = 42,
            screenOffEnergyWh = 35.0f,
            samplePointsJson = ""
        )

        // 构造记录 2：总时长 30 分钟 (1800000ms = 0.5h)，息屏 15 分钟 (900000ms = 0.25h)，亮屏 15 分钟 (900000ms = 0.25h)
        // 总充入能量 15.0 Wh，息屏充入能量 10.0 Wh，亮屏能量 = 15.0 - 10.0 = 5.0 Wh
        // 电量增量 25%，息屏增量 18%，亮屏增量 7%
        // 最高功率 70.0 W，最高温度 40.2 ℃
        val record2 = ChargingHistoryRecord(
            id = 2000L,
            recordTime = "2026-10-12 18:00:00",
            startTimestamp = 2000L,
            endTimestamp = 1802000L,
            durationMs = 1800000L,
            startLevel = 40,
            endLevel = 65,
            levelGain = 25,
            chargedEnergyWh = 15.0f,
            avgPowerWatts = 30.0f,
            maxPowerWatts = 70.0f,
            maxTemperature = 40.2f,
            chargeType = "交流快充",
            screenOffDurationMs = 900000L,
            screenOffLevelGain = 18,
            screenOffEnergyWh = 10.0f,
            samplePointsJson = ""
        )

        val records = listOf(record1, record2)
        val summary = ChargingHistorySummary.calculate(records, totalCapacityWh = 20.0f)

        assertEquals("记录数应为 2", 2, summary.recordCount)

        // 时长累加验证：
        // 总时长 = 1h + 0.5h = 1.5h = 5400000ms
        // 息屏时长 = 0.75h + 0.25h = 1.0h = 3600000ms
        // 亮屏时长 = 0.25h + 0.25h = 0.5h = 1800000ms
        assertEquals("总时长累加应为 5400000ms", 5400000L, summary.totalDurationMs)
        assertEquals("息屏时长累加应为 3600000ms", 3600000L, summary.screenOffDurationMs)
        assertEquals("亮屏时长累加应为 1800000ms", 1800000L, summary.screenOnDurationMs)

        // 能量累加验证：
        // 亮屏充入能量 = 5.0 + 5.0 = 10.0 Wh
        // 息屏充入能量 = 35.0 + 10.0 = 45.0 Wh
        // 总充入能量 = 40.0 + 15.0 = 55.0 Wh
        assertEquals(10.0, summary.screenOnEnergyWh, 0.001)
        assertEquals(45.0, summary.screenOffEnergyWh, 0.001)
        assertEquals(55.0, summary.totalEnergyWh, 0.001)

        // 电量增量百分比累加验证：
        // 总增量 = 50 + 25 = 75%
        // 息屏增量 = 42 + 18 = 60%
        // 亮屏增量 = 8 + 7 = 15%
        assertEquals(75, summary.totalLevelGainPercent)
        assertEquals(60, summary.screenOffLevelGainPercent)
        assertEquals(15, summary.screenOnLevelGainPercent)

        // 平均充电功率验证：
        // 综合平均功率 = 55.0 Wh / 1.5 h ≈ 36.6667 W
        // 息屏平均功率 = 45.0 Wh / 1.0 h = 45.0 W
        // 亮屏平均功率 = 10.0 Wh / 0.5 h = 20.0 W
        assertNotNull(summary.avgPowerWatts)
        assertEquals(36.6667f, summary.avgPowerWatts!!, 0.01f)

        assertNotNull(summary.screenOffPowerWatts)
        assertEquals(45.0f, summary.screenOffPowerWatts!!, 0.01f)

        assertNotNull(summary.screenOnPowerWatts)
        assertEquals(20.0f, summary.screenOnPowerWatts!!, 0.01f)

        // 充电速度验证（%/h）：
        // 综合速度 = 75% / 1.5h = 50.0%/h
        // 息屏速度 = 60% / 1.0h = 60.0%/h
        // 亮屏速度 = 15% / 0.5h = 30.0%/h
        assertNotNull(summary.globalChargingRate)
        assertEquals(50.0f, summary.globalChargingRate!!, 0.01f)

        assertNotNull(summary.screenOffChargingRate)
        assertEquals(60.0f, summary.screenOffChargingRate!!, 0.01f)

        assertNotNull(summary.screenOnChargingRate)
        assertEquals(30.0f, summary.screenOnChargingRate!!, 0.01f)

        // 峰值参数验证：
        assertEquals(70.0f, summary.maxPowerWatts!!, 0.01f)
        assertEquals(40.2f, summary.maxTemperature!!, 0.01f)

        // 格式化文本验证
        assertEquals("01h30m", summary.getFormattedTotalDuration())
        assertEquals("30m", summary.getFormattedScreenOnDuration())
        assertEquals("01h00m", summary.getFormattedScreenOffDuration())
        assertEquals("36.67 W", summary.getFormattedAvgPower())
        assertEquals("20.00 W", summary.getFormattedScreenOnPower())
        assertEquals("45.00 W", summary.getFormattedScreenOffPower())
        assertEquals("50.0%/h", summary.getFormattedGlobalChargingRate())
        assertEquals("30.0%/h", summary.getFormattedScreenOnChargingRate())
        assertEquals("60.0%/h", summary.getFormattedScreenOffChargingRate())
    }

    /**
     * 验证各时间量级下的紧凑时长格式化算法（天、时、分、秒）。
     */
    @Test
    fun testFormatDurationVariants() {
        val summary = ChargingHistorySummary()

        assertEquals("00s", summary.formatDuration(0L))
        assertEquals("30s", summary.formatDuration(30 * 1000L))
        assertEquals("10m15s", summary.formatDuration((10 * 60 + 15) * 1000L))
        assertEquals("25m", summary.formatDuration(25 * 60 * 1000L))
        assertEquals("02h15m", summary.formatDuration((2 * 3600 + 15 * 60) * 1000L))
        assertEquals("1d04h", summary.formatDuration((86400 + 4 * 3600) * 1000L))
    }

    /**
     * 验证充入能量 Wh 转换为等效物理电荷量 mAh 的物理换算准确度。
     */
    @Test
    fun testConvertEnergyWhToMah() {
        val summary = ChargingHistorySummary()

        // 3.85V 标称电压下，3.85 Wh 能量恰好等效为 1000 mAh
        val mah = summary.convertEnergyWhToMah(3.85, 3.85f)
        assertEquals("3.85 Wh 应精确折算为 1000 mAh", 1000f, mah, 0.01f)

        // 0.0 Wh 返回 0f
        val mahZero = summary.convertEnergyWhToMah(0.0, 3.85f)
        assertEquals("0 Wh 应返回 0 mAh", 0f, mahZero, 0.001f)

        // 负数能量返回 0f
        val mahNeg = summary.convertEnergyWhToMah(-2.0, 3.85f)
        assertEquals("负能量应安全返回 0 mAh", 0f, mahNeg, 0.001f)
    }
}
