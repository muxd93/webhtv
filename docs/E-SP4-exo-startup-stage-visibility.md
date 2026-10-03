# E-SP4 起播阶段可见（OSD 诊断"起播"行）

## 恢复锚（Recovery anchor）

- 目标：播放器 OSD 诊断面板新增"起播"行——按时间序显示 request → parse-complete → prepare → tracks → first-frame → audio-playable → ready 各阶段耗时与最慢阶段，慢起播可在设备上直接定位阶段，无需导出日志。
- 验收：`PlaybackTraceTest` 12/12（含新增 5 条）；双端 arm64 release Java 编译通过。
- 回滚：单提交 `git revert`。

## 实施（源自 Silent1566 E-SP4，按本仓 API 适配）

1. **`PlaybackTrace`**：新增 `STAGE_ORDER`（时间序，非枚举声明序——first-frame 实际先于 ready 到达）、`startupSummary()`（未到达阶段省略而非显示 0）、`slowestStage()`（`label:deltaMs`）。打点体系（REQUEST/PARSE_COMPLETE/PREPARE/TRACKS/FIRST_FRAME/AUDIO_PLAYABLE/READY 的 mark 调用）本仓基线**已完整存在**（AV-DIAG-01 同源），无需新增打点。
2. **`PlayerManager`**：`getStartupSummary()`/`getSlowestStartupStage()` 两个透传 getter。
3. **`PlayerOsdController`**：诊断主面板"配置"与"结论"之间插入"起播"行 + `getStartupText` 辅助（摘要 + "最慢 xx"）。
4. **`PlaybackTraceTest`**：移植源分叉 5 条新测试（时间序、最慢阶段、未到达省略、clear 清空、空态）。

## 范围决策记录（按"不硬上"原则丢弃/推迟的项）

- **丢弃：deferred-cues 设置开关**。源分叉的 `isDeferredCuesEnabled` 开关在源分叉 main 自己也未接入工厂读取点（UI 空接），无移植价值。
- **丢弃：OSD"缓冲偏少"提示改用原生 buffered**。混在源分叉 191 行 OSD diff 中，属独立微调，收益低。
- **丢弃：CP16（TV seek 后加载圈收口）及 E-SP1 纠正（首帧 progress GONE 收口）**。审计确认源分叉 main 上只有被其自己推翻前的中间形态（终态符号 `canHideSeekProgress`/`VideoActivityLoadingProgressSourceTest` 在源 main 也不存在），移植中间形态属"硬上"；且首帧收圈是基线长期行为、非缺陷。若装机实测发现 seek 后加载圈残留影响体验，再按源文档终态设计重写。

## 实施记录

- 2026-10-03：guard 会话 E-SP4-exo-startup-stage-visibility（upstream）。验证：双端 arm64 release Java 编译 + `PlaybackTraceTest` 12/12 通过。设备观感（诊断面板起播行）随下次装机验证。
