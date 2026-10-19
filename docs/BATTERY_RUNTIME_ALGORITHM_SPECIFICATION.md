# 电池分析系统：满电续航与剩余续航算法架构与计算逻辑白皮书 (RuntimeModel v2.1)

本文档系统性阐述 **Battery** 项目中电池理论可用时长预测引擎的核心设计原理、底层数学与物理模型、工况状态机、多时间尺度自适应融合机制以及异常防御体系。

---

## 1. 概述与核心哲学

在移动设备能耗建模中，传统粗放式的“电量计百分比简单换算”往往存在启动尖峰畸变、待机微扰误触发、重载被误杀降权、以及极端温度下严重虚标等固有缺陷。本项目构建了完全基于物理做功守恒的统一续航预测体系：

1. **严格物理守恒（Physical Conservation）**：
   续航时长的物理本质是能量与功率的商，系统所有推导严格遵循物理做功定律：
   $$T = \frac{E_{\text{usable}}}{P_{\text{predicted}}}$$
   严禁脱离实际物理能量与功率，杜绝机械线性推算。
2. **零虚构原则（Zero-Mock & Zero-Fake Data）**：
   严禁捏造假数据、虚拟占位数据或人为的上下限强制钳位（clamp/coerce）。当硬件寄存器不可读或历史统计样本不足时，系统如实向下游返回 `null` 或标识为 `UNAVAILABLE`，界面真实反映“数据不足/未获取”，绝不合成兜底虚假数据。
3. **忠实物理直读与杜绝二次温度惩罚**：
   现代智能手机硬件电量计芯片（如 TI 阻抗追踪算法、高通 PMIC）在底层驱动中已动态包含了电芯温度、内阻与截止电压对可用电量的物理补偿。系统在应用层默认忠实反映硬件电量计直读，不叠加未经设备标定的通用硬编码 $K_T$ 折损，杜绝双重扣除。
4. **两套体系解耦并列架构**：
   - **满电理论续航（FullChargeRuntime v2.1）**：面向宏观设备能力评估，基于滚动 168 小时（7 天自然墙钟时间）真实放电行为库，通过工况分类（Regime Classification）与时间概率分布推导出的典型使用条件下的满电表现。
   - **当前剩余续航（RemainingRuntime v2.1）**：面向微观实时体验，基于设备当前剩余可用物理能量，结合当前放电会话（Fast/Medium）与历史习惯（Long）的多时间尺度连续平滑插值及凸组合跃迁响应进行微秒级动态预测。

> [!IMPORTANT]
> **核心定调：满电续航与剩余续航相互独立**  
> 满电续航的结果**绝不会**输入到剩余续航的计算流程中，两个算法模型在计算链路上互为并列分支，不存在上下游依赖关系。

---

## 2. 算法总体架构全景

```mermaid
flowchart TD
    subgraph DataSources["系统底层与硬件数据采集层"]
        NativeWh["硬件纳瓦时寄存器 (nWh)"]
        ChargeCounter["实时电荷计数器 (uAh 严格除以 1000 规范为 mAh)"]
        SysFCC["电量计真实满充容量 (FCC mAh)"]
        DesignCap["出厂标称设计容量 (mAh)"]
        BattTemp["电芯工作温度 (℃)"]
        BattLevel["当前电量百分比 (%)"]
        DischargeRecords["历史放电快照库 (168h 滚动自然窗口)"]
        CurrentSession["当前放电会话实时指标"]
    end

    subgraph FullChargeBranch["满电理论续航预测引擎 (FullChargeRuntimeEstimator v2.1)"]
        direction TB
        F1["FullChargeEnergyEstimator<br/>满充物理能量估算 (优先真实 FCC)"]
        F2["PowerSampleNormalizer<br/>168h 滚动自然墙钟窗口裁剪与清洗"]
        F3["PowerRegimeClassifier<br/>五大工况自适应分类"]
        F4["UsageProfileEstimator<br/>工况时间占比概率分布"]
        F5["RegimePowerEstimator<br/>工况代表功率做功积分"]
        F6["FullChargeRuntimeResult<br/>满电综合/亮屏/息屏理论续航 (v2.1)"]
        
        F1 --> F6
        F2 --> F3 --> F4 --> F6
        F3 --> F5 --> F6
    end

    subgraph RemainingBranch["当前剩余续航预测引擎 (RuntimeEstimator v2.1)"]
        direction TB
        R1["RemainingEnergyEstimator<br/>剩余可用物理能量直读 (杜绝单位猜测)"]
        R2["PowerStateDetector<br/>工况跃迁双门限检测与滞回"]
        R3["PowerAnomalyDetector<br/>MAD 稳健统计离群点检测与跃迁联动"]
        R4["PowerEstimator<br/>连续插值时间过渡 + 凸组合跃迁响应"]
        R5["TripleRuntimeResult<br/>当前亮屏/息屏/全局综合剩余续航 (v2.1)"]
        
        R1 --> R5
        R2 --> R3 --> R4 --> R5
    end

    SysFCC --> F1
    DesignCap --> F1
    NativeWh -.->|"降级备选"| F1
    DischargeRecords --> F2

    NativeWh --> R1
    ChargeCounter --> R1
    SysFCC --> R1
    BattLevel --> R1
    CurrentSession --> R2
    CurrentSession --> R4
    DischargeRecords -.->|"仅提供长期基准放电速率"| R4

    subgraph Presentation["UI 表现层 (ViewBinder / Tooltip)"]
        CardUI["放电卡片展示"]
        DetailTooltip["详情气泡弹窗 (并列呈现两者与质量说明)"]
    end

    F6 --> DetailTooltip
    R5 --> CardUI
    R5 --> DetailTooltip
```

