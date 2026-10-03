# E-SP6 解码标签如实反映实际解码器

## 恢复锚（Recovery anchor）

- 目标：硬解档位下 Exo 仍会为 MediaCodec 拒绝的编码安装 FFmpeg 软解兜底渲染器，标签只显示配置档位会掩盖"标着硬解、实际软解"的慢放场景。标签在失配时显示为"硬解→软解"。
- 验收：`DecodeLabelPolicyTest` 6/6 通过；双端 arm64 release Java 编译通过。
- 回滚：单提交 `git revert`。

## 实施（源自 Silent1566 E-SP6）

1. **`DecodeLabelPolicy`**（39 行纯函数，原样移植）：仅当"配置=硬解 且 实际=SOFTWARE"时失配；实际模式未知时不声称失配（宁可少报不可错报）；软解标签取自与引擎标签同一本地化 `select_decode` 数组（避免硬编码造成混合语言）。
2. **`PlayerManager`**：`getDecodeText()` 改经策略输出；`getSoftDecodeLabel()`（select_decode[SOFT]）；`getActualDecodeMode()` 读 `playbackAutoContextStore.snapshot().media().decoder().videoDecodeMode()`（该链路与本仓既有诊断日志同源，未知时返回 UNKNOWN）。实际解码模式由 `PlaybackMediaFactsCoordinator` 按内核上报事实解析（IJK 用 FFP_PROPV_DECODER_*、MPV 用 hwdec），单源真相，覆盖全部内核。
3. **未移植**：`getAppliedIjkTuneMode()`（依赖本仓没有的 `IjkDecodePressurePolicy`，属源分叉 E-SP5 降负载家族）与其 OSD `getSoftDecodeTuneText` 门控改动——随 E-SP5 一并丢弃。

## 实施记录

- 2026-10-03：guard 会话 E-SP6-decode-label-reflects-actual（upstream）。验证：双端 arm64 release Java 编译 + `DecodeLabelPolicyTest` 6/6 通过。观感（配置行"硬解→软解"）随下次装机验证。
