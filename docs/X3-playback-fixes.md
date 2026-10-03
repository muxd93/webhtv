# X3 播放修复：重复起播竞态 + 短显按钮核查

## 恢复锚（Recovery anchor）

- 目标：修复 TV/手机端换条目/换集时前台取流与旧请求竞态导致的"反复加载/重复起播"。
- 验收：两端 `setPlayer` 对 null 安全；新取址请求前旧请求被取消（代次校验失效 + future 取消）；双端 arm64 release Java 编译通过。
- 回滚：单提交 `git revert`。

## 内容

### 1. 重复起播修复（适用，已实施）
来源：Silent1566 `docs/fix-duplicate-playback-start-20260918.md`（其修复分两部分：请求取消 + 预加载稳定窗）。

- `SiteViewModel.cancelPlayerContent()`：使旧 PLAYER 请求失效（代次 +1、future 取消、LiveData 置空），原样移植。
- 两端 `VideoActivity`：在 `mViewModel.playerContent(...)` 前调用 `cancelPlayerContent()`；`setPlayer(Result)` 增加 `result == null` 防护（基线原实现直接解引用 result，置空后观察者会 NPE；两个既有 `getPlayer().getValue()` 消费者已自带 null 判断，核查无其他风险点）。
- 安全核查：取消路径经代次校验丢弃迟到结果，不产生额外回调；null 只瞬时存在且所有消费点已防护。

### 2. TV 短显按钮无响应（不适用，记录备查）
来源：Silent1566 `docs/tv-short-display-dead-20260914.md`。根因是其分叉内部合并（`7dc58af1b0b`，C4 合并）误删 leanback `VideoActivity.initEvent()` 的控制栏监听。本仓基线 `VideoActivity:746` 一直保留 `mBinding.shortDisplay.setOnClickListener(...)`，该缺陷从未存在于 muxd93 线。无需改动。

### 3. 预加载稳定窗（已并入 E-SP3 实施）
原推迟至 E-SP3 的预加载稳定窗/分段间隔部分已随 `E-SP3-exo-buffering-stall-watchdog` 会话实施完成（`PreCachePolicy` 稳定门 + `PreCache` defer/spacing），见 `docs/E-SP3-exo-buffering-stall-watchdog.md`。

## 实施记录

- 2026-10-03：guard 会话 X3-playback-fixes（quick-fix）。验证：`:app:compileMobileArm64_v8aReleaseJavaWithJavac` + `:app:compileLeanbackArm64_v8aReleaseJavaWithJavac` BUILD SUCCESSFUL；`getPlayer().getValue()` 全部消费点核查通过。设备实测（一次启播仅一条 startPlayer dispatch）留待装机后按源文档验证计划执行。