---

## 3. 满电续航算法深度剖析（FullChargeRuntime v2.1）

满电理论续航引擎的核心位于 `com.battery.analysis.domain.estimator.FullChargeRuntimeEstimator`。它通过重构用户过去 168 小时（7 天自然墙钟窗口）的真实放电物理区间，建立工况条件化典型行为模型。

### 3.1 满充可用物理能量估算器（`FullChargeEnergyEstimator`）

遵循系统底层物理数据最高置信度优先级，严禁单点除以整数电量百分比放大抖动，杜绝在已老化的容量上二次扣除健康度：

```
优先级 1（首选）：电量计实测真实满充容量 (FCC, Full Charge Capacity mAh)
   └── 由于 FCC 已由电量计库仑计与阻抗跟踪算法动态计入电芯自然老化衰减，代表最可信的满充容量，
       严禁二次乘以健康度 SOH（杜绝二次惩罚）：
       E_full = (fccMah * nominalVoltage) / 1000.0

优先级 2：电池标称出厂设计容量 (Design Capacity mAh)
   └── 结合实测健康度 SOH 折算：
       E_full = (designCapacityMah * (sohPercent / 100.0) * nominalVoltage) / 1000.0
       (若无有效 SOH，退化为出厂设计标称值并标记为低质量数据)

优先级 3（降级备选）：硬件原生剩余能量计数器 (nativeEnergyNwh)
   └── 仅在前两者均不可用且当前电量较为充足（SOC >= 20%）时作为低质量参考：
       E_full = (nativeEnergyNwh / 10^9) / (batteryPercent / 100.0)
       (标记为 LOW_QUALITY，防止整数 SOC 量化跳变导致反推满充总能量剧烈抖动)

兜底策略：
   └── 若以上全部缺失或计算结果非法，返回 unavailable（null），严禁硬编码虚拟能量。
```

### 3.2 自适应历史时间窗口（7~30天）与时间衰减权重（`PowerSampleNormalizer`）

为避免“固定 7 天”在轻度或新装机用户场景下样本稀疏、工况缺失，同时杜绝“死板凑满 168 小时放电时长”导致回溯至一个半月前被陈旧机况严重污染的问题，系统采用自适应历史时间窗口：

1. **第一优先级（最近 7 天优先基准）**：
   优先提取当前时刻向前倒推 168 小时的自然墙钟时间窗口 $[t_{\text{now}} - 168\text{h}, t_{\text{now}}]$ 内的有效放电区间，赋予全额样本权重（$w_{\text{age}} = 1.0$）。该窗口天然覆盖完整的“5个工作日 + 2个周末”作息循环，时效性与代表性最高。
2. **第二优先级（样本充分性门控检查）**：
   系统评估 7 天内样本的充分性指标：
   - 累积亮屏有效放电时长 $T_{\text{on}} \ge 6.0\,\text{小时}$；
   - 累积息屏有效放电时长 $T_{\text{off}} \ge 12.0\,\text{小时}$；
   - 累积总有效放电时长 $T_{\text{total}} \ge 18.0\,\text{小时}$；
   - 覆盖核心放电工况数 $N_{\text{regime}} \ge 2$（同时覆盖亮屏与待机）。
   若全部满足，判定为 `SUFFICIENT`（充足），**立即锁定窗口为 7.0 天并停止回溯**，绝不读取 7 天前陈旧数据。
