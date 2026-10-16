package com.battery.analysis.domain.estimator

import com.battery.analysis.domain.estimator.model.DataQuality
import com.battery.analysis.domain.estimator.model.DischargeInterval
import com.battery.analysis.manager.PowerOverviewStats
import com.battery.analysis.model.PowerUsageRecord

/**
 * 历史放电采样与物理区间规范化归一器。
 * 负责从设备本地持久化的历史放电快照 [PowerUsageRecord] 及当前正在进行的活跃放电会话 [PowerOverviewStats] 中，
 * 按时间倒序解析并提取不重叠、时间单调的有效放电物理区间序列，
 * 严格过滤充电记录与异常数据，并在跨越 168 小时（7 天物理使用时间）边界时执行精确的物理等比裁剪，
 * 确保各区间放电功率物理守恒、能量严格守恒。
 */
class PowerSampleNormalizer {

    companion object {
        /** 7 天累积物理毫秒时长常量（7 * 24 * 3600 * 1000 毫秒 = 168 小时） */
        const val SEVEN_DAYS_MS: Long = 7L * 24L * 3600L * 1000L
    }

    /**
     * 从当前实时放电会话及历史归档记录中提取并构建 168 小时有效放电物理区间列表。
     *
     * 处理逻辑：
     * 1. 优先纳入最新的当前活跃放电会话（若存在且包含有效时长与能耗）；
     * 2. 倒序遍历合规历史记录（排除充电记录与异常未来时间戳）；
     * 3. 会话细分为亮屏活跃、息屏唤醒与息屏深度睡眠子区间，真实呈现各工况物理能耗；
     * 4. 累计物理放电时长达到 168 小时上限时，对边界记录进行等比裁剪，并立即终止遍历。
     *
     * @param currentOverview 当前正在进行的活跃放电会话概览统计（若无传入 null）
     * @param allRecords 设备本地持久化的历史放电记录列表（已默认按时间倒序排列）
     * @param maxTargetDurationMs 最大累积物理时长上限（单位：毫秒，默认 168 小时）
     * @param baseTimestamp 统计基准时间戳（毫秒，默认当前系统时间）
     * @return 经过边界裁剪与物理归一化后的有效放电区间列表 [List]<[DischargeInterval]>
     */
    fun extractDischargeIntervals(
        currentOverview: PowerOverviewStats?,
        allRecords: List<PowerUsageRecord>,
        maxTargetDurationMs: Long = SEVEN_DAYS_MS,
        baseTimestamp: Long = System.currentTimeMillis()
    ): List<DischargeInterval> {
        val intervals = mutableListOf<DischargeInterval>()
        var accumulatedDurationMs = 0L

        /**
         * 内部辅助函数：向结果集合中安全追加物理区间，若超出总时长上限则执行物理等比裁剪。
         *
         * @param interval 待追加的完整放电物理区间
         * @return 追加后是否已达到或超过目标总时长上限（true 为已满，无需继续遍历）
         */
        fun tryAppendInterval(interval: DischargeInterval): Boolean {
            if (interval.durationMs <= 0L || interval.energyWh <= 0.0 || !interval.powerWatts.isFinite()) {
                return false
            }
            val remainMs = maxTargetDurationMs - accumulatedDurationMs
            if (remainMs <= 0L) {
                return true
            }

            if (interval.durationMs <= remainMs) {
                intervals.add(interval)
                accumulatedDurationMs += interval.durationMs
            } else {
                // 跨越 168 小时边界，执行精确物理等比裁剪
                val ratio = remainMs.toDouble() / interval.durationMs.toDouble()
                val clippedEnergy = interval.energyWh * ratio
                val clippedInterval = DischargeInterval(
                    startTimeMs = interval.startTimeMs,
                    endTimeMs = interval.startTimeMs + remainMs,
                    durationMs = remainMs,
                    energyWh = clippedEnergy,
                    powerWatts = interval.powerWatts, // 平均功率保持守恒
                    isScreenOn = interval.isScreenOn,
                    quality = interval.quality
                )
                intervals.add(clippedInterval)
                accumulatedDurationMs += remainMs
            }
            return accumulatedDurationMs >= maxTargetDurationMs
        }

        // 1. 优先解构当前正在进行的活跃会话
        if (currentOverview != null) {
            val now = baseTimestamp
            val onDur = currentOverview.screenOnDurationMs
            val onEnergy = if (currentOverview.screenOnEnergyWh > 0f) {
                currentOverview.screenOnEnergyWh.toDouble()
            } else if (currentOverview.screenOnPowerWatts > 0f && onDur > 0L) {
                currentOverview.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            if (onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                val fullAppend = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = now - onDur,
                        endTimeMs = now,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID
                    )
                )
                if (fullAppend) return intervals
            }

            val offDur = currentOverview.screenOffDurationMs
            val offEnergy = if (currentOverview.screenOffEnergyWh > 0f) {
                currentOverview.screenOffEnergyWh.toDouble()
            } else if (currentOverview.screenOffPowerWatts > 0f && offDur > 0L) {
                currentOverview.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
            } else 0.0

            if (offDur > 0L && offEnergy > 0.0) {
                val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                val fullAppend = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = now - onDur - offDur,
                        endTimeMs = now - onDur,
                        durationMs = offDur,
                        energyWh = offEnergy,
                        powerWatts = pOff,
                        isScreenOn = false,
                        quality = DataQuality.VALID
                    )
                )
                if (fullAppend) return intervals
            }
        }

        // 2. 倒序解构历史归档记录
        val eligibleRecords = allRecords.filter { record ->
            !record.isCharging && record.id <= (baseTimestamp + 60_000L)
        }

        for (record in eligibleRecords) {
            // 若历史记录中包含未完结草稿且当前已存在实时会话，跳过旧草稿以最新实时数据为准
            if (!record.isCompleted && currentOverview != null) {
                continue
            }

            val recEndTs = record.id
            val onDur = record.getScreenOnDurationMs()
            val onEnergy = if (record.screenOnEnergyWh > 0f) {
                record.screenOnEnergyWh.toDouble()
            } else if (record.screenOnPowerWatts > 0f && onDur > 0L) {
                record.screenOnPowerWatts.toDouble() * (onDur / 3600000.0)
            } else 0.0

            if (onDur > 0L && onEnergy > 0.0) {
                val pOn = onEnergy / (onDur.toDouble() / 3600000.0)
                val isFull = tryAppendInterval(
                    DischargeInterval(
                        startTimeMs = recEndTs - onDur,
                        endTimeMs = recEndTs,
                        durationMs = onDur,
                        energyWh = onEnergy,
                        powerWatts = pOn,
                        isScreenOn = true,
                        quality = DataQuality.VALID
                    )
                )
                if (isFull) break
            }

            // 息屏细分：若具备深度休眠与唤醒的细分数据，则拆分为两个更具物理代表性的独立区间
            val hasDecomposed = record.isScreenOffDecomposedAvailable &&
                    (record.screenOffAwakeDurationMs > 0L || record.screenOffDeepSleepDurationMs > 0L)

            if (hasDecomposed) {
                val awakeDur = record.screenOffAwakeDurationMs
                val awakeEnergy = record.screenOffAwakeEnergyWh.toDouble()
                if (awakeDur > 0L && awakeEnergy > 0.0) {
                    val pAwake = awakeEnergy / (awakeDur.toDouble() / 3600000.0)
                    val isFull = tryAppendInterval(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - awakeDur,
                            endTimeMs = recEndTs - onDur,
                            durationMs = awakeDur,
                            energyWh = awakeEnergy,
                            powerWatts = pAwake,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isFull) break
                }

                val sleepDur = record.screenOffDeepSleepDurationMs
                val sleepEnergy = record.screenOffDeepSleepEnergyWh.toDouble()
                if (sleepDur > 0L && sleepEnergy > 0.0) {
                    val pSleep = sleepEnergy / (sleepDur.toDouble() / 3600000.0)
                    val isFull = tryAppendInterval(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - awakeDur - sleepDur,
                            endTimeMs = recEndTs - onDur - awakeDur,
                            durationMs = sleepDur,
                            energyWh = sleepEnergy,
                            powerWatts = pSleep,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isFull) break
                }
            } else {
                // 无细分时作为综合息屏区间纳入
                val offDur = record.getScreenOffDurationMs()
                val offEnergy = if (record.screenOffEnergyWh > 0f) {
                    record.screenOffEnergyWh.toDouble()
                } else if (record.screenOffPowerWatts > 0f && offDur > 0L) {
                    record.screenOffPowerWatts.toDouble() * (offDur / 3600000.0)
                } else 0.0

                if (offDur > 0L && offEnergy > 0.0) {
                    val pOff = offEnergy / (offDur.toDouble() / 3600000.0)
                    val isFull = tryAppendInterval(
                        DischargeInterval(
                            startTimeMs = recEndTs - onDur - offDur,
                            endTimeMs = recEndTs - onDur,
                            durationMs = offDur,
                            energyWh = offEnergy,
                            powerWatts = pOff,
                            isScreenOn = false,
                            quality = DataQuality.VALID
                        )
                    )
                    if (isFull) break
                }
            }
        }

        return intervals
    }
}
