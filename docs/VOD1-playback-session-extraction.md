# VOD1 — VideoActivity 同构问题（登记，未实施）

**状态**：登记为后续独立任务（用户指示"单独登记"）。本次 LIVE1 未触碰任何 VOD 代码。

## 背景（2026-09-29 梳理结论）

- `VideoActivity` 存在与直播重构前同构的病根：leanback 6462 行 / mobile 6632 行，选集/播放/换源状态机双份复制并已出现单侧修复漂移。
- `PlaybackActivity`（共享播放器管线、service 绑定、表面管理、错误分发）证明共享基座模式在本仓库运转良好；直播侧 `LiveSession`（LIVE1）提供了"域状态机 + Listener 渲染契约"的可复制样板。

## 建议路径（启动时再细化）

1. 复用 LIVE1 模式：抽取 `VodSession`（选集/换源/历史/错误恢复状态机），两 flavor VideoActivity 实现渲染 Listener。
2. 分阶段：先只读梳理 VideoActivity 的重复方法清单与漂移点（同 LIVE1 前置分析的做法），再动手。
3. 预计体量大于 LIVE1（文件 ~6.5k 行 ×2），需拆多个 guard 会话。

## 关联

- 样板：`app/src/main/java/com/fongmi/android/tv/live/LiveSession.java`、`docs/LIVE1-live-session-refactor.md`。