3. **第三优先级（自适应按需向前扩展至最多 30 天与时间指数衰减）**：
   若 7 天内样本不足以建立稳健模型，算法自适应继续向前追溯补足缺失工况（最远至 30 天）。
   对于超出 7 天的较旧历史记录，引入时间指数衰减权重：
   $$w_{\text{age}}(i) = \begin{cases}
   1.0 & \text{若 } a_i \le 7.0\,\text{天} \\
   \exp\left(-\frac{a_i - 7.0}{\tau}\right) & \text{若 } 7.0\,\text{天} < a_i \le 30.0\,\text{天} \\
   0.0 & \text{若 } a_i > 30.0\,\text{天（硬上限截断）}
   \end{cases}$$
   其中 $a_i = (t_{\text{now}} - t_{\text{end}, i}) / 86400\,\text{s}$ 为样本年龄，$\tau = 7.0\,\text{天}$ 为半衰时间常数。超出 7 天后每过 7 天影响力衰减约 $36.8\%$，保证越近的记录权重越高，旧记录仅作为补充基线平滑淡出，绝不喧宾夺主。
4. **第四优先级（30 天硬上限截断与充分性如实评定）**：
   回溯到达 30 天自然时间上限时（$t_{\text{now}} - 30\text{d}$），在边界处执行等比物理截断，绝不无限制向前搜寻。若此时仍不足，如实标记为 `INSUFFICIENT`（样本较少）并降低置信度，严格坚守**零假数据原则**。

### 3.3 运行工况自适应分类器（`PowerRegimeClassifier`）

为避免绝对功耗门限在不同硬件（如能效旗舰芯片与高刷电竞芯片）上出现水土不服，分类器支持自适应基线：

* **息屏工况判定（Screen-Off）**：
  以设备息屏待机基准底噪 $P_{\text{base,off}}$（默认 0.15W）为基准：
  - **深睡休眠底噪（`OFF_LOW`）**：$P \le 1.5 \times P_{\text{base,off}}$（电芯几乎无负荷，保持底电流）；
  - **唤醒活跃待机（`OFF_HIGH`）**：$P > 1.5 \times P_{\text{base,off}}$（存在后台服务、网络数据同步或音频播放）。
* **亮屏工况判定（Screen-On）**：
  以设备亮屏日常中位功耗 $P_{\text{median,on}}$（默认 1.80W）为基准：
  - **轻载工况（`ON_LIGHT`）**：$P < 0.65 \times P_{\text{median,on}}$（电子书阅读、低亮度纯文本静态展示）；
  - **日常中载（`ON_NORMAL`）**：$0.65 \times P_{\text{median,on}} \le P \le 2.20 \times P_{\text{median,on}}$（日常社交、网页浏览、短视频信息流）；
  - **高负荷重载（`ON_HEAVY`）**：$P > 2.20 \times P_{\text{median,on}}$（高帧率 3D 游戏、相机 4K 连续录像、重度多任务并发）。

### 3.4 经时间衰减加权的行为工况时间占比概率分布（`UsageProfileEstimator`）

传统算法按采样点个数计算比例，会在高频采样的亮屏阶段产生严重偏置。本算法严格遵循**持续物理时间加权守恒**，并融入时间衰减权重：
$$\Delta t_{\text{eff}, i} = \sum_{k \in \text{Regime}_i} \Delta t_k \cdot w_{\text{age}}(k)$$
$$r_i = \frac{\Delta t_{\text{eff}, i}}{\sum_{m} \Delta t_{\text{eff}, m}}, \quad \sum_{i} r_i \equiv 1.0$$

同时引入近期行为（近 24 小时）与长期习惯（近 168 小时）的温和自适应融合：
$$r_{\text{blended}, i} = (1 - \beta) \cdot r_{\text{long}, i} + \beta \cdot r_{\text{recent}, i}$$
其中 $\beta \in [0.0, 0.20]$（默认 0.15），严格限制近期行为影响上限，防止突发单日行为导致长期习惯模型失真。

### 3.5 经时间衰减加权的工况代表性功率计算（`RegimePowerEstimator`）

