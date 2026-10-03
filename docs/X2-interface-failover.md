# X2 接口故障转移

## 恢复锚（Recovery anchor）

- 目标：VOD 接口加载失败时按 off/auto/confirm 三种模式自动/确认切换备用接口，最多 3 个候选（原始 + 2 备选），一轮只交付一次终态回调/事件。
- 验收标准（阶段 1 部分）：三个自包含核心类与其单测全部通过（`InterfaceFailoverPolicyTest`、`InterfaceFailoverStateTest`、`InterfaceOrderStoreTest`，共 12 条，`:app:testMobileArm64_v8aDebugUnitTest` BUILD SUCCESSFUL）；主代码尚未接线，行为无变化。
- 回滚：阶段 1 单提交 `git revert` 即可（纯新增未接线类，无行为影响）。

## 规格

来源：Silent1566 分支 `docs/interface-failover-design.md`、`docs/interface-failover-v1.md`、`InterfaceFailoverPolicy/State/OrderStore.java` 及同名测试。

设计要点（源 v1 决策）：默认 AUTO；URL 顺序存偏好（`interface_order_vod`）；最多 3 候选；AUTO 自动重试剩余候选，CONFIRM 弹一次剩余列表并在所选候选失败后结束本轮；手动选择开启新一轮；中间失败不发 VOD 事件；空 sites 列表视为加载失败进入故障转移；模式切到 Off 取消排队任务并阻断迟到回调。

## 阶段记录

### 阶段 1（本次，2026-10-03）：核心类移植
- 原样物化 `InterfaceFailoverPolicy`（39 行，纯函数）、`InterfaceFailoverState`（71 行，纯状态机）、`InterfaceOrderStore`（111 行，Prefers+Gson 持久化）+ 3 个测试类。
- WebHTV 适配：`InterfaceOrderStore` 去掉 `android.text.TextUtils` 依赖（`isEmpty` → 私有 null/长度判断；`equals` → `java.util.Objects.equals`），否则本地单测因 "not mocked" 抛 RuntimeException（源分叉 testOptions 与本仓一致，其测试通过存疑，此处以实测为准）。
- 验证：`:app:testMobileArm64_v8aDebugUnitTest` 聚焦 3 个测试类 12 条全部通过。

### 阶段 2a（本次，2026-10-03）：VodConfig 接线完成（AUTO 模式生效）
- **BaseConfig**：新增 `onConfigFailure` 统一失败出口钩子（默认投递错误）、`cancelLoad(boolean)`、`loadSilent` 通路；`loadDispatch/loadCachedConfig/loadFromNetwork/loadConfig` 贯穿 `silent` 标记——SWR 静默刷新与缓存先行后的第二段网络刷新失败**不进入**故障转移（避免后台静默切换接口）；缓存先行已成功渲染时，第二段刷新失败保持原行为。
- **VodConfig**：移植源分叉 FailoverRound 轮次层——失败时按 `InterfaceOrderStore.sortVodConfigs(Config.getAll(VOD))` 从当前接口位置向后取最多 2 个候选；AUTO 自动推进（同一用户 callback 贯穿整轮，attemptCallback 身份校验防串轮）；轮次活跃期间抑制 VOD 事件，终态统一补发；`abandonFailover` 在每次用户发起加载时取消旧轮次；`load(Config)` 覆写增加"站点为空即失败"判定（与源分叉一致）。
- **Setting/Backup**：`interface_failover_mode` 键（默认 AUTO，与源分叉 c89b166e/ca9febe 复核结论一致）；`interface_failover_mode` + `interface_order_vod` 进一键同步白名单；`BackupPreferenceFilterTest` 补两条正断言。
- **语义确认**：故障转移只在"缓存也救不回来"的真失败时触发（缓存先行成功渲染后网络刷新失败仍走原行为）；候选只来自历史接口，最多 3 次尝试。
- 验证：双端 arm64 release Java 编译通过；`:app:testMobileArm64_v8aDebugUnitTest` 聚焦 4 个测试类 19/19 通过。
- **已知留待 2b**：CONFIRM 模式的确认弹窗（当前 CONFIRM 无自动候选，等效终止报错）、设置 UI（模式选择 + 历史拖拽排序，`setting_interface_failover` 等文案）、`selectConfig` 手动选择入口、`cancelFailover` 公开取消。

