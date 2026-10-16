package com.battery.analysis.model

/**
 * 应用偏好设置备份数据实体类。
 * 完整承载应用内所有用户设置项，包含多语言、主题、充放电统计、屏幕常亮、耗电模式、Shizuku 偏好、
 * 保活监控与采样频率、后台服务总开关、桌面全局悬浮窗全量外观与定位参数、硬件电流校准配置、
 * 耗电量显示单位以及时间轴趋势图指标勾选等配置。
 *
 * @property languageMode 语言设置模式（0 跟随系统，1 简体中文，2 English）
 * @property themeMode 主题模式（-1 跟随系统，1 浅色模式，2 深色模式）
 * @property autoRefreshEnabled 是否开启实时自动刷新（保留旧版本兼容）
 * @property refreshIntervalMs 自动刷新时间间隔毫秒值（保留旧版本兼容）
 * @property chargeDischargeStatsEnabled 是否启用充、放电统计功能
 * @property chargingKeepScreenOn 充电/耗电详情页是否保持屏幕常亮
 * @property powerStatsMode 耗电统计检测模式（0 为 Shizuku 模式，1 为标准模式）
 * @property powerModeConfigured 耗电统计检测模式是否已完成初始化引导配置
 * @property shizukuUserDisabled 用户是否主动停用 Shizuku 提权读取
 * @property notificationDisplayEnabled 常驻通知栏监控是否开启显示
 * @property screenOnIntervalMs 亮屏监控刷新间隔（毫秒，-1 为不采样）
 * @property screenOffIntervalMs 息屏待机采样间隔（毫秒，0 为智能省电，-1 为不采样）
 * @property bootAutoStartEnabled 设备重启后是否自动启动后台监控服务
 * @property batteryServiceEnabled 常驻后台电池监控服务总开关是否启用
 * @property floatingEnabled 桌面实时监控全局悬浮窗总开关是否启用
 * @property floatingShowPower 悬浮窗是否显示实时功率数值
 * @property floatingShowTemp 悬浮窗是否显示实时电池温度
 * @property floatingAlignStatusBar 悬浮窗是否吸附状态栏定位
 * @property floatingPosX 悬浮窗竖屏 X 轴绝对物理坐标像素值
 * @property floatingPosY 悬浮窗竖屏 Y 轴绝对物理坐标像素值
 * @property floatingPosXLand 悬浮窗横屏 X 轴绝对物理坐标像素值
 * @property floatingPosYLand 悬浮窗横屏 Y 轴绝对物理坐标像素值
 * @property floatingHorizontalAlign 悬浮窗水平停靠对齐方式（0 靠左，1 居中，2 靠右，3 自定义）
 * @property floatingOpacity 悬浮窗背景与文字整体不透明度（0~100）
 * @property floatingThemeMode 悬浮窗色彩主题（0 跟随系统，1 深色，2 浅色）
 * @property floatingFontSize 悬浮窗文字字号大小（单位：sp）
 * @property floatingFontBold 悬浮窗文字是否开启加粗显示
 * @property floatingShowUnit 悬浮窗是否显示物理单位后缀（W / ℃）
 * @property floatingShowBackground 悬浮窗是否渲染半透明深色圆角胶囊背景边框
 * @property floatingOffsetX 悬浮窗状态栏水平偏移微调微积分值（像素）
 * @property floatingPosPercentX 悬浮窗水平停靠可用区间自适应百分比（0.0f~1.0f）
 * @property floatingPosPercentY 悬浮窗垂直停靠可用区间自适应百分比（0.0f~1.0f）
 * @property currentCalibrationMultiplier 硬件电流校准倍率（支持 10 的倍数倍率档位，如 -1000f~1000f）
 * @property currentCalibrationDualCell 是否开启双电芯串/并联电流读数翻倍校准
 * @property currentCalibrationInvertPolarity 是否手动开启电流充放电极性反转
 * @property currentCalibrationCoulombFallback 是否开启底层库仑计电荷差分推算补偿
 * @property powerDisplayUnit 耗电量显示单位（0 为瓦时 Wh，1 为毫安时 mAh）
 * @property powerUnitDialogDoNotShow 切换耗电量显示单位时是否不再弹出确认提示对话框
 * @property enableBackgroundStats 是否启用后台应用耗电明细统计与拆分
 * @property timelineSavedMetrics 功耗时间轴底部趋势图用户自定义选中的指标名称列表
 * @property timelineSavedChargingMetrics 充电时间轴底部趋势图用户自定义选中的指标名称列表
 */