针对每一个有效工况分类，其物理代表功率由加权做功除以加权持续时间积分得出：
$$P_i = \frac{\sum_{k \in \text{Regime}_i} E_k \cdot w_{\text{age}}(k)}{\sum_{k \in \text{Regime}_i} \Delta t_k \cdot w_{\text{age}}(k)}$$
准入门限：单工况有效物理持续时长必须 $\ge 60$ 秒，否则不确立独立工况。

### 3.6 满电续航综合求解与 UI 三项解耦呈现

1. **工况条件化典型功率求解**：
   若有效工况类别覆盖 $\ge 2$ 类，启用行为条件化高阶模型：
   $$P_{\text{typical}} = \sum_{i} r_i \cdot P_i$$
   若工况类别不足，平滑降级至全局物理加权均值基准：
   $$P_{\text{global}} = \frac{\sum_k E_k \cdot w_{\text{age}}(k)}{\sum_k \Delta t_k \cdot w_{\text{age}}(k)}$$
2. **满电时长求解**：
   $$T_{\text{full,composite}} = \frac{E_{\text{full}}}{P_{\text{typical}}} \times 3600 \times 1000 \quad (\text{ms})$$
   同理，分别提取纯亮屏工况集合与纯息屏工况集合，独立推导得出：
   - **满电亮屏续航**：$T_{\text{full,on}} = \frac{E_{\text{full}}}{P_{\text{typical,on}}}$
   - **满电待机续航**：$T_{\text{full,off}} = \frac{E_{\text{full}}}{P_{\text{typical,off}}}$
3. **UI 三项信息清晰解耦呈现（解决概念混淆）**：
   在详情弹窗中，系统将以往容易混淆的“7天数据”解耦为三项独立指标：
   - **统计范围**：展示自然日历跨度，如“最近 7 天”或“自适应扩展至近 18.5 天”；
   - **有效放电时长**：展示实际参与计算的累计放电时间，如“实际参与累计 26h30m (亮屏 8h15m, 息屏 18h15m)”；
   - **数据充分性**：根据样本量与覆盖度标明“数据充足 / 基本可用 / 样本较少（已自动降低置信度）”。
4. **置信度等级与质量追溯（Quality Reasons）**：
   - `HIGH`：数据充分（`SUFFICIENT`）且工况类别覆盖完整；
   - `MEDIUM`：数据基本可用（`MODERATE`）或有效时长 $\ge 6$ 小时；
   - `LOW`：样本较少（`INSUFFICIENT`）；
   - `UNAVAILABLE`：有效历史时长 $< 10$ 分钟或满充物理能量不可用。
   结果附带 `qualityReasons` 列表，明确说明能量来源依据、自适应扩展天数与工况覆盖度。

---

## 4. 剩余续航算法深度剖析（RemainingRuntime v2.1）

剩余续航引擎由 `com.battery.analysis.domain.estimator.RuntimeEstimator` 及其组件协同完成，负责回答用户最关心的核心问题：“以当前的使用状态和电量，设备还能撑多久？”

### 4.1 剩余可用物理能量高精度估算（`RemainingEnergyEstimator`）

#### 四级能量源解析优先级（严格杜绝数值大小猜单位）
```
策略 1：硬件直接上报剩余能量 (remainingEnergyWh)
   └── 若硬件驱动直接提供且有效，直接采用。

策略 2：硬件原生纳瓦时能量寄存器 (nativeEnergyNwh)
   └── E_raw = nativeEnergyNwh / 10^9 (Wh)。

策略 3：硬件实时电荷计数器 (chargeCounterUah)
   └── 引入 BatteryUnitNormalizer 自适应物理量级校验（Physical Magnitude Validation）：
       通过基准容量或当前电量反推满充容量评估合理性，既严格杜绝低电量放大千倍，
       又彻底兼容国产厂商底层直接上报 mAh 的驱动实现：
       Q_mah = BatteryUnitNormalizer.normalizeChargeCounterToMah(chargeCounterUah, effectiveCapacityMah, batteryPercent)
       E_raw = (Q_mah * nominalVoltage) / 1000.0

策略 4：有效基准容量 (FCC) 结合剩余百分比推算
   └── E_raw = (effectiveCapacityMah * (batteryPercent / 100.0) * nominalVoltage) / 1000.0
```