## 整体 review 记录（2026-10-03，squash 后成品复审）

复审发现并已修复两处考虑不周（修复随整合提交 amend 入库）：

1. **空站点判定位置**：最初放在 `VodConfig.load(Config)` 覆写、`super.load()` 的 try 之外——网络返回空 sites 但缓存有内容时跳过缓存回退直接报错/切源，且空 payload 已覆盖好缓存。已移入 `parse()`（源分叉同位），异常 payload 时仍享受缓存回退；缓存内容本身为空则按缓存损坏路径转网络重试（可容灾）。
2. **SWR 刷新作废在途容灾轮次**：轮次活跃期间 SWR 定时刷新触发 `loadSilent`（taskId++ + 取消 future）会静默作废进行中的容灾尝试，轮次终态永不到达，VOD 事件抑制通道被无限期占用。已在 VodConfig 覆写 `loadSilent` 先行 `abandonFailover()`。

复审确认无问题的点：failover 仅由用户发起加载触发（SWR/缓存先行第二段均 silent）；候选去重与上限（origin+2）；轮次生命周期由 taskId 代次校验兜底（迟到回调全部丢弃）；`finishSuccess` 先于被抑制的 finally postEvent 执行（App.post 时序），事件只补发一次；X1 两端 skip 路径与 `arm()` 同步落盘（`commit()`）语义正确；E-SP3 装载点覆盖 release/reset/seekTo/状态迁移/超时，`setMediaItemNow` 未显式取消但 BUFFERING 重 arm 语义等价；E-SP6 的 UNKNOWN 不声称失配。

### 阶段 2b（批次 C 完成，2026-10-03）：确认弹窗与设置 UI
- **VodConfig**：移植 CONFIRM 确认弹窗全套——`showConfirmDialog`（单选候选列表，`App.activity()` 不可用时终态报错）、`startSelectedAttempt`（`state.select` 一轮一次）、`cancelFailover(round)`/`cancelRound`/`stopFailover`、`failoverDialog` 字段与 `abandonFailover` 弹窗清理；新增公开 `VodConfig.cancelFailover()` 供设置页切到"关闭"时终止进行中轮次。
- **设置 UI**（增强功能页，两端 `SettingEnhance*`）：新增"点播接口容灾"选择器（关闭/自动/确认，写入 `interface_failover_mode`，切到关闭即 `cancelFailover()`）与"跨配置历史聚合"开关（`history_aggregation`）；行样式/焦点/reorderItems 按两端既有模式。
- **文案**：`setting_interface_failover`、`select_interface_failover_mode` 数组、`interface_failover_title/message/switch/cancelled`、`setting_history_aggregation` 三语（values/values-zh-rCN/values-zh-rTW）补齐。
- 验证：双端 arm64 release Java 编译通过；聚焦测试（setting + BackupPreferenceFilter）通过。
- X2 全部阶段收口。遗留可选：批次 D 拖拽排序（`InterfaceOrderStore` 接线 `ConfigDialog`，含 `selectConfig` 手动选择入口）。
- 已知冲突点：本仓 VodConfig/BaseConfig/ConfigDialog 刚经历 SUB1–SUB3 订阅重构与配置缓存/离线回退改造，与源分叉基于旧基线的 +380 行 VodConfig diff 不能直接套用；需先读本仓现行 load 流程再设计接入点（候选加载、空 sites 判定、失效处理、终态事件唯一性）。
- 设置 UI：leanback `SettingPersonalActivity`（+286 行）与 mobile `SettingPersonalFragment`（+248 行）含模式选择与历史拖拽排序；CONFIRM 确认弹窗（`showConfirmDialog`/`startSelectedAttempt`/`cancelFailover`）与 `selectConfig` 手动选择入口；所需文案 `interface_failover_title/message/switch/cancelled`、`setting_interface_failover`、`select_interface_failover_mode` 数组。
