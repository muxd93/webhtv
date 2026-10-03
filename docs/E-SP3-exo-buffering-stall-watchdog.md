# E-SP3 Exo 缓冲停滞看门狗（含预加载稳定窗）

## 恢复锚（Recovery anchor）

- 目标：消除 Exo 永久转圈——BUFFERING 停滞（位置与缓冲端双双不前进）自动触发超时兜底；后台 HLS 预加载让行前台（首帧/恢复后 5 秒稳定窗才启动，分段间隔 5 秒，buffering/seek 重新计时）。
- 验收：`ExoBufferingStallWatchdogTest` 22/22 通过；`PreCachePolicyTest` 12/12、`ExoPlaybackThresholdCoordinatorTest` 回归通过；双端 arm64 release Java 编译通过。
- 回滚：单提交 `git revert`；watchdog 为独立类，PreCache 增量均可独立还原。

## 实施内容（源自 Silent1566 E-SP3a + fix-duplicate-playback-start 第二部分）

1. **`ExoBufferingStallWatchdog`**（165 行，纯逻辑，原样移植）：位置+缓冲端双停滞 20s 判停、loading 宽限 60s、回退 >1000ms 视为不连续重置基线、暂停开新 episode；常量 `STALL_TIMEOUT_MS=20s`、`LOADING_STALL_TIMEOUT_MS=60s`。
2. **`PlayerManager` 接线**（对照源分叉 hunk 逐点适配）：
   - 装载点：`onPlaybackStateChanged` 的 READY 取消 / BUFFERING（仅 Exo）arm / 其他状态取消；`seekTo` 取消后重新 arm；`reset()` 与 `release()` 取消；`onPlaybackTimeout` 先取消看门狗。
   - 1 秒轮询 `checkBufferingStall`：非 Exo/无 player/spec 即取消；READY/ENDED/IDLE 取消；暂停时重置基线；停滞触发 `onBufferingStall`。
   - **适配点**：源实现停滞时先走其 `fallbackPlayback` 内核兜底链（本仓未移植该特性），改为与基线超时路径一致：`finishPlaybackProfileAbSession("buffering-stall")` + `callback.onError(error_play_timeout)`，由界面给出重试入口。
3. **PreCache 稳定窗**（仅移植 defer/spacing 机制，剥离播放列表预加载与 `setPlaylistPreloadDurationMs`/`onMediaItemTransition`/`scopedCache` 等无关改动）：
   - `PreCachePolicy`：`PLAYBACK_STABILITY_GRACE_MS=5s`、`NEXT_RANGE_DELAY_MS=5s`、`isPlaybackStableForPreload`、`nextRangeDelayMs`。
   - `PreCache`：`preloadNotBeforeMs`/`nextRangeNotBeforeMs` 字段与 start/reset 重置；buffering/seek/first-frame 三处 `deferPreload`；`check()` 增加 playback-stability 门（未稳定则 WAIT_*_BUFFER 收口）；分段完成后 `nextRangeNotBeforeMs` 间隔 5 秒，`schedule`→`scheduleAt`（先 cancel 再延迟 post），`requestImmediateCheck` 按间隔调度。

## 范围决策记录

- **E-SP1 纠正（首帧 progress GONE 收口）推迟**：基线 `PlaybackActivity.onExoFirstFrame()`（:262）首帧即隐藏进度圈，源分叉将其收口交回 READY。该改动属 CP16/E-SP3b 的"TV 加载圈收口"族且在 guard scope 之外，与看门狗正确性无关（arm() 的重定位语义已覆盖换防），留待 CP16 阶段处理。
- **`fallbackPlayback` 内核兜底链未移植**：属源分叉独立的内核会话记忆 + 兜底特性（涉及 `playerFallbackTried`、会话内核等），如需"停滞自动换内核"需单独立项。
- 验证中未包含设备实测；建议装机后复测长断流场景：20 秒停滞应出现重试提示而非永久转圈。

## 实施记录

- 2026-10-03：guard 会话 E-SP3-exo-buffering-stall-watchdog（upstream）。验证：`:app:compileMobileArm64_v8aReleaseJavaWithJavac` + `:app:compileLeanbackArm64_v8aReleaseJavaWithJavac` + `:app:testMobileArm64_v8aDebugUnitTest`（Watchdog 22/22、PreCachePolicy 12/12、ThresholdCoordinator 回归）全部通过。
