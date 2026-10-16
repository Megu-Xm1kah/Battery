package com.battery.analysis.domain.estimator

/**
 * 电池物理量纲与数据源单位自适应归一化工具。
 * 负责将不同硬件驱动、Android 框架及厂商定制 ROM 的电荷量、电压、电流等读数
 * 依据物理上下文与量级合理性校验（Physical Magnitude Validation），安全归一化为领域标准单位。
 */
object BatteryUnitNormalizer {

    /**
     * 将硬件电荷计数器读数自适应归一化为毫安时（mAh）。
     *
     * 物理背景与兼容性：
     * 1. Google 官方 Android API 规范定义 BATTERY_PROPERTY_CHARGE_COUNTER 单位为微安时（uAh）；
     * 2. 然而部分国内厂商（OPPO/OnePlus、vivo 等定制 ROM）驱动底层直接上报毫安时（mAh）；
     * 3. 严禁使用单一写死阈值猜测单位：
     *    - 若写死除以 1000，则在上报 mAh 的设备上，24% 电量下的 1896 会被误缩小 1000 倍变成 1.896 mAh，导致电池总容量缩水至 7.9 mAh、放电速度暴增至 3800%/h；
     *    - 若写死 < 100,000，则在标准 uAh 设备极低电量（如 80,000 uAh = 80 mAh）时会被误放大 1000 倍为 80,000 mAh。
     *
     * 物理上下文判定准则（Physical Magnitude Validation）：
     * - 若提供有效基准容量 effectiveCapacityMah（如 5000 mAh）：
     *   如果 rawCounter > effectiveCapacityMah * 1.5，说明数值明显大于电池物理总容量，其物理量纲必定为 uAh，必须除以 1000；
     *   如果 rawCounter <= effectiveCapacityMah * 1.2，说明数值已落在当前剩余电荷合理区间内，其单位本来就是 mAh，绝不能再除以 1000。
     * - 若无基准容量但有当前电量百分比 batteryPercent（1..100）：
     *   分别评估假定为 uAh 与 mAh 时的等效总容量：
     *   C_uAh = (rawCounter / 1000.0) / (percent / 100.0)
     *   C_mAh = rawCounter.toDouble() / (percent / 100.0)
     *   现代智能手机电池容量通常在 1000 mAh 至 15000 mAh 之间。
     *   若 C_uAh < 500 mAh（纽扣电池级别），而 C_mAh 在 1000..20000 mAh，说明按 uAh 折算严重违背物理规律，必定为厂商直报的 mAh；
     *   反之若 C_mAh > 25000 mAh（电动车级别），而 C_uAh 在 1000..20000 mAh，说明必定为标准 uAh 读数。
     * - 极端无上下文兜底（无基准容量且无电量）：严格遵循 Android 标准规范，默认单位为 uAh，统一除以 1000.0。
     *
     * @param rawCounter 硬件底层读取的原始电荷计数器读数（支持 uAh 或厂商上报的 mAh，可选）
     * @param effectiveCapacityMah 可选的设备已知基准容量（单位：mAh）
     * @param batteryPercent 可选的当前电池电量百分比（取值范围 1.0 至 100.0）
     * @return 归一化后的毫安时数值（单位：mAh），若原始输入无效则返回 null
     */
    fun normalizeChargeCounterToMah(
        rawCounter: Long?,
        effectiveCapacityMah: Double? = null,
        batteryPercent: Double? = null
    ): Double? {
        if (rawCounter == null || rawCounter <= 0L) return null

        // 1. 若具有有效基准容量参考（如 5000 mAh）
        if (effectiveCapacityMah != null && effectiveCapacityMah > 0.0) {
            // 若原始读数显著大于电池总容量（如 1,896,000 > 5000 * 1.5），必定为 uAh，必须除以 1000
            return if (rawCounter > effectiveCapacityMah * 1.5) {
                rawCounter.toDouble() / 1000.0
            } else {
                // 若原始读数小于等于电池总容量合理上限，说明厂商底层直接上报了 mAh，绝不能再除以 1000
                rawCounter.toDouble()
            }
        }

        // 2. 若无基准容量但有电量百分比，通过反推等效满电容量评估物理合理性
        if (batteryPercent != null && batteryPercent in 1.0..100.0) {
            val ratio = batteryPercent / 100.0
            val asUahFullMah = (rawCounter.toDouble() / 1000.0) / ratio
            val asMahFullMah = rawCounter.toDouble() / ratio

            // 现代手机电池容量通常在 1000 mAh ~ 15000 mAh
            // 若作为 uAh 计算出的总容量严重偏离正常物理手机电池（例如 7.9 mAh < 500 mAh），而作为 mAh 合理，则按 mAh
            if (asUahFullMah < 500.0 && asMahFullMah in 1000.0..20000.0) {
                return rawCounter.toDouble()
            }
            // 若作为 mAh 计算出的总容量过大（例如 > 25000 mAh），而作为 uAh 合理，则按 uAh
            if (asMahFullMah > 25000.0 && asUahFullMah in 1000.0..20000.0) {
                return rawCounter.toDouble() / 1000.0
            }
        }

        // 3. 无上下文极端兜底：遵循 Android 官方标准规范，原始单位为 uAh，严格除以 1000
        return rawCounter.toDouble() / 1000.0
    }
}
