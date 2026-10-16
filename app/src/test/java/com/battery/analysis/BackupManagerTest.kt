package com.battery.analysis

import com.battery.analysis.manager.BackupManager
import com.battery.analysis.model.BackupData
import com.battery.analysis.model.BackupSettings
import com.battery.analysis.model.HistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份与恢复数据解析核心单元测试。
 * 验证 [BackupManager] 对 JSON 数据的序列化与反序列化正确性、参数完整性及异常容错能力。
 */
class BackupManagerTest {

    /**
     * 测试标准备份数据 JSON 解析与字段还原。
     */
    @Test
    fun testParseBackupJson_validData() {
        val testJson = """
            {
                "version": 1,
                "app_version": "1.2.1",
                "backup_time": "2026-08-19 13:30:00",
                "backup_timestamp": 1787117400000,
                "settings": {
                    "theme_mode": 2,
                    "auto_refresh_enabled": true,
                    "refresh_interval_ms": 3000
                },
                "history_records": [
                    {
                        "id": 1787117400000,
                        "capture_time": "2026-08-19 13:30:00",
                        "source": "系统api",
                        "category": "系统api",
                        "note": "日常测试",
                        "battery_health": 98.5,
                        "health_status": "良好",
                        "level": 85,
                        "status": "放电中",
                        "design_capacity": 5000.0,
                        "current_capacity": 4250.0,
                        "full_charge_capacity": 4925.0,
                        "cycle_count": 42,
                        "temperature": 28.5,
                        "voltage": 4120.0,
                        "current_now": -350.0,
                        "power_watts": 1.44,
                        "is_dual_cell": false,
                        "technology": "Li-ion"
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val backupData = manager.parseBackupJson(testJson)

        assertEquals(1, backupData.version)
        assertEquals("1.2.1", backupData.appVersion)
        assertEquals("2026-08-19 13:30:00", backupData.backupTime)
        assertEquals(1787117400000L, backupData.backupTimestamp)

        // 验证设置项
        assertEquals(2, backupData.settings.themeMode)
        assertEquals(true, backupData.settings.autoRefreshEnabled)
        assertEquals(3000L, backupData.settings.refreshIntervalMs)

        // 验证历史记录项
        assertEquals(1, backupData.historyRecords.size)
        val record = backupData.historyRecords[0]
        assertEquals(1787117400000L, record.id)
        assertEquals("2026-08-19 13:30:00", record.captureTime)
        assertEquals("系统api", record.source)
        assertEquals("系统api", record.category)
        assertEquals("日常测试", record.note)
        assertEquals(98.5f, record.batteryHealth!!, 0.001f)
        assertEquals("良好", record.healthStatus)
        assertEquals(85, record.level)
        assertEquals("放电中", record.status)
        assertEquals(5000.0f, record.designCapacity!!, 0.001f)
        assertEquals(4250.0f, record.currentCapacity!!, 0.001f)
        assertEquals(4925.0f, record.fullChargeCapacity!!, 0.001f)
        assertEquals(42, record.cycleCount)
        assertEquals(28.5f, record.temperature!!, 0.001f)
        assertEquals(4120.0f, record.voltage!!, 0.001f)
        assertEquals(-350.0f, record.currentNow!!, 0.001f)
        assertEquals(1.44f, record.powerWatts!!, 0.001f)
        assertEquals(false, record.isDualCell)
        assertEquals("Li-ion", record.technology)
    }

    /**
     * 测试当部分非必填字段缺失或为 null 时，解析器能够稳定降级处理且不发生崩溃。
     */
    @Test
    fun testParseBackupJson_partialData() {
        val testJson = """
            {
                "version": 1,
                "app_version": "1.0.0",
                "backup_time": "2026-08-19 12:00:00",
                "settings": {},
                "history_records": [
                    {
                        "capture_time": "2026-08-19 12:00:00",
                        "source": "Shizuku",
                        "level": 90
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val backupData = manager.parseBackupJson(testJson)

        assertEquals(1, backupData.version)
        assertNull(backupData.settings.themeMode)
        assertNull(backupData.settings.autoRefreshEnabled)
        assertNull(backupData.settings.refreshIntervalMs)

        assertEquals(1, backupData.historyRecords.size)
        val record = backupData.historyRecords[0]
        assertEquals("2026-08-19 12:00:00", record.captureTime)
        assertEquals("Shizuku", record.source)
        assertEquals("Shizuku", record.category)
        assertEquals(90, record.level)
        assertNull(record.batteryHealth)
        assertNull(record.designCapacity)
    }

    /**
     * 测试全量 App 设置项与充、放电历史记录的 JSON 反序列化解析。
     * 验证包含语言、服务开关、亮息屏间隔、开机自启、充电常亮以及充电会话与放电记录的字段完整性。
     */
    @Test
    fun testParseBackupJson_fullSettingsAndChargeDischargeRecords() {
        val testJson = """
            {
                "version": 2,
                "app_version": "1.3.0",
                "backup_time": "2026-09-22 17:00:00",
                "backup_timestamp": 1790067600000,
                "settings": {
                    "language_mode": 1,
                    "theme_mode": 1,
                    "charge_discharge_stats_enabled": true,
                    "charging_keep_screen_on": true,
                    "power_stats_mode": 0,
                    "power_mode_configured": true,
                    "shizuku_user_disabled": false,
                    "notification_display_enabled": true,
                    "screen_on_interval_ms": 3000,
                    "screen_off_interval_ms": 10000,
                    "boot_auto_start_enabled": true
                },
                "history_records": [],
                "charging_records": [
                    {
                        "id": 101,
                        "record_time": "2026-09-22 15:00:00",
                        "start_timestamp": 1790060400000,
                        "end_timestamp": 1790064000000,
                        "duration_ms": 3600000,
                        "start_level": 20,
                        "end_level": 85,
                        "level_gain": 65,
                        "charged_energy_wh": 18.5,
                        "avg_power_watts": 18.5,
                        "max_power_watts": 33.0,
                        "max_temperature": 36.2,
                        "charge_type": "交流快充",
                        "screen_off_duration_ms": 3000000,
                        "screen_off_level_gain": 55,
                        "screen_off_energy_wh": 15.2,
                        "sample_points_json": "[{\"ts\":1790060400000,\"pw\":33.0,\"lv\":20}]"
                    }
                ],
                "power_usage_records": [
                    {
                        "id": 201,
                        "record_time": "2026-09-22 16:30:00",
                        "level_percent": 80,
                        "voltage_volts": 4.15,
                        "temperature": 29.0,
                        "energy_wh": 16.2,
                        "is_charging": false,
                        "avg_power_watts": 1.25,
                        "screen_on_power_watts": 2.1,
                        "screen_off_power_watts": 0.15,
                        "screen_on_duration_text": "1h30m",
                        "screen_off_duration_text": "4h00m",
                        "total_duration_text": "5h30m",
                        "rem_screen_on": "6h30m",
                        "rem_composite": "12h00m",
                        "rem_screen_off": "35h00m",
                        "is_shizuku_real_data": true,
                        "app_count": 12,
                        "trend_points_json": "[]",
                        "app_list_json": "[]",
                        "background_power_watts": 0.2,
                        "background_duration_text": "2h",
                        "rem_background": "20h",
                        "screen_on_energy_wh": 3.15,
                        "total_energy_wh": 6.88,
                        "screen_off_energy_wh": 0.6,
                        "background_energy_wh": 0.4
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val backupData = manager.parseBackupJson(testJson)

        assertEquals(2, backupData.version)
        assertEquals("1.3.0", backupData.appVersion)

        // 验证全部扩展设置项
        val s = backupData.settings
        assertEquals(1, s.languageMode)
        assertEquals(1, s.themeMode)
        assertEquals(true, s.chargeDischargeStatsEnabled)
        assertEquals(true, s.chargingKeepScreenOn)
        assertEquals(0, s.powerStatsMode)
        assertEquals(true, s.powerModeConfigured)
        assertEquals(false, s.shizukuUserDisabled)
        assertEquals(true, s.notificationDisplayEnabled)
        assertEquals(3000L, s.screenOnIntervalMs)
        assertEquals(10000L, s.screenOffIntervalMs)
        assertEquals(true, s.bootAutoStartEnabled)

        // 验证充电记录
        assertEquals(1, backupData.chargingRecords.size)
        val charging = backupData.chargingRecords[0]
        assertEquals(101L, charging.id)
        assertEquals("2026-09-22 15:00:00", charging.recordTime)
        assertEquals(20, charging.startLevel)
        assertEquals(85, charging.endLevel)
        assertEquals(65, charging.levelGain)
        assertEquals(18.5f, charging.chargedEnergyWh, 0.01f)
        assertEquals(33.0f, charging.maxPowerWatts, 0.01f)
        assertEquals("交流快充", charging.chargeType)
        assertEquals(3000000L, charging.screenOffDurationMs)
        assertEquals(1, charging.getSamplePoints().size)

        // 验证放电记录
        assertEquals(1, backupData.powerUsageRecords.size)
        val power = backupData.powerUsageRecords[0]
        assertEquals(201L, power.id)
        assertEquals("2026-09-22 16:30:00", power.recordTime)
        assertEquals(80, power.levelPercent)
        assertEquals(4.15f, power.voltageVolts, 0.01f)
        assertEquals(29.0f, power.temperature, 0.01f)
        assertEquals(false, power.isCharging)
        assertEquals(1.25f, power.avgPowerWatts, 0.01f)
        assertEquals(2.1f, power.screenOnPowerWatts, 0.01f)
        assertEquals("1h30m", power.screenOnDurationText)
        assertEquals("5h30m", power.totalDurationText)
        assertTrue(power.isShizukuRealData)
        assertEquals(12, power.appCount)
    }

    /**
     * 测试向后兼容版本 1 备份文件。
     * 当备份中无 charging_records 与 power_usage_records 时，解析为非 null 的空列表，保证安全平滑升级。
     */
    @Test
    fun testParseBackupJson_v1Compatibility() {
        val v1Json = """
            {
                "version": 1,
                "app_version": "1.0.0",
                "backup_time": "2026-08-01 10:00:00",
                "settings": {
                    "theme_mode": 2
                },
                "history_records": []
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val data = manager.parseBackupJson(v1Json)

        assertEquals(1, data.version)
        assertEquals(2, data.settings.themeMode)
        assertNull(data.settings.languageMode)
        assertNull(data.settings.chargeDischargeStatsEnabled)
        assertNull(data.settings.floatingEnabled)
        assertNull(data.settings.currentCalibrationMultiplier)
        assertNotNull(data.historyRecords)
        assertTrue(data.historyRecords.isEmpty())
        assertNotNull(data.chargingRecords)
        assertTrue(data.chargingRecords.isEmpty())
        assertNotNull(data.powerUsageRecords)
        assertTrue(data.powerUsageRecords.isEmpty())
    }

    /**
     * 测试版本 3 全量偏好设置（包含桌面悬浮窗、硬件电流校准、耗电单位、趋势图指标）
     * 与放电全量细分指标（息屏唤醒与深睡能耗/时长/电荷量、会话完结状态及 Checkpoint 时间戳）的无损反序列化。
     */
    @Test
    fun testParseBackupJson_v3FullSettingsAndRecords() {
        val testJson = """
            {
                "version": 3,
                "app_version": "1.9.24",
                "backup_time": "2026-10-16 10:00:00",
                "backup_timestamp": 1792120000000,
                "settings": {
                    "language_mode": 1,
                    "theme_mode": 2,
                    "charge_discharge_stats_enabled": true,
                    "charging_keep_screen_on": true,
                    "power_stats_mode": 0,
                    "power_mode_configured": true,
                    "shizuku_user_disabled": false,
                    "notification_display_enabled": true,
                    "screen_on_interval_ms": 1000,
                    "screen_off_interval_ms": 0,
                    "boot_auto_start_enabled": true,
                    "battery_service_enabled": true,
                    "floating_enabled": true,
                    "floating_show_power": true,
                    "floating_show_temp": true,
                    "floating_align_status_bar": true,
                    "floating_pos_x": 120,
                    "floating_pos_y": 50,
                    "floating_pos_x_land": 240,
                    "floating_pos_y_land": 30,
                    "floating_horizontal_align": 2,
                    "floating_opacity": 95,
                    "floating_theme_mode": 1,
                    "floating_font_size": 13,
                    "floating_font_bold": false,
                    "floating_show_unit": true,
                    "floating_show_background": false,
                    "floating_offset_x": -10,
                    "floating_pos_percent_x": 0.45,
                    "floating_pos_percent_y": 0.12,
                    "current_calibration_multiplier": 10.0,
                    "current_calibration_dual_cell": true,
                    "current_calibration_invert_polarity": false,
                    "current_calibration_coulomb_fallback": true,
                    "power_display_unit": 1,
                    "power_unit_dialog_donot_show": true,
                    "enable_background_stats": true,
                    "timeline_saved_metrics": ["POWER", "BATTERY", "TEMPERATURE"]
                },
                "history_records": [],
                "charging_records": [],
                "power_usage_records": [
                    {
                        "id": 1792110000000,
                        "record_time": "2026-10-16 09:30:00",
                        "level_percent": 75,
                        "voltage_volts": 4.12,
                        "temperature": 30.5,
                        "energy_wh": 15.6,
                        "is_charging": false,
                        "avg_power_watts": 1.45,
                        "screen_on_power_watts": 2.2,
                        "screen_off_power_watts": 0.12,
                        "screen_on_duration_text": "2h10m",
                        "screen_off_duration_text": "6h30m",
                        "total_duration_text": "8h40m",
                        "rem_screen_on": "5h40m",
                        "rem_composite": "11h20m",
                        "rem_screen_off": "32h00m",
                        "is_shizuku_real_data": true,
                        "app_count": 8,
                        "trend_points_json": "[]",
                        "app_list_json": "[]",
                        "background_power_watts": 0.18,
                        "background_duration_text": "1h20m",
                        "rem_background": "22h00m",
                        "screen_on_energy_wh": 4.76,
                        "total_energy_wh": 5.54,
                        "screen_off_energy_wh": 0.78,
                        "background_energy_wh": 0.24,
                        "is_completed": true,
                        "last_checkpoint_time": 1792119000000,
                        "screen_off_awake_energy_wh": 0.58,
                        "screen_off_deep_sleep_energy_wh": 0.20,
                        "screen_off_awake_duration_ms": 1800000,
                        "screen_off_deep_sleep_duration_ms": 21600000,
                        "screen_off_awake_drain_mah": 140.5,
                        "screen_off_deep_sleep_drain_mah": 48.2,
                        "is_screen_off_decomposed": true
                    }
                ]
            }
        """.trimIndent()

        val manager = BackupManager.getInstance()
        val data = manager.parseBackupJson(testJson)

        assertEquals(3, data.version)
        assertEquals("1.9.24", data.appVersion)

        // 验证悬浮窗设置项
        val s = data.settings
        assertEquals(true, s.batteryServiceEnabled)
        assertEquals(true, s.floatingEnabled)
        assertEquals(true, s.floatingShowPower)
        assertEquals(true, s.floatingShowTemp)
        assertEquals(true, s.floatingAlignStatusBar)
        assertEquals(120, s.floatingPosX)
        assertEquals(50, s.floatingPosY)
        assertEquals(240, s.floatingPosXLand)
        assertEquals(30, s.floatingPosYLand)
        assertEquals(2, s.floatingHorizontalAlign)
        assertEquals(95, s.floatingOpacity)
        assertEquals(1, s.floatingThemeMode)
        assertEquals(13, s.floatingFontSize)
        assertEquals(false, s.floatingFontBold)
        assertEquals(true, s.floatingShowUnit)
        assertEquals(false, s.floatingShowBackground)
        assertEquals(-10, s.floatingOffsetX)
        assertEquals(0.45f, s.floatingPosPercentX!!, 0.001f)
        assertEquals(0.12f, s.floatingPosPercentY!!, 0.001f)

        // 验证电流校准设置项
        assertEquals(10.0f, s.currentCalibrationMultiplier!!, 0.001f)
        assertEquals(true, s.currentCalibrationDualCell)
        assertEquals(false, s.currentCalibrationInvertPolarity)
        assertEquals(true, s.currentCalibrationCoulombFallback)

        // 验证耗电统计单位及指标
        assertEquals(1, s.powerDisplayUnit)
        assertEquals(true, s.powerUnitDialogDoNotShow)
        assertEquals(true, s.enableBackgroundStats)
        assertNotNull(s.timelineSavedMetrics)
        assertEquals(3, s.timelineSavedMetrics!!.size)
        assertTrue(s.timelineSavedMetrics!!.contains("POWER"))

        // 验证放电记录细分与状态指标
        assertEquals(1, data.powerUsageRecords.size)
        val p = data.powerUsageRecords[0]
        assertEquals(1792110000000L, p.id)
        assertEquals(true, p.isCompleted)
        assertEquals(1792119000000L, p.lastCheckpointTime)
        assertEquals(0.58f, p.screenOffAwakeEnergyWh, 0.001f)
        assertEquals(0.20f, p.screenOffDeepSleepEnergyWh, 0.001f)
        assertEquals(1800000L, p.screenOffAwakeDurationMs)
        assertEquals(21600000L, p.screenOffDeepSleepDurationMs)
        assertEquals(140.5f, p.screenOffAwakeDrainMah, 0.001f)
        assertEquals(48.2f, p.screenOffDeepSleepDrainMah, 0.001f)
        assertTrue(p.isScreenOffDecomposedAvailable)
    }

}