#### 真实物理直读原则
默认情况下，系统不再叠加未经目标设备标定的通用硬编码 $K_T$ 折损（常温1.0、低温0.75、高温0.95），忠实保留硬件电量计芯片直读数值；仅在针对特定设备拥有实验标定数据时支持可选开启。

### 4.2 工况跃迁双门限检测器（`PowerStateDetector`）

为了在微小的息屏波动与剧烈的重载操作之间保持精准平衡，采用工业级**双门限判定机制**：

1. **绝对变化 + 相对变化触发门限**：
   $$\text{Threshold}_{\text{trigger}} = \max\left(\Delta P_{\text{abs,min}}, \text{Ratio}_{\text{change}} \times P_{\text{baseline}}\right)$$
   - $\Delta P_{\text{abs,min}} = 0.35\text{W}$：最小绝对变化硬门限。息屏待机功耗哪怕从 0.20W 波动至 0.30W（相对增幅 50%），但绝对差仅 0.10W $< 0.35\text{W}$，**绝对不误判为工况跃迁**。
   - $\text{Ratio}_{\text{change}} = 0.40$（40%）：相对比例阈值。当功耗从 1.5W 跃升至 3.5W 时，绝对差 2.0W 且相对比率达 133%，连续 2 周期确认后正式触发跃迁激活状态（`isRegimeChanging = true`）。
2. **滞回退出门限（Hysteresis Recovery）**：
   $$\text{Threshold}_{\text{recovery}} = \max\left(0.6 \times \Delta P_{\text{abs,min}}, \text{Ratio}_{\text{recovery}} \times P_{\text{baseline}}\right)$$
   退出容限比设定为 25%，防止功耗在门限边缘反复横跳。
3. **自适应响应倍率**：
   跃迁期间响应倍率 $M_{\text{resp}}$ 动态放大至 1.5 ~ 2.0 倍，使预测模型能够快速跟进新工况。

### 4.3 稳健统计异常功耗检测器（`PowerAnomalyDetector`）

采用中位数与绝对中位差（Median + MAD, Median Absolute Deviation）统计学架构：
$$\text{MAD} = \text{median}\left(\left|P_i - \text{median}(P)\right|\right)$$
标准化离群残差计算公式：
$$Z_i = \frac{\left|P_i - \text{median}(P)\right|}{1.4826 \times \max(\text{MAD}, \text{Floor}_{\text{mad}})}$$
- **短时偶发脉冲抑制**：若 $Z_i > 3.0$，判定为偶发尖峰（如 GC 突发、应用极短冷启动），将其样本置信度降权至 0.15，避免续航数字剧烈跳变。
- **与工况跃迁联动**：若 `PowerStateDetector` 已经确认当前处于持续工况迁移状态（如启动游戏），则**豁免 MAD 离群判定**，权重保持 1.0，防止将真实高负载误杀。

### 4.4 连续权重平滑插值与凸组合工况跃迁响应（`PowerEstimator`）

模型维持三个并行时间尺度的指数加权移动平均（EWMA）：
* **Fast 尺度（$\tau = 300\text{s}$，~5 分钟）**：捕捉即时操作与当前任务动态；
* **Medium 尺度（$\tau = 1800\text{s}$，~30 分钟）**：刻画当前放电会话的稳态功耗；
* **Long 尺度（近 7 天习惯基准）**：反映用户大样本长期生活作息基线。

#### 1. 连续平滑线性插值权重过渡
系统在会话时间轴的 7 级预设锚点间进行连续线性插值，消除任何时间分界点处的权重阶跃突跳：
- **0 分钟（拔电/亮屏冷启动）**：$(w_F, w_M, w_L) = (0.05, 0.15, 0.80)$（长期大样本习惯强先验主导，消除冷启动毛刺）；
- **5 分钟（Fast 物理时间常数 $\tau = 300\text{s}$）**：$(0.10, 0.20, 0.70)$；
- **10 分钟**：$(0.15, 0.25, 0.60)$；
- **20 分钟**：$(0.20, 0.35, 0.45)$；
- **30 分钟（Medium 物理时间常数 $\tau = 1800\text{s}$）**：$(0.25, 0.40, 0.35)$；
- **60 分钟（会话稳态期）**：$(0.25, 0.50, 0.25)$；
- **$\ge 120$ 分钟（单次长续航超稳态）**：$(0.25, 0.55, 0.20)$。

