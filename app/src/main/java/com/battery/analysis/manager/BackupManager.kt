package com.battery.analysis.manager

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import com.battery.analysis.db.ChargingHistoryDbHelper
import com.battery.analysis.db.HistoryDbHelper
import com.battery.analysis.db.PowerUsageDbHelper
import com.battery.analysis.model.BackupData
import com.battery.analysis.model.BackupRestoreSummary
import com.battery.analysis.model.BackupSettings
import com.battery.analysis.model.ChargingHistoryRecord
import com.battery.analysis.model.HistoryRecord
import com.battery.analysis.model.PowerUsageRecord
import com.battery.analysis.model.PowerDisplayUnit
import com.battery.analysis.service.BatteryMonitorService
import com.battery.analysis.ui.PowerUsageFragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电池数据备份与恢复业务管理类。
 * 负责将应用偏好配置、电池检测历史快照、充电历史账本与放电耗电记录序列化为标准 JSON 格式并写入外部存储，
 * 以及从备份文件流中读取、校验、反序列化并按覆盖或合并策略恢复至本地数据库与偏好设置中。
 */
class BackupManager private constructor() {

    /**
     * 获取当前应用程序的版本名称（例如 "1.2.1"）。
     *
     * @param context 应用程序上下文
     * @return 应用版本名称字符串
     */
    fun getAppVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * 将当前应用全部设置项及电池检测快照、充电记录、放电记录序列化为格式化缩进的 JSON 字符串。
     *
     * @param context 应用程序上下文
     * @param historyRecords 待导出的电池检测历史快照列表
     * @param chargingRecords 待导出的充电历史记录列表
     * @param powerUsageRecords 待导出的放电耗电历史记录列表
     * @return 格式化后的 JSON 字符串
     */
    fun generateBackupJson(
        context: Context,
        historyRecords: List<HistoryRecord>,
        chargingRecords: List<ChargingHistoryRecord> = emptyList(),
        powerUsageRecords: List<PowerUsageRecord> = emptyList()
    ): String {
        val appPrefs = context.getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)
        val servicePrefs = context.getSharedPreferences("battery_service_prefs", Context.MODE_PRIVATE)
        val chargingPrefs = context.getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
        val powerPrefs = context.getSharedPreferences("power_stats_prefs", Context.MODE_PRIVATE)
        val floatingPrefs = context.getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE)
        val calibPrefs = context.getSharedPreferences("current_calibration_prefs", Context.MODE_PRIVATE)
        val timelinePrefs = context.getSharedPreferences("timeline_metrics_prefs", Context.MODE_PRIVATE)

        val now = Date()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val backupTime = timeFormat.format(now)

        val rootJson = JSONObject()
        rootJson.put("version", BACKUP_SCHEMA_VERSION)
        rootJson.put("app_version", getAppVersionName(context))
        rootJson.put("backup_time", backupTime)
        rootJson.put("backup_timestamp", now.time)

        // 1. 设置配置项打包（覆盖现有全部设置项）
        val settingsJson = JSONObject()
        if (appPrefs.contains("app_language_mode")) {
            settingsJson.put("language_mode", appPrefs.getInt("app_language_mode", LanguageManager.MODE_FOLLOW_SYSTEM))
        }
        if (appPrefs.contains("theme_mode")) {
            settingsJson.put("theme_mode", appPrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        }
        if (servicePrefs.contains("pref_charge_discharge_stats_enabled")) {
            settingsJson.put("charge_discharge_stats_enabled", servicePrefs.getBoolean("pref_charge_discharge_stats_enabled", false))
        }
        if (chargingPrefs.contains("pref_charging_keep_screen_on")) {
            settingsJson.put("charging_keep_screen_on", chargingPrefs.getBoolean("pref_charging_keep_screen_on", false))
        }
        if (powerPrefs.contains("pref_power_stats_mode")) {
            settingsJson.put("power_stats_mode", powerPrefs.getInt("pref_power_stats_mode", PowerUsageManager.MODE_NORMAL))
        }
        if (powerPrefs.contains("pref_power_mode_configured")) {
            settingsJson.put("power_mode_configured", powerPrefs.getBoolean("pref_power_mode_configured", false))
        }
        if (appPrefs.contains("pref_shizuku_user_disabled")) {
            settingsJson.put("shizuku_user_disabled", appPrefs.getBoolean("pref_shizuku_user_disabled", false))
        }
        if (servicePrefs.contains("pref_notification_display_enabled")) {
            settingsJson.put("notification_display_enabled", servicePrefs.getBoolean("pref_notification_display_enabled", true))
        }
        if (servicePrefs.contains("pref_screen_on_interval_ms")) {
            settingsJson.put("screen_on_interval_ms", servicePrefs.getLong("pref_screen_on_interval_ms", 1000L))
        }
        if (servicePrefs.contains("pref_screen_off_interval_ms")) {
            settingsJson.put("screen_off_interval_ms", servicePrefs.getLong("pref_screen_off_interval_ms", 0L))
        }
        if (servicePrefs.contains("pref_battery_boot_auto_start")) {
            settingsJson.put("boot_auto_start_enabled", servicePrefs.getBoolean("pref_battery_boot_auto_start", true))
        }
        if (servicePrefs.contains("pref_battery_service_enabled")) {
            settingsJson.put("battery_service_enabled", servicePrefs.getBoolean("pref_battery_service_enabled", true))
        }

        // 桌面全局悬浮窗配置
        if (floatingPrefs.contains(FloatingWindowManager.KEY_ENABLED)) {
            settingsJson.put("floating_enabled", floatingPrefs.getBoolean(FloatingWindowManager.KEY_ENABLED, false))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_SHOW_POWER)) {
            settingsJson.put("floating_show_power", floatingPrefs.getBoolean(FloatingWindowManager.KEY_SHOW_POWER, true))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_SHOW_TEMP)) {
            settingsJson.put("floating_show_temp", floatingPrefs.getBoolean(FloatingWindowManager.KEY_SHOW_TEMP, true))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_ALIGN_STATUS_BAR)) {
            settingsJson.put("floating_align_status_bar", floatingPrefs.getBoolean(FloatingWindowManager.KEY_ALIGN_STATUS_BAR, true))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_POS_X)) {
            settingsJson.put("floating_pos_x", floatingPrefs.getInt(FloatingWindowManager.KEY_POS_X, 0))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_POS_Y)) {
            settingsJson.put("floating_pos_y", floatingPrefs.getInt(FloatingWindowManager.KEY_POS_Y, 0))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_POS_X_LAND)) {
            settingsJson.put("floating_pos_x_land", floatingPrefs.getInt(FloatingWindowManager.KEY_POS_X_LAND, 0))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_POS_Y_LAND)) {
            settingsJson.put("floating_pos_y_land", floatingPrefs.getInt(FloatingWindowManager.KEY_POS_Y_LAND, 0))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_HORIZONTAL_ALIGN)) {
            settingsJson.put("floating_horizontal_align", floatingPrefs.getInt(FloatingWindowManager.KEY_HORIZONTAL_ALIGN, FloatingWindowManager.ALIGN_RIGHT))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_OPACITY)) {
            settingsJson.put("floating_opacity", floatingPrefs.getInt(FloatingWindowManager.KEY_OPACITY, 90))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_THEME_MODE)) {
            settingsJson.put("floating_theme_mode", floatingPrefs.getInt(FloatingWindowManager.KEY_THEME_MODE, FloatingWindowManager.THEME_FOLLOW_SYSTEM))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_FONT_SIZE)) {
            settingsJson.put("floating_font_size", floatingPrefs.getInt(FloatingWindowManager.KEY_FONT_SIZE, FloatingWindowManager.DEFAULT_FONT_SIZE))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_FONT_BOLD)) {
            settingsJson.put("floating_font_bold", floatingPrefs.getBoolean(FloatingWindowManager.KEY_FONT_BOLD, true))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_SHOW_UNIT)) {
            settingsJson.put("floating_show_unit", floatingPrefs.getBoolean(FloatingWindowManager.KEY_SHOW_UNIT, true))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_SHOW_BACKGROUND)) {
            settingsJson.put("floating_show_background", floatingPrefs.getBoolean(FloatingWindowManager.KEY_SHOW_BACKGROUND, true))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_OFFSET_X)) {
            settingsJson.put("floating_offset_x", floatingPrefs.getInt(FloatingWindowManager.KEY_OFFSET_X, 0))
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_POS_PERCENT_X)) {
            settingsJson.put("floating_pos_percent_x", floatingPrefs.getFloat(FloatingWindowManager.KEY_POS_PERCENT_X, 0.5f).toDouble())
        }
        if (floatingPrefs.contains(FloatingWindowManager.KEY_POS_PERCENT_Y)) {
            settingsJson.put("floating_pos_percent_y", floatingPrefs.getFloat(FloatingWindowManager.KEY_POS_PERCENT_Y, 0f).toDouble())
        }

        // 硬件电流校准配置
        if (calibPrefs.contains("key_calibration_multiplier")) {
            settingsJson.put("current_calibration_multiplier", calibPrefs.getFloat("key_calibration_multiplier", 1.0f).toDouble())
        }
        if (calibPrefs.contains("key_dual_cell")) {
            settingsJson.put("current_calibration_dual_cell", calibPrefs.getBoolean("key_dual_cell", false))
        }
        if (calibPrefs.contains("key_invert_polarity")) {
            settingsJson.put("current_calibration_invert_polarity", calibPrefs.getBoolean("key_invert_polarity", false))
        }
        if (calibPrefs.contains("key_coulomb_fallback")) {
            settingsJson.put("current_calibration_coulomb_fallback", calibPrefs.getBoolean("key_coulomb_fallback", true))
        }

        // 耗电统计偏好配置
        if (powerPrefs.contains(PowerDisplayUnit.PREF_KEY_POWER_DISPLAY_UNIT)) {
            settingsJson.put("power_display_unit", powerPrefs.getInt(PowerDisplayUnit.PREF_KEY_POWER_DISPLAY_UNIT, PowerDisplayUnit.WH.id))
        }
        if (powerPrefs.contains(PowerDisplayUnit.PREF_KEY_POWER_UNIT_DIALOG_DONOT_SHOW)) {
            settingsJson.put("power_unit_dialog_donot_show", powerPrefs.getBoolean(PowerDisplayUnit.PREF_KEY_POWER_UNIT_DIALOG_DONOT_SHOW, false))
        }
        if (powerPrefs.contains(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS)) {
            settingsJson.put("enable_background_stats", powerPrefs.getBoolean(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS, false))
        }

        // 趋势图指标配置（放电与充电时间轴）
        if (timelinePrefs.contains("saved_timeline_metrics")) {
            val metricsSet = timelinePrefs.getStringSet("saved_timeline_metrics", null)
            if (metricsSet != null) {
                val array = JSONArray()
                metricsSet.forEach { array.put(it) }
                settingsJson.put("timeline_saved_metrics", array)
            }
        }
        if (timelinePrefs.contains("saved_charging_timeline_metrics")) {
            val metricsSet = timelinePrefs.getStringSet("saved_charging_timeline_metrics", null)
            if (metricsSet != null) {
                val array = JSONArray()
                metricsSet.forEach { array.put(it) }
                settingsJson.put("timeline_saved_charging_metrics", array)
            }
        }

        rootJson.put("settings", settingsJson)

        // 2. 电池检测历史记录列表打包
        val historyArray = JSONArray()
        for (record in historyRecords) {
            val recordJson = JSONObject()
            recordJson.put("id", record.id)
            recordJson.put("capture_time", record.captureTime)
            recordJson.put("source", record.source)
            recordJson.put("category", record.category)
            record.note?.let { recordJson.put("note", it) }
            record.batteryHealth?.let { recordJson.put("battery_health", it.toDouble()) }
            record.healthStatus?.let { recordJson.put("health_status", it) }
            record.level?.let { recordJson.put("level", it) }
            record.status?.let { recordJson.put("status", it) }
            record.designCapacity?.let { recordJson.put("design_capacity", it.toDouble()) }
            record.currentCapacity?.let { recordJson.put("current_capacity", it.toDouble()) }
            record.fullChargeCapacity?.let { recordJson.put("full_charge_capacity", it.toDouble()) }
            record.cycleCount?.let { recordJson.put("cycle_count", it) }
            record.temperature?.let { recordJson.put("temperature", it.toDouble()) }
            record.voltage?.let { recordJson.put("voltage", it.toDouble()) }
            record.currentNow?.let { recordJson.put("current_now", it.toDouble()) }
            record.powerWatts?.let { recordJson.put("power_watts", it.toDouble()) }
            record.isDualCell?.let { recordJson.put("is_dual_cell", it) }
            record.technology?.let { recordJson.put("technology", it) }
            historyArray.put(recordJson)
        }
        rootJson.put("history_records", historyArray)

        // 3. 充电历史记录列表打包
        val chargingArray = JSONArray()
        for (c in chargingRecords) {
            val cJson = JSONObject()
            cJson.put("id", c.id)
            cJson.put("record_time", c.recordTime)
            cJson.put("start_timestamp", c.startTimestamp)
            cJson.put("end_timestamp", c.endTimestamp)
            cJson.put("duration_ms", c.durationMs)
            cJson.put("start_level", c.startLevel)
            cJson.put("end_level", c.endLevel)
            cJson.put("level_gain", c.levelGain)
            cJson.put("charged_energy_wh", c.chargedEnergyWh.toDouble())
            cJson.put("avg_power_watts", c.avgPowerWatts.toDouble())
            cJson.put("max_power_watts", c.maxPowerWatts.toDouble())
            cJson.put("max_temperature", c.maxTemperature.toDouble())
            cJson.put("charge_type", c.chargeType)
            cJson.put("screen_off_duration_ms", c.screenOffDurationMs)
            cJson.put("screen_off_level_gain", c.screenOffLevelGain)
            cJson.put("screen_off_energy_wh", c.screenOffEnergyWh.toDouble())
            cJson.put("sample_points_json", c.samplePointsJson)
            chargingArray.put(cJson)
        }
        rootJson.put("charging_records", chargingArray)

        // 4. 放电耗电历史记录列表打包（全量无损包含息屏细分与会话状态指标）
        val powerArray = JSONArray()
        for (p in powerUsageRecords) {
            val pJson = JSONObject()
            pJson.put("id", p.id)
            pJson.put("record_time", p.recordTime)
            pJson.put("level_percent", p.levelPercent)
            pJson.put("voltage_volts", p.voltageVolts.toDouble())
            pJson.put("temperature", p.temperature.toDouble())
            pJson.put("energy_wh", p.energyWh.toDouble())
            pJson.put("is_charging", p.isCharging)
            pJson.put("avg_power_watts", p.avgPowerWatts.toDouble())
            pJson.put("screen_on_power_watts", p.screenOnPowerWatts.toDouble())
            pJson.put("screen_off_power_watts", p.screenOffPowerWatts.toDouble())
            pJson.put("screen_on_duration_text", p.screenOnDurationText)
            pJson.put("screen_off_duration_text", p.screenOffDurationText)
            pJson.put("total_duration_text", p.totalDurationText)
            pJson.put("rem_screen_on", p.remainingScreenOnText)
            pJson.put("rem_composite", p.remainingCompositeText)
            pJson.put("rem_screen_off", p.remainingScreenOffText)
            pJson.put("is_shizuku_real_data", p.isShizukuRealData)
            pJson.put("app_count", p.appCount)
            pJson.put("trend_points_json", p.trendPointsJson)
            pJson.put("app_list_json", p.appListJson)
            pJson.put("background_power_watts", p.backgroundPowerWatts.toDouble())
            pJson.put("background_duration_text", p.backgroundDurationText)
            pJson.put("rem_background", p.remainingBackgroundText)
            pJson.put("screen_on_energy_wh", p.screenOnEnergyWh.toDouble())
            pJson.put("total_energy_wh", p.totalEnergyWh.toDouble())
            pJson.put("screen_off_energy_wh", p.screenOffEnergyWh.toDouble())
            pJson.put("background_energy_wh", p.backgroundEnergyWh.toDouble())
            pJson.put("is_completed", p.isCompleted)
            pJson.put("last_checkpoint_time", p.lastCheckpointTime)
            pJson.put("screen_off_awake_energy_wh", p.screenOffAwakeEnergyWh.toDouble())
            pJson.put("screen_off_deep_sleep_energy_wh", p.screenOffDeepSleepEnergyWh.toDouble())
            pJson.put("screen_off_awake_duration_ms", p.screenOffAwakeDurationMs)
            pJson.put("screen_off_deep_sleep_duration_ms", p.screenOffDeepSleepDurationMs)
            pJson.put("screen_off_awake_drain_mah", p.screenOffAwakeDrainMah.toDouble())
            pJson.put("screen_off_deep_sleep_drain_mah", p.screenOffDeepSleepDrainMah.toDouble())
            pJson.put("is_screen_off_decomposed", p.isScreenOffDecomposedAvailable)
            powerArray.put(pJson)
        }
        rootJson.put("power_usage_records", powerArray)

        return rootJson.toString(2)
    }


    /**
     * 将 JSON 字符串解析为 [BackupData] 实体对象。
     * 向后兼容版本 1 格式（当缺失充电或放电数组时回退为空列表）。
     *
     * @param jsonString 待解析的备份 JSON 字符串
     * @return 解析完成的 [BackupData] 对象
     * @throws IllegalArgumentException 当 JSON 格式异常时抛出
     */
    fun parseBackupJson(jsonString: String): BackupData {
        val rootJson = JSONObject(jsonString)
        val version = rootJson.optInt("version", 1)
        val appVersion = rootJson.optString("app_version", "1.0.0")
        val backupTime = rootJson.optString("backup_time", "")
        val backupTimestamp = rootJson.optLong("backup_timestamp", System.currentTimeMillis())

        val settingsJson = rootJson.optJSONObject("settings")
        val settings = if (settingsJson != null) {
            val timelineMetrics = if (settingsJson.has("timeline_saved_metrics")) {
                val array = settingsJson.optJSONArray("timeline_saved_metrics")
                if (array != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getString(i))
                    }
                    list
                } else null
            } else null

            val timelineChargingMetrics = if (settingsJson.has("timeline_saved_charging_metrics")) {
                val array = settingsJson.optJSONArray("timeline_saved_charging_metrics")
                if (array != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getString(i))
                    }
                    list
                } else null
            } else null

            BackupSettings(
                languageMode = if (settingsJson.has("language_mode")) settingsJson.getInt("language_mode") else null,
                themeMode = if (settingsJson.has("theme_mode")) settingsJson.getInt("theme_mode") else null,
                autoRefreshEnabled = if (settingsJson.has("auto_refresh_enabled")) settingsJson.getBoolean("auto_refresh_enabled") else null,
                refreshIntervalMs = if (settingsJson.has("refresh_interval_ms")) settingsJson.getLong("refresh_interval_ms") else null,
                chargeDischargeStatsEnabled = if (settingsJson.has("charge_discharge_stats_enabled")) settingsJson.getBoolean("charge_discharge_stats_enabled") else null,
                chargingKeepScreenOn = if (settingsJson.has("charging_keep_screen_on")) settingsJson.getBoolean("charging_keep_screen_on") else null,
                powerStatsMode = if (settingsJson.has("power_stats_mode")) settingsJson.getInt("power_stats_mode") else null,
                powerModeConfigured = if (settingsJson.has("power_mode_configured")) settingsJson.getBoolean("power_mode_configured") else null,
                shizukuUserDisabled = if (settingsJson.has("shizuku_user_disabled")) settingsJson.getBoolean("shizuku_user_disabled") else null,
                notificationDisplayEnabled = if (settingsJson.has("notification_display_enabled")) settingsJson.getBoolean("notification_display_enabled") else null,
                screenOnIntervalMs = if (settingsJson.has("screen_on_interval_ms")) settingsJson.getLong("screen_on_interval_ms") else null,
                screenOffIntervalMs = if (settingsJson.has("screen_off_interval_ms")) settingsJson.getLong("screen_off_interval_ms") else null,
                bootAutoStartEnabled = if (settingsJson.has("boot_auto_start_enabled")) settingsJson.getBoolean("boot_auto_start_enabled") else null,
                batteryServiceEnabled = if (settingsJson.has("battery_service_enabled")) settingsJson.getBoolean("battery_service_enabled") else null,
                floatingEnabled = if (settingsJson.has("floating_enabled")) settingsJson.getBoolean("floating_enabled") else null,
                floatingShowPower = if (settingsJson.has("floating_show_power")) settingsJson.getBoolean("floating_show_power") else null,
                floatingShowTemp = if (settingsJson.has("floating_show_temp")) settingsJson.getBoolean("floating_show_temp") else null,
                floatingAlignStatusBar = if (settingsJson.has("floating_align_status_bar")) settingsJson.getBoolean("floating_align_status_bar") else null,
                floatingPosX = if (settingsJson.has("floating_pos_x")) settingsJson.getInt("floating_pos_x") else null,
                floatingPosY = if (settingsJson.has("floating_pos_y")) settingsJson.getInt("floating_pos_y") else null,
                floatingPosXLand = if (settingsJson.has("floating_pos_x_land")) settingsJson.getInt("floating_pos_x_land") else null,
                floatingPosYLand = if (settingsJson.has("floating_pos_y_land")) settingsJson.getInt("floating_pos_y_land") else null,
                floatingHorizontalAlign = if (settingsJson.has("floating_horizontal_align")) settingsJson.getInt("floating_horizontal_align") else null,
                floatingOpacity = if (settingsJson.has("floating_opacity")) settingsJson.getInt("floating_opacity") else null,
                floatingThemeMode = if (settingsJson.has("floating_theme_mode")) settingsJson.getInt("floating_theme_mode") else null,
                floatingFontSize = if (settingsJson.has("floating_font_size")) settingsJson.getInt("floating_font_size") else null,
                floatingFontBold = if (settingsJson.has("floating_font_bold")) settingsJson.getBoolean("floating_font_bold") else null,
                floatingShowUnit = if (settingsJson.has("floating_show_unit")) settingsJson.getBoolean("floating_show_unit") else null,
                floatingShowBackground = if (settingsJson.has("floating_show_background")) settingsJson.getBoolean("floating_show_background") else null,
                floatingOffsetX = if (settingsJson.has("floating_offset_x")) settingsJson.getInt("floating_offset_x") else null,
                floatingPosPercentX = if (settingsJson.has("floating_pos_percent_x")) settingsJson.getDouble("floating_pos_percent_x").toFloat() else null,
                floatingPosPercentY = if (settingsJson.has("floating_pos_percent_y")) settingsJson.getDouble("floating_pos_percent_y").toFloat() else null,
                currentCalibrationMultiplier = if (settingsJson.has("current_calibration_multiplier")) settingsJson.getDouble("current_calibration_multiplier").toFloat() else null,
                currentCalibrationDualCell = if (settingsJson.has("current_calibration_dual_cell")) settingsJson.getBoolean("current_calibration_dual_cell") else null,
                currentCalibrationInvertPolarity = if (settingsJson.has("current_calibration_invert_polarity")) settingsJson.getBoolean("current_calibration_invert_polarity") else null,
                currentCalibrationCoulombFallback = if (settingsJson.has("current_calibration_coulomb_fallback")) settingsJson.getBoolean("current_calibration_coulomb_fallback") else null,
                powerDisplayUnit = if (settingsJson.has("power_display_unit")) settingsJson.getInt("power_display_unit") else null,
                powerUnitDialogDoNotShow = if (settingsJson.has("power_unit_dialog_donot_show")) settingsJson.getBoolean("power_unit_dialog_donot_show") else null,
                enableBackgroundStats = if (settingsJson.has("enable_background_stats")) settingsJson.getBoolean("enable_background_stats") else null,
                timelineSavedMetrics = timelineMetrics,
                timelineSavedChargingMetrics = timelineChargingMetrics
            )
        } else {
            BackupSettings()
        }


        // 解析检测历史快照
        val historyRecords = mutableListOf<HistoryRecord>()
        val recordsArray = rootJson.optJSONArray("history_records")
        if (recordsArray != null) {
            for (i in 0 until recordsArray.length()) {
                val item = recordsArray.getJSONObject(i)
                val id = item.optLong("id", System.currentTimeMillis() + i)
                val captureTime = item.optString("capture_time", "")
                val source = item.optString("source", "系统api")
                val category = item.optString("category", source)
                val note = if (item.has("note") && !item.isNull("note")) item.getString("note") else null
                val batteryHealth = if (item.has("battery_health") && !item.isNull("battery_health")) item.getDouble("battery_health").toFloat() else null
                val healthStatus = if (item.has("health_status") && !item.isNull("health_status")) item.getString("health_status") else null
                val level = if (item.has("level") && !item.isNull("level")) item.getInt("level") else null
                val status = if (item.has("status") && !item.isNull("status")) item.getString("status") else null
                val designCapacity = if (item.has("design_capacity") && !item.isNull("design_capacity")) item.getDouble("design_capacity").toFloat() else null
                val currentCapacity = if (item.has("current_capacity") && !item.isNull("current_capacity")) item.getDouble("current_capacity").toFloat() else null
                val fullChargeCapacity = if (item.has("full_charge_capacity") && !item.isNull("full_charge_capacity")) item.getDouble("full_charge_capacity").toFloat() else null
                val cycleCount = if (item.has("cycle_count") && !item.isNull("cycle_count")) item.getInt("cycle_count") else null
                val temperature = if (item.has("temperature") && !item.isNull("temperature")) item.getDouble("temperature").toFloat() else null
                val voltage = if (item.has("voltage") && !item.isNull("voltage")) item.getDouble("voltage").toFloat() else null
                val currentNow = if (item.has("current_now") && !item.isNull("current_now")) item.getDouble("current_now").toFloat() else null
                val powerWatts = if (item.has("power_watts") && !item.isNull("power_watts")) item.getDouble("power_watts").toFloat() else null
                val isDualCell = if (item.has("is_dual_cell") && !item.isNull("is_dual_cell")) item.getBoolean("is_dual_cell") else null
                val technology = if (item.has("technology") && !item.isNull("technology")) item.getString("technology") else null

                historyRecords.add(
                    HistoryRecord(
                        id = id,
                        captureTime = captureTime,
                        source = source,
                        category = category,
                        note = note,
                        batteryHealth = batteryHealth,
                        healthStatus = healthStatus,
                        level = level,
                        status = status,
                        designCapacity = designCapacity,
                        currentCapacity = currentCapacity,
                        fullChargeCapacity = fullChargeCapacity,
                        cycleCount = cycleCount,
                        temperature = temperature,
                        voltage = voltage,
                        currentNow = currentNow,
                        powerWatts = powerWatts,
                        isDualCell = isDualCell,
                        technology = technology
                    )
                )
            }
        }

        // 解析充电历史记录
        val chargingRecords = mutableListOf<ChargingHistoryRecord>()
        val chargingArray = rootJson.optJSONArray("charging_records")
        if (chargingArray != null) {
            for (i in 0 until chargingArray.length()) {
                val item = chargingArray.getJSONObject(i)
                chargingRecords.add(
                    ChargingHistoryRecord(
                        id = item.optLong("id", System.currentTimeMillis() + i),
                        recordTime = item.optString("record_time", ""),
                        startTimestamp = item.optLong("start_timestamp", 0L),
                        endTimestamp = item.optLong("end_timestamp", 0L),
                        durationMs = item.optLong("duration_ms", 0L),
                        startLevel = item.optInt("start_level", 0),
                        endLevel = item.optInt("end_level", 0),
                        levelGain = item.optInt("level_gain", 0),
                        chargedEnergyWh = item.optDouble("charged_energy_wh", 0.0).toFloat(),
                        avgPowerWatts = item.optDouble("avg_power_watts", 0.0).toFloat(),
                        maxPowerWatts = item.optDouble("max_power_watts", 0.0).toFloat(),
                        maxTemperature = item.optDouble("max_temperature", 0.0).toFloat(),
                        chargeType = item.optString("charge_type", ""),
                        screenOffDurationMs = item.optLong("screen_off_duration_ms", 0L),
                        screenOffLevelGain = item.optInt("screen_off_level_gain", 0),
                        screenOffEnergyWh = item.optDouble("screen_off_energy_wh", 0.0).toFloat(),
                        samplePointsJson = item.optString("sample_points_json", "")
                    )
                )
            }
        }

        // 解析放电耗电历史记录
        val powerUsageRecords = mutableListOf<PowerUsageRecord>()
        val powerArray = rootJson.optJSONArray("power_usage_records")
        if (powerArray != null) {
            for (i in 0 until powerArray.length()) {
                val item = powerArray.getJSONObject(i)
                powerUsageRecords.add(
                    PowerUsageRecord(
                        id = item.optLong("id", System.currentTimeMillis() + i),
                        recordTime = item.optString("record_time", ""),
                        levelPercent = item.optInt("level_percent", 0),
                        voltageVolts = item.optDouble("voltage_volts", 0.0).toFloat(),
                        temperature = item.optDouble("temperature", 0.0).toFloat(),
                        energyWh = item.optDouble("energy_wh", 0.0).toFloat(),
                        isCharging = item.optBoolean("is_charging", false),
                        avgPowerWatts = item.optDouble("avg_power_watts", 0.0).toFloat(),
                        screenOnPowerWatts = item.optDouble("screen_on_power_watts", 0.0).toFloat(),
                        screenOffPowerWatts = item.optDouble("screen_off_power_watts", 0.0).toFloat(),
                        screenOnDurationText = item.optString("screen_on_duration_text", ""),
                        screenOffDurationText = item.optString("screen_off_duration_text", ""),
                        totalDurationText = item.optString("total_duration_text", ""),
                        remainingScreenOnText = item.optString("rem_screen_on", ""),
                        remainingCompositeText = item.optString("rem_composite", ""),
                        remainingScreenOffText = item.optString("rem_screen_off", ""),
                        isShizukuRealData = item.optBoolean("is_shizuku_real_data", false),
                        appCount = item.optInt("app_count", 0),
                        trendPointsJson = item.optString("trend_points_json", "[]"),
                        appListJson = item.optString("app_list_json", "[]"),
                        backgroundPowerWatts = item.optDouble("background_power_watts", 0.0).toFloat(),
                        backgroundDurationText = item.optString("background_duration_text", ""),
                        remainingBackgroundText = item.optString("rem_background", ""),
                        screenOnEnergyWh = item.optDouble("screen_on_energy_wh", 0.0).toFloat(),
                        totalEnergyWh = item.optDouble("total_energy_wh", 0.0).toFloat(),
                        screenOffEnergyWh = item.optDouble("screen_off_energy_wh", 0.0).toFloat(),
                        backgroundEnergyWh = item.optDouble("background_energy_wh", 0.0).toFloat(),
                        isCompleted = item.optBoolean("is_completed", true),
                        lastCheckpointTime = item.optLong("last_checkpoint_time", 0L),
                        screenOffAwakeEnergyWh = item.optDouble("screen_off_awake_energy_wh", 0.0).toFloat(),
                        screenOffDeepSleepEnergyWh = item.optDouble("screen_off_deep_sleep_energy_wh", 0.0).toFloat(),
                        screenOffAwakeDurationMs = item.optLong("screen_off_awake_duration_ms", 0L),
                        screenOffDeepSleepDurationMs = item.optLong("screen_off_deep_sleep_duration_ms", 0L),
                        screenOffAwakeDrainMah = item.optDouble("screen_off_awake_drain_mah", 0.0).toFloat(),
                        screenOffDeepSleepDrainMah = item.optDouble("screen_off_deep_sleep_drain_mah", 0.0).toFloat(),
                        isScreenOffDecomposedAvailable = item.optBoolean("is_screen_off_decomposed", true)
                    )
                )
            }
        }

        return BackupData(
            version = version,
            appVersion = appVersion,
            backupTime = backupTime,
            backupTimestamp = backupTimestamp,
            settings = settings,
            historyRecords = historyRecords,
            chargingRecords = chargingRecords,
            powerUsageRecords = powerUsageRecords
        )
    }

    /**
     * 将应用设置以及电池快照、充电记录和放电记录导出并写入由 SAF 指定的目标 URI 文件中。
     *
     * @param context 应用程序上下文
     * @param uri 用户选定的目标保存文件 URI
     * @param historyRecords 待导出的电池检测历史快照列表
     * @param chargingRecords 待导出的充电历史记录列表
     * @param powerUsageRecords 待导出的放电耗电历史记录列表
     * @return 包含各维度成功导出数量的 [BackupRestoreSummary] 结果包装
     */
    fun exportBackupToUri(
        context: Context,
        uri: Uri,
        historyRecords: List<HistoryRecord>,
        chargingRecords: List<ChargingHistoryRecord> = emptyList(),
        powerUsageRecords: List<PowerUsageRecord> = emptyList()
    ): Result<BackupRestoreSummary> {
        return try {
            val jsonContent = generateBackupJson(context, historyRecords, chargingRecords, powerUsageRecords)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonContent)
                    writer.flush()
                }
            } ?: return Result.failure(Exception("无法打开文件写入流"))
            Result.success(
                BackupRestoreSummary(
                    historyCount = historyRecords.size,
                    chargingCount = chargingRecords.size,
                    powerUsageCount = powerUsageRecords.size
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从用户选定的备份文件 URI 中读取内容并解析出 [BackupData] 元数据供预览。
     *
     * @param context 应用程序上下文
     * @param uri 用户选定的备份文件 URI
     * @return 包含解析结果 [BackupData] 的 [Result] 包装
     */
    fun importBackupPreview(context: Context, uri: Uri): Result<BackupData> {
        return try {
            val stringBuilder = java.lang.StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        stringBuilder.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
            } ?: return Result.failure(Exception("无法打开文件读取流"))

            val jsonStr = stringBuilder.toString()
            if (jsonStr.isBlank()) {
                return Result.failure(Exception("备份文件内容为空"))
            }

            val backupData = parseBackupJson(jsonStr)
            Result.success(backupData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 执行数据恢复：将 [BackupData] 中的全量历史记录写入数据库并按需同步所有应用偏好配置。
     *
     * @param context 应用程序上下文
     * @param backupData 待恢复的备份数据实体对象
     * @param isOverwrite 是否采用覆盖模式（true 为清空当前数据库后写入，false 为合并去重写入）
     * @param restoreSettings 是否同步恢复应用偏好设置
     * @return 包含各类历史记录成功恢复条数的 [BackupRestoreSummary] 包装
     */
    fun restoreBackup(
        context: Context,
        backupData: BackupData,
        isOverwrite: Boolean,
        restoreSettings: Boolean
    ): Result<BackupRestoreSummary> {
        return try {
            // 1. 恢复电池检测快照
            val historyDb = HistoryDbHelper.getInstance(context)
            val restoredHistoryCount = if (isOverwrite) {
                historyDb.replaceRecords(backupData.historyRecords)
            } else {
                historyDb.insertRecords(backupData.historyRecords)
            }

            // 2. 恢复充电历史记录
            val chargingDb = ChargingHistoryDbHelper.getInstance(context)
            val restoredChargingCount = if (isOverwrite) {
                chargingDb.replaceRecords(backupData.chargingRecords)
            } else {
                chargingDb.insertRecords(backupData.chargingRecords)
            }

            // 3. 恢复放电耗电历史记录
            val powerDb = PowerUsageDbHelper.getInstance(context)
            val restoredPowerCount = if (isOverwrite) {
                powerDb.replaceRecords(backupData.powerUsageRecords)
            } else {
                powerDb.insertRecords(backupData.powerUsageRecords)
            }

            // 4. 按需还原现有全部应用偏好设置
            if (restoreSettings) {
                val settings = backupData.settings
                val appPrefs = context.getSharedPreferences("battery_app_settings", Context.MODE_PRIVATE)
                val appEditor = appPrefs.edit()

                // 主题模式
                settings.themeMode?.let {
                    appEditor.putInt("theme_mode", it)
                    AppCompatDelegate.setDefaultNightMode(it)
                }

                // 语言模式
                settings.languageMode?.let {
                    LanguageManager.setLanguageMode(context, it)
                }

                // Shizuku 停用偏好
                settings.shizukuUserDisabled?.let {
                    ShizukuManager.setUserDisabled(context, it)
                }

                // 旧版兼容配置
                appEditor.apply()

                // 充放电与后台常驻监控服务设置
                settings.chargeDischargeStatsEnabled?.let {
                    BatteryMonitorService.setChargeDischargeStatsEnabled(context, it)
                }
                settings.notificationDisplayEnabled?.let {
                    BatteryMonitorService.setNotificationDisplayEnabled(context, it)
                }
                settings.screenOnIntervalMs?.let {
                    BatteryMonitorService.setScreenOnIntervalMs(context, it)
                }
                settings.screenOffIntervalMs?.let {
                    BatteryMonitorService.setScreenOffIntervalMs(context, it)
                }
                settings.bootAutoStartEnabled?.let {
                    BatteryMonitorService.setBootAutoStartEnabled(context, it)
                }
                BatteryMonitorService.updateNotificationVisibility(context)

                // 服务总开关
                settings.batteryServiceEnabled?.let {
                    BatteryMonitorService.setServiceEnabled(context, it)
                }

                // 充电常亮开关
                settings.chargingKeepScreenOn?.let {
                    val chargingPrefs = context.getSharedPreferences("charging_stats_prefs", Context.MODE_PRIVATE)
                    chargingPrefs.edit().putBoolean("pref_charging_keep_screen_on", it).apply()
                }

                // 耗电统计模式与单位偏好
                settings.powerStatsMode?.let {
                    val powerManager = PowerUsageManager.getInstance(context)
                    powerManager.setSelectedMode(it)
                }
                val powerPrefs = context.getSharedPreferences("power_stats_prefs", Context.MODE_PRIVATE)
                val powerEditor = powerPrefs.edit()
                settings.powerModeConfigured?.let {
                    powerEditor.putBoolean("pref_power_mode_configured", it)
                }
                settings.powerDisplayUnit?.let {
                    powerEditor.putInt(PowerDisplayUnit.PREF_KEY_POWER_DISPLAY_UNIT, it)
                }
                settings.powerUnitDialogDoNotShow?.let {
                    powerEditor.putBoolean(PowerDisplayUnit.PREF_KEY_POWER_UNIT_DIALOG_DONOT_SHOW, it)
                }
                settings.enableBackgroundStats?.let {
                    powerEditor.putBoolean(PowerUsageFragment.PREF_KEY_ENABLE_BACKGROUND_STATS, it)
                }
                powerEditor.apply()

                // 桌面全局悬浮窗配置
                val floatingPrefs = context.getSharedPreferences("floating_window_prefs", Context.MODE_PRIVATE)
                val floatingEditor = floatingPrefs.edit()
                settings.floatingEnabled?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_ENABLED, it) }
                settings.floatingShowPower?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_SHOW_POWER, it) }
                settings.floatingShowTemp?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_SHOW_TEMP, it) }
                settings.floatingAlignStatusBar?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_ALIGN_STATUS_BAR, it) }
                settings.floatingPosX?.let { floatingEditor.putInt(FloatingWindowManager.KEY_POS_X, it) }
                settings.floatingPosY?.let { floatingEditor.putInt(FloatingWindowManager.KEY_POS_Y, it) }
                settings.floatingPosXLand?.let { floatingEditor.putInt(FloatingWindowManager.KEY_POS_X_LAND, it) }
                settings.floatingPosYLand?.let { floatingEditor.putInt(FloatingWindowManager.KEY_POS_Y_LAND, it) }
                settings.floatingHorizontalAlign?.let { floatingEditor.putInt(FloatingWindowManager.KEY_HORIZONTAL_ALIGN, it) }
                settings.floatingOpacity?.let { floatingEditor.putInt(FloatingWindowManager.KEY_OPACITY, it) }
                settings.floatingThemeMode?.let { floatingEditor.putInt(FloatingWindowManager.KEY_THEME_MODE, it) }
                settings.floatingFontSize?.let { floatingEditor.putInt(FloatingWindowManager.KEY_FONT_SIZE, it) }
                settings.floatingFontBold?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_FONT_BOLD, it) }
                settings.floatingShowUnit?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_SHOW_UNIT, it) }
                settings.floatingShowBackground?.let { floatingEditor.putBoolean(FloatingWindowManager.KEY_SHOW_BACKGROUND, it) }
                settings.floatingOffsetX?.let { floatingEditor.putInt(FloatingWindowManager.KEY_OFFSET_X, it) }
                settings.floatingPosPercentX?.let { floatingEditor.putFloat(FloatingWindowManager.KEY_POS_PERCENT_X, it) }
                settings.floatingPosPercentY?.let { floatingEditor.putFloat(FloatingWindowManager.KEY_POS_PERCENT_Y, it) }
                floatingEditor.apply()

                // 若具备悬浮窗系统权限，按恢复的开关状态安全驱动悬浮窗
                if (FloatingWindowManager.canDrawOverlays(context)) {
                    val isFloatingActive = settings.floatingEnabled ?: FloatingWindowManager.isFloatingEnabled(context)
                    if (isFloatingActive) {
                        FloatingWindowManager.show(context)
                        FloatingWindowManager.updatePositionAndAppearance(context)
                        if (BatteryMonitorService.shouldServiceRun(context)) {
                            BatteryMonitorService.start(context)
                        }
                    } else {
                        FloatingWindowManager.hide(context)
                    }
                }

                // 硬件电流校准配置
                settings.currentCalibrationMultiplier?.let {
                    CurrentCalibrationManager.setMultiplier(context, it)
                }
                settings.currentCalibrationDualCell?.let {
                    CurrentCalibrationManager.setDualCellEnabled(context, it)
                }
                settings.currentCalibrationInvertPolarity?.let {
                    CurrentCalibrationManager.setInvertPolarity(context, it)
                }
                settings.currentCalibrationCoulombFallback?.let {
                    CurrentCalibrationManager.setCoulombFallbackEnabled(context, it)
                }

                // 趋势图指标配置（放电与充电时间轴）
                settings.timelineSavedMetrics?.let { metricsList ->
                    val timelinePrefs = context.getSharedPreferences("timeline_metrics_prefs", Context.MODE_PRIVATE)
                    timelinePrefs.edit().putStringSet("saved_timeline_metrics", metricsList.toSet()).apply()
                }
                settings.timelineSavedChargingMetrics?.let { metricsList ->
                    val timelinePrefs = context.getSharedPreferences("timeline_metrics_prefs", Context.MODE_PRIVATE)
                    timelinePrefs.edit().putStringSet("saved_charging_timeline_metrics", metricsList.toSet()).apply()
                }

                // 统一依据恢复后的全局偏好设置裁决后台常驻监控服务生命周期
                if (BatteryMonitorService.shouldServiceRun(context)) {
                    BatteryMonitorService.start(context)
                } else {
                    BatteryMonitorService.stop(context)
                }
            }

            Result.success(
                BackupRestoreSummary(
                    historyCount = restoredHistoryCount,
                    chargingCount = restoredChargingCount,
                    powerUsageCount = restoredPowerCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val BACKUP_SCHEMA_VERSION = 4

        @Volatile
        private var instance: BackupManager? = null

        /**
         * 获取 [BackupManager] 单例实例。
         *
         * @return 单例 [BackupManager] 对象
         */
        fun getInstance(): BackupManager {
            return instance ?: synchronized(this) {
                instance ?: BackupManager().also { instance = it }
            }
        }
    }
}