data class BackupSettings(
    val languageMode: Int? = null,
    val themeMode: Int? = null,
    val autoRefreshEnabled: Boolean? = null,
    val refreshIntervalMs: Long? = null,
    val chargeDischargeStatsEnabled: Boolean? = null,
    val chargingKeepScreenOn: Boolean? = null,
    val powerStatsMode: Int? = null,
    val powerModeConfigured: Boolean? = null,
    val shizukuUserDisabled: Boolean? = null,
    val notificationDisplayEnabled: Boolean? = null,
    val screenOnIntervalMs: Long? = null,
    val screenOffIntervalMs: Long? = null,
    val bootAutoStartEnabled: Boolean? = null,
    val batteryServiceEnabled: Boolean? = null,
    val floatingEnabled: Boolean? = null,
    val floatingShowPower: Boolean? = null,
    val floatingShowTemp: Boolean? = null,
    val floatingAlignStatusBar: Boolean? = null,
    val floatingPosX: Int? = null,
    val floatingPosY: Int? = null,
    val floatingPosXLand: Int? = null,
    val floatingPosYLand: Int? = null,
    val floatingHorizontalAlign: Int? = null,
    val floatingOpacity: Int? = null,
    val floatingThemeMode: Int? = null,
    val floatingFontSize: Int? = null,
    val floatingFontBold: Boolean? = null,
    val floatingShowUnit: Boolean? = null,
    val floatingShowBackground: Boolean? = null,
    val floatingOffsetX: Int? = null,
    val floatingPosPercentX: Float? = null,
    val floatingPosPercentY: Float? = null,
    val currentCalibrationMultiplier: Float? = null,
    val currentCalibrationDualCell: Boolean? = null,
    val currentCalibrationInvertPolarity: Boolean? = null,
    val currentCalibrationCoulombFallback: Boolean? = null,
    val powerDisplayUnit: Int? = null,
    val powerUnitDialogDoNotShow: Boolean? = null,
    val enableBackgroundStats: Boolean? = null,
    val timelineSavedMetrics: List<String>? = null,
    val timelineSavedChargingMetrics: List<String>? = null
)

/**
 * 数据备份与恢复计数汇总实体类。
 * 记录电池检测快照、充电历史与放电历史的条目数量。
 *
 * @property historyCount 电池检测快照记录条数
 * @property chargingCount 充电历史会话记录条数
 * @property powerUsageCount 放电耗电历史记录条数
 */
data class BackupRestoreSummary(
    val historyCount: Int = 0,
    val chargingCount: Int = 0,
    val powerUsageCount: Int = 0
) {
    /**
     * 计算三类记录的总条数。
     *
     * @return 历史记录总条目数量
     */
    fun getTotalCount(): Int {
        return historyCount + chargingCount + powerUsageCount
    }
}

/**
 * 电池数据备份文件根结构实体类。
 * 承载备份协议版本、应用元信息、全量应用偏好配置，以及电池快照、充电历史和放电历史记录列表。
 * 向下兼容协议版本 1、版本 2 与版本 3，当前最新版本为 4。
 *
 * @property version 备份协议版本号（当前最新为 4）
 * @property appVersion 导出该备份的应用版本名称
 * @property backupTime 格式化的备份生成时间（例如 "2026-08-19 13:30:00"）
 * @property backupTimestamp 备份生成时的时间戳毫秒值
 * @property settings 应用偏好配置
 * @property historyRecords 电池检测历史快照列表
 * @property chargingRecords 充电历史会话记录列表
 * @property powerUsageRecords 放电耗电历史记录列表
 */
data class BackupData(
    val version: Int = 4,
    val appVersion: String,
    val backupTime: String,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val settings: BackupSettings,
    val historyRecords: List<HistoryRecord> = emptyList(),
    val chargingRecords: List<ChargingHistoryRecord> = emptyList(),
    val powerUsageRecords: List<PowerUsageRecord> = emptyList()
)