在无 7 天历史基准（Long 为 null）时，系统依据该时刻 Fast 与 Medium 的既定比率进行闭环自适应归一化：
$$w_F' = \frac{w_F}{w_F + w_M}, \quad w_M' = \frac{w_M}{w_F + w_M}$$
确保无历史数据场景下依然平滑连续。

#### 2. 凸组合工况跃迁响应（Convex Combination）
发生工况跃迁时，基于响应倍率计算响应因子 $\gamma \in [0.40, 0.85]$，对权重进行凸组合再分配：
$$w_F' = w_F + \gamma (1 - w_F)$$
$$w_M' = (1 - \gamma) w_M$$
$$w_L' = (1 - \gamma) w_L$$
由于基准权重满足 $\sum w = 1.0$，凸组合后的新权重天然严格满足 $\sum w' \equiv 1.0$，数学上自洽可解释，既迅捷提升对 Fast 的信任，又同比例温和压制 Long 习惯惯性。

预测功率由加权和求解：
$$P_{\text{predicted}} = w_{\text{fast}} P_{\text{fast}} + w_{\text{medium}} P_{\text{medium}} + w_{\text{long}} P_{\text{long}}$$

### 4.5 三维度剩余续航统一预测（`estimateTripleRuntimes`）

一站式输出三个维度的续航预测结果：

1. **持续亮屏剩余续航（Screen-On）**：
   $$T_{\text{rem,on}} = \frac{E_{\text{usable}}}{P_{\text{on,predicted}}}$$
   - **工况情景假设范围（Scenario Bounds）**：
     重载情景功率 $P_{\text{heavy}} = \max(1.8 \times P, 3.5\text{W}) \implies T_{\text{min,on}} = \frac{E}{P_{\text{heavy}}}$；  
     轻载情景功率 $P_{\text{light}} = \min(0.8 \times P, \max(1.0\text{W}, 0.75 \times P)) \implies T_{\text{max,on}} = \frac{E}{P_{\text{light}}}$。
2. **持续息屏待机剩余续航（Screen-Off）**：
   $$T_{\text{rem,off}} = \frac{E_{\text{usable}}}{P_{\text{off,predicted}}}$$
   - 预期范围涵盖高活跃待机（下载/后台）与深度休眠底噪（$0.15\text{W} \sim 0.30\text{W}$）。
3. **全局综合剩余续航（Global Composite）**：
   融合用户真实日常作息的亮屏时间占空比 $r_{\text{on}} \in [0.0, 1.0]$：
   $$P_{\text{global,composite}} = r_{\text{on}} \cdot P_{\text{on}} + (1 - r_{\text{on}}) \cdot P_{\text{off}}$$
   $$T_{\text{rem,global}} = \frac{E_{\text{usable}}}{P_{\text{global,composite}}}$$
4. **低电量置信度抑制机制**：
   当剩余电量 $< 10\%$ 时，由于锂电池放电曲线末端极化内阻急剧攀升、压降陡峭，模型将置信度统一乘以 $0.80$ 衰减系数，并输出数据质量说明，如实向用户提示预测不确定性。

### 4.5 三维度剩余续航统一预测（`estimateTripleRuntimes`）

一站式输出三个维度的续航预测结果：

1. **持续亮屏剩余续航（Screen-On）**：
   $$T_{\text{rem,on}} = \frac{E_{\text{usable}}}{P_{\text{on,predicted}}}$$
   - **工况预期范围**：
     重载功率上限 $P_{\text{heavy}} = \max(1.8 \times P, 3.5\text{W}) \implies T_{\text{min,on}} = \frac{E}{P_{\text{heavy}}}$；  
     轻载功率下限 $P_{\text{light}} = \min(0.8 \times P, \max(1.0\text{W}, 0.75 \times P)) \implies T_{\text{max,on}} = \frac{E}{P_{\text{light}}}$。
2. **持续息屏待机剩余续航（Screen-Off）**：
   $$T_{\text{rem,off}} = \frac{E_{\text{usable}}}{P_{\text{off,predicted}}}$$
   - 预期范围涵盖高活跃待机（下载/后台）与深度休眠底噪（$0.15\text{W} \sim 0.30\text{W}$）。
