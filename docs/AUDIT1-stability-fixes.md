# AUDIT1 — 直播/点播稳定性审计与模式化修复

## Recovery anchor

- **目标**：承接 LIVE3a/b/c 与全直播面审计之后的点播（VOD）全功能审计；对两轮审计沉淀的六类缺陷模式（A 主线程触 player()、B 双端漂移、C 位置指针负索引、D 主线程订阅做重 DB、E 外部输入无上限分配、F 线程池泄漏）做全库举一反三，并系统性修复。
- **验收标准**：双 flavor 编译通过 + 定向单测通过；六类模式在点播域的已知实例全部修复或有记录决策；文档账本完整。
- **状态**：代码已完成并验证（见文末验证记录）；待真机回归；未推送。

## 审计范围与方法

三路并行审计（VOD UI 层 / 数据 API 层 / 集成层）+ 逐条人工复核原始代码。无 P0；点播域 4 个 P1 + 约 10 个 P2。结论：无主线程网络/大解析类 ANR 新点；crash 集中在"服务连接前/断开窗口 + 外部事件"与双端漂移。

## 已修复（本 commit）

### 模式 A：player()/mHistory 在服务未连接或详情未返回窗口被触达
1. 双端 `VideoActivity`：`checkPlay/checkNext/checkPrev` 补 `service()==null` 守卫；leanback 另补 `mHistory==null`（媒体会话回调可先于详情返回；`onNext/onPrev` 通知分支亦解引用 mHistory）；双端 `onKeep` 补 `mHistory==null`。
2. 双端 `VideoActivity.onRefreshEvent`：`PLAYER/SUBTITLE/DANMAKU` 分支补 `service()!=null` 守卫（web 推送在断开窗口到达不再 NPE）。

### 模式 B：双端漂移
3. leanback `checkNext/checkPrev` 的 mHistory/空表双缺失（mobile `getAdjacentEpisode` 有守卫）——由第 1 条与 EpisodeAdapter 修复覆盖。
4. **mobile `checkId` 移植 leanback 的 `ensureConfigThenGetDetail/pollConfigReady`**（15s 超时等待 VOD 配置就绪），消除投屏/快捷方式冷启动黑屏漂移；新增 `detailRequested/mWaitingConfig/configWaitStart` 字段。

### 模式 C：位置指针负索引
5. 双端 `EpisodeAdapter.getNext/getPrev`：空表返回 `new Episode()`、上界改用 `mItems.size()`。
6. `Url.v/n` 补下界防；`Url.set` 空表钳到 0（原来 `Math.min(position, size-1)` 空表写 -1 → `v()` 的 `get(-1)` IOOBE；清晰度选择记忆 UX 保留，QualityAdapter 不动）。

### 模式 D：主线程订阅做重 DB（X16 历史聚合）
7. 双端 `HistoryActivity.getHistory` 与 leanback `HomeActivity.getHistory(renew)`：`History.getAll/findAcrossConfigs/configNameMap` 移入 `Task.submit`，完成后 `App.post` 渲染，epoch 计数防事件风暴下的乱序覆盖；`doClear`/`clearHistory` 的 `deleteAllAndSync/deleteAndSync` 移入后台，完成后回主线程刷新并广播 `RefreshEvent.history()`。mobile 首页无历史行（已核实）。

### 模式 E：外部输入无上限分配
8. `dlna/SocketHttpStreamServer`：`readBodyInto` 对 Content-Length 设 8MB 上限、`readLine` 设 64KB 行长上限，超限抛 IOException 走 `responseException` 优雅拒绝（原实现对 LAN 任意主机可分配 ~2GB，OOM Error 逃出 `catch(Exception)` 可致进程死亡）。

### 模式 F：线程池泄漏（点播域排查结论）
- `SiteViewModel.onCleared` 已有 `playerExecutor.shutdownNow()`——清白。
- ParseJob/MpvHlsProxy/LiveDanmakuWebSocketClient 属播放器核心内部生命周期，本轮不动（记录）；`Source` 用 try-with-resources 已安全。

## 点播审计 P2 记录不修

音频舞台按钮 pre-connect 的 player() NPE 族（第 1 条守卫已覆盖其主路径）；`History.isSameContent` 对远端同步可注入 null 直接 equals（建议后续 `Objects.equals()` 化）；`History.sync` 与播放保存并发的读-改-删无事务（进度可回退，`PlaybackProgressWriter` 的墓碑锁只覆盖 deleteInternal）；`VodBrowse.search` 逐站点串行 `future.get(5s)` 占共享池；`/media` 端点 `future.get()` 无超时；`PlaybackWebhookSender` 重试在 per-endpoint 锁内最长 ~23s；`Download.get` callback==null 失败 rethrow（现存调用方均有 FutureTask 兜底）；`CastEvent` 无订阅者（Action.onCast 死路径，投屏推送不生效——功能缺口非崩溃）；mobile `onDelete` 聚合模式 `mAdapter.clear()` 是否同步删库待确认（行为问题非稳定性）。

## 决策记录

- **入口守卫 vs 集中派发器**：`setPlayer/setDetail` 已有 pending 挂起机制，事件/点击入口补守卫是外科手术式修复；集中派发器需动 13k 行且改变回调语义，过度设计。长期解是已登记的 VOD1（VideoActivity 域状态机抽取），本批修复与其不冲突。
- **清晰度记忆**：`QualityAdapter.position` 跨结果残留本身是 UX（记住清晰度偏好），崩溃根因在 `Url` 缺下界防，故只修 Url。
- **Keep 行（`Keep.getVod()`）**仍为同步主线程：单表小查询，量级有界，与 X16 跨配置扫描不同类；记录不修。

## 验证记录

- `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` + `:app:compileMobileArm64_v8aDebugJavaWithJavac` + `testLeanbackArm64_v8aDebugUnitTest`（LiveProbePolicyTest/ChannelLineSplitTest/LiveAggregatorNormalizeTest，18 用例）：全部通过（chaquo 任务以 -x 排除，环境原因见 LIVE3 文档）。
- 待真机回归：媒体会话/耳机切集（详情未返回时）、音频舞台按钮冷启动连点、web 推送字幕/弹幕、投屏冷启动 mobile 黑屏是否消除、选过非首清晰度后触发播放失败、多配置大历史下首页/历史页滑动与清空、DLNA 渲染器开启后异常请求。

## AUDIT2 — 剩余项 ROI 分级后的微批修复（同日）

剩余项按 ROI 分级：仅 4 项值得做（本节），其余维持记录；VOD1/LIVE2 维持登记不启动。

1. **`History.isSameContent` 空值防护**：远端同步 payload 缺字段致 vodName/vodPic/wallPic 落库 null，历史页 DiffUtil 主线程 NPE。四个 String 字段改 `Objects.equals`（position/duration/createTime 为 long 基本类型保持 `==`）。同类核查：`Class/Collect/Func` 的 isSameContent 分别有 getter 兜底或走 `equals()` 机制，不受影响——无需改。
2. **聚合写盘唯一临时名**：并发 `aggregate()` 共用 `aggregate.json.tmp` 可互写截断；改 `文件名.线程id.tmp`，`aggregate()` 开头按龄（>1h）清理崩溃遗留孤儿。
3. **mobile `HistoryAdapter.clear()` 的 DB 删除移出主线程**（行为核查结论：clear 会删库，行为正确，仅线程问题）；列表 UI 部分保持主线程。
4. **`/media` 端点 `future.get()` 加 3s 超时**：主线程卡死时 Nano 连接线程不再无限挂起。
5. 核查后撤销：审计代理称 `VodBrowse.search` "逐站点串行 N×5s"不成立——futures 并行提交到 20 线程 largeExecutor，等待循环总耗时≈最慢一个；不改。