3. **全局综合剩余续航（Global Composite）**：
   融合用户真实日常作息的亮屏时间占空比 $r_{\text{on}} \in [0.0, 1.0]$：
   $$P_{\text{global,composite}} = r_{\text{on}} \cdot P_{\text{on}} + (1 - r_{\text{on}}) \cdot P_{\text{off}}$$
   $$T_{\text{rem,global}} = \frac{E_{\text{usable}}}{P_{\text{global,composite}}}$$
4. **低电量置信度抑制机制**：
   当剩余电量 $< 10\%$ 时，由于锂电池放电曲线末端极化内阻急剧攀升、压降陡峭，模型将置信度统一乘以 $0.80$ 衰减系数，如实向用户提示预测不确定性。

---

## 5. 满电续航与剩余续航的深度对比与解耦验证

### 5.1 核心维度对比矩阵

| 特性 | 满电理论续航（v2.0） | 当前剩余续航（v2.0） |
| :--- | :--- | :--- |
| **物理求解目标** | 电池总能量在典型作息下的满电总时长 | 电池当前剩余能量在当前工况下的剩余可用时长 |
| **能量物理依据** | $E_{\text{full}}$（硬件 nWh、FCC 或设计标称容量） | $E_{\text{remaining}}$（实时 uAh、nWh 或当前电量折算）$\times K_T$ |
| **功耗物理依据** | 168 小时工况聚类与概率加权典型功耗 $P_{\text{typical}}$ | Fast(5m) + Medium(30m) + Long(7d) 多时间尺度预测功率 |
| **抗突变特性** | **高惯性、高稳定性**，过滤短期单次波动 | **高敏捷、自适应**，双门限瞬时感知工况跃迁 |
| **温度修正** | 针对宏观标称容量（不随瞬时温度跳变） | 引入实时电芯温度物理衰减系数 $K_T$ |
| **工况范围预期** | 输出单一典型评估值与置信度评级 | 输出预期范围（重载极值 ~ 轻载极值） |
| **输出实体** | `FullChargeRuntimeResult` | `TripleRuntimeResult` / `RuntimeEstimate` |

### 5.2 为什么剩余续航不采用“满电续航 × 剩余百分比”？

很多简易系统会采用 $T_{\text{remaining}} = T_{\text{full}} \times \text{Percent}$ 的方式计算剩余续航，但在工程实践中这会导致严重问题：
1. **违背瞬时工况真实性**：
   用户满电续航可能是 10 小时（基于日常刷网页与待机）。但此时如果用户正在运行 3D 游戏（功耗 4.5W），剩余 50% 电量时如果直接乘以 50% 预测还能用 5 小时，会在不到 1 小时内迅速电量耗尽关机，造成极其恶劣的体验崩溃。
2. **违背锂电温度物理特质**：
   在冬季室外（-5℃）环境下，电池实际可释放化学能只有常温的 75% 左右。若按满电续航直接折算，会造成电量断崖跳水。
3. **容错隔离机制**：
   当新设备刚安装应用，历史记录尚不足 10 分钟时，满电续航会忠实返回 `null`（未知）。如果强行绑定依赖，将导致剩余续航也无法显示。解耦后，剩余续航只需基于当前会话实时功率即可快速独立完成推算。

---

## 6. 零假数据边界防御体系与异常处理标准

系统全局严格贯彻零虚构原则，在代码全链路中建立了坚固的防御屏障：

1. **不可用向上如实抛出**：
   - 能量缺失时：`UsableRemainingEnergyEstimate.usableEnergyWh = null`
   - 功率缺失时：`RuntimeEstimate.predictedPowerWatts = null`
   - 时长无法计算时：`remainingMs = null`
   - UI 展现层：忠实展示 `--` 或 `未知`，绝不生成随机数或预设保底假时长。
2. **杜绝二次老化衰减扣除**：
   系统在计算满充能量时，明确界定若使用电量计底层的 FCC，由于硬件底层库仑计与阻抗跟踪算法已动态包含了循环衰减，绝对不进行二次 `* SOH` 惩罚。
3. **物理有效性硬校验**：
   所有计算环节中的浮点数运算均经过 `isFinite()` 与 `> 0.0` 严格校验，杜绝 `NaN`、`Infinity` 或负数时长污染整个状态机。

---

## 7. 结语

本电池续航评估体系通过**满电理论续航（宏观能力）**与**当前剩余续航（微观预测）**的彻底解耦与精细化建模，在保障系统极端高可靠性的同时，忠实反映系统底层与硬件物理本质，实现了工业级的预测稳定性与响应敏捷度。
