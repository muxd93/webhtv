# LIVE3 — 直播聚合/探测加固（线路名保留、主线程 I/O、会话软刷新）

## Recovery anchor

- **目标**：修复 2026-10-03 复查发现的直播聚合/探测缺陷：① `$线路名` 被解析剥离导致线路命名回退；② 进直播页/手动探测的主线程文件 I/O；③ 探测/聚合更新后当前会话停留旧树、重排不生效。附带：savePool 保留源状态、聚合隐藏组 pass 语义修正。
- **验收标准**：两 flavor 编译通过（本轮由实施者编译验证）；txt/m3u 源 `$名称` 线路名在 UI 显示且聚合/探测去重用整洁 URL；探测轮次后正在观看的会话不重启且新树生效；进直播页主线程零大文件 I/O。
- **状态**：代码已完成，编译+单测已验证并提交（见文末记录）；待真机回归。

## 变更清单

| 文件 | 变更 |
|---|---|
| `bean/Channel.java` | 新增 `lineNames`（与 urls 等长）+ `splitLine/addLine/mergeLines/orderLines/normalizeLines`；`getLine()` 改读 lineNames；`copy()` 拷贝名称并归一 |
| `bean/Group.java` | `Group.add` 合并分支改 `mergeLines`（收藏组副本随带名称） |
| `bean/Live.java` | `objectFrom` 反序列化后逐频道 `normalizeLines`（JSON 配置与聚合文件路径） |
| `bean/Group.arrayFrom` | 同上归一钩子（JSON 数组 lives 路径） |
| `api/parser/LiveParser.java` | 删除 `stripInfo`，m3u/txt 改 `channel.addLine(raw)` 捕获 `$名称` |
| `live/LiveAggregator.java` | `orderIndices`（纯函数，orderUrls 变其包装）；merge 用 `mergeLines`、隐藏组"任一隐藏即隐藏"、重排 `orderLines`；`isStale()` 廉价化（Prefers `live_agg_ts_`）；`savePool` 保留既有源状态；**meta/state 分文件**（lines/probe 迁入 `aggregate.state.json`，含一次性迁移）；删除/增减源重置时间戳 |
| `live/LiveProbe.java` | `collect` 移入后台（`run(Supplier)`），入口线程零文件 I/O；探测落盘后改 `reloadQuietly` |
| `live/LiveSession.java` | 新增 `softReload()`：静默换新树、按 组名+台名 对位当前台，不触碰播放器 |
| `api/config/LiveConfig.java` | 新增 `reloadQuietly()`（loadSilent + 成功后 `RefreshEvent.liveUpdated()`） |
| `event/RefreshEvent.java` | 新增 `LIVE_UPDATE` 类型 |
| 双端 `LiveActivity` | `LIVE_UPDATE → session.softReload()`；`onSoftReloaded` 重建列表并对位选择（不走 keep 计数/密码弹窗） |
| 测试 | `bean/ChannelLineSplitTest`（新增）、`live/LiveProbePolicyTest` 补 orderIndices 排列一致性用例 |

## 关键设计决策

1. **线路名方案**：采用"整洁 URL + 平行 lineNames"（选项 C），而非简单回滚 stripInfo。原因：探测/聚合去重/Keep 断点都依赖整洁 URL；若保留 URL 内联 `$名`，探测会请求带后缀的 URL 导致误杀。`$说明`（iptv-api）与 `$线路名`（源作者）语法不可区分，统一按线路名捕获——对用户表现为线路显示有意义名称。
2. **软刷新而非自动重载**：复核事件链后确认此前的"自动重载打断播放"判断有误（`ConfigEvent.live()` 仅被 PlaybackService 消费，不进 UI）。真实缺口是"更新后当前会话停留旧树"。`LIVE_UPDATE` 事件 + `softReload` 补上它且绝不重启播放；找不到原台时保留旧引用继续播。
3. **staleness 廉价化**：主线程只读 Prefers 时间戳 + 文件存在性；单源粒度由 `aggregate()` 内部按每源 ts 增量拉取兜底。旧数据无时间戳 → 首次进入触发一次重聚合后自愈。

## 遗留与交接

不修/待办子项已写入交接文档：`docs/LIVE2-epg-streaming-parser.md`（EPG 流式解析，全量实施手册）、`docs/VOD1-playback-session-extraction.md`（VOD 端状态机抽取）。低风险边界项（探测 UA 与播放 UA 不一致、collect 去重取首个频道 header、多仓首次导入同步等待、远端/服务器路径 `Manage.java` 的带事件 load）记录在 LIVE2/VOD1 文档"非目标"与本文下方。

- 探测 UA：channel 无显式 UA 时用 OkHttp 默认 UA 探测，个别校验 UA 的源可能误判；有"连续 2 轮全死才隔离 + 手动复活"缓冲。若要修：LiveProbe.probe 的 Request 附加播放侧同款默认 UA。
- collect 去重：同一 URL 出现在多频道且 header 不同时取首个频道的 header；若要修：Entry 按 url+headers 组合去重或探测结果按 header 分键。
- 多仓首次导入：`LiveConfig.parseDepot` 内 `aggregate(true)` 同步执行，首屏等待最慢单源（20s/6 并发），有 Toast 进度。若要修：先加载池内已有缓存的聚合文件，后台刷新后再 quiet reload。

## 验证记录

- `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` + `:app:compileMobileArm64_v8aDebugJavaWithJavac`：BUILD SUCCESSFUL（过程中修复 1 处编译错误：`Prefers.putLong` 不存在，改 `Prefers.put(String, long)`）。
- 单测：`:app:testLeanbackArm64_v8aDebugUnitTest` 过滤 `LiveProbePolicyTest`（含新增 orderIndices 排列用例）、`ChannelLineSplitTest`（新增）、`LiveAggregatorNormalizeTest`（回归）——全部通过。
- 待真机回归（后续统一验证）：txt/m3u `$线路名` 显示、聚合源池管理、探测轮次后不重启且重排生效、断网聚合降级、隐藏组解锁。
- 2026-10-03 接手复核（新会话继承未提交工作区，复核后提交）：`:app:compileLeanbackArm64_v8aDebugJavaWithJavac` + `:app:compileMobileArm64_v8aDebugJavaWithJavac` 执行通过；`testLeanbackArm64_v8aDebugUnitTest` 过滤三测试类共 18 用例（ChannelLineSplitTest 5、LiveAggregatorNormalizeTest 7、LiveProbePolicyTest 6）全部通过。
- 环境备注：本机仅有 Python 3.13，Chaquopy 要求构建 Python 3.10，`:chaquo:installArm64_v8aDebugPythonRequirements` 无法执行；上述验证以 `-x` 排除该任务（JVM 单测不消费其产物）。打整包 APK 前需安装 Python 3.10，与本任务无关。

## 2026-10-03 复核清单验证（LIVE3a）

针对原始复查清单逐条重读当前代码验证：4 个确认缺陷（$线路名吞没、自动重载打断播放、主线程 I/O、nextLine 不一致）**全部已由本任务修复**；边界项中隐藏组"任一来源隐藏即隐藏"（merge:637-640）与 rewrite 极端时序（复活回插先于隔离过滤，allDead 与 revived 互斥，同轮不可能吞掉回插频道）**已修/不可复现**。事件链复核：重启型 `RefreshEvent.LIVE` 仅剩远端遥控 `Action.java:146` 来源；`ConfigEvent.live()` 消费方 PlaybackService 只刷 BrowseTree 不碰播放器；内容未变时 `suppressEvent` 抑制事件。

LIVE3a 补丁（本节同 commit）：

1. `LiveSession.softReload`：原台不在新树但同名组存在时，把保留播放的旧 channel 重绑到新树同名组——否则 `isTuned/syncToTuned` 拿到脱离树的旧组，列表选中态错位。
2. `LiveAggregator.savePool`：URL 比较改为集合比较——同批源仅顺序不同（用户在源池 UI 调过序）不再触发整池重写，本地顺序得以保留。

已知边缘（记录不修）：softReload 时整个组消失的场景，session 仍持旧组引用继续播（换台后自愈）；URL 查询参数含 `$` 会被 splitLine 误拆名称（与旧 stripInfo 行为等同，非回归）。

维持不修决策（本轮复核确认）：探测 UA 与播放侧默认 UA 不一致（三种播放引擎默认 UA 各异，无对齐收益，2 轮全死+复活兜底）；collect 按 URL 去重取首个频道 headers（误配窗口极窄）；parseDepot 同步 aggregate(true)（实际在后台线程执行且有 Toast，池未变未超龄直接复用文件）；单源配置 SWR 内容变化不补页内热更广播（需给 BaseConfig 加内容变化信号，低频场景不值得引入事件时序风险）。

## 2026-10-03 ANR/crash 审计（LIVE3b）

对直播链路做 ANR/crash 定向审计：无 crash 级缺陷；配置加载/探测/EPG 全链路线程模型核实无主线程网络与重解析；Channel EPG 锁仅覆盖小开销拷贝；适配器主线程 clear-then-add 无 CME。发现并修复（本节同 commit）：

1. **revive 主线程大文件 I/O**：双端 LivePoolDialog"复活"经 apply() 内联执行 `LiveAggregator.revive()`，其内部 readState/writeState 同步读写大 state 文件（lines+probe 随池增长且 lines 无清理）。改为 `Task.submit` 后台执行、完成后主线程 `loadData()+reaggregate()`。
2. **池对话框重聚合后停留旧树**：`reaggregate()` 成功改用 `reloadQuietly()`（原全量事件 `load()`，页面不订阅 ConfigEvent 故不刷新），经 `liveUpdated → softReload` 静默对位。
3. **sync 守卫对齐**：`LiveConfig.reloadQuietly()` 补 `if (sync) return;`，与 `load()` 一致，防同步模式被静默重载。

鲁棒性记录项（非 ANR/crash）：并发 aggregate() 可能竞态写同一 `.tmp` 文件，`write()` 捕获异常且下次聚合自愈。

## 2026-10-03 全直播面 ANR/crash 审计（LIVE3c）

三路并行审计（UI 层、数据/API 层、集成层）+ 逐条人工复核原始代码。无 P0；修复 4 个 P1 崩溃/泄漏 + 1 个缓解（本节同 commit）：

1. **leanback LiveActivity 粘性 Result 崩溃**：Activity 重建（uiMode/fontScale）后 `observeForever` 同步重放 ViewModel 存活 Result，`mService` 未连接时 `player().setSpeed`/`Track.delete(player().getKey())` NPE。照 mobile 模式补 `mPendingStartResult` 暂存 + `onServiceConnected` 补放。
2. **数字选台定时器逃逸**：`CustomKeyDownLive` 的 2s 定时器 runnable 不在 leanback onDestroy 清理清单，退出页面后触发 `onFind→…→player().clear()` NPE。新增 `CustomKeyDownLive.release()` 并在 onDestroy 调用。
3. **Catchup 非法 regex 主线程崩溃**：JSON 配置 `catchup.regex` 非法时 `Pattern.compile` 抛 PatternSyntaxException（`LiveSession.onEpgDataClick → hasCatchup → match`）。`match()` 加 try-catch 回退子串匹配。
4. **ScanTask 线程泄漏**：`Manage/devices?scan=1` 每次创建孤儿 ScanTask（无人调 stop()），`newFixedThreadPool(64)` 核心线程永久驻留 → 反复扫描后 pthread_create OOM。改为允许核心线程 60s 空闲超时的等价池（排队语义不变；CastDialog 同实例重扫描流程不受影响——这正是不能"完成即关池"的原因）。
5. **浏览路径池饥饿缓解**：`LiveBrowse → LiveConfig.ensureLoaded`（synchronized）在共享 5 线程池内同步拉网络+EPG，慢源首用最多 5 线程串行阻塞。PlaybackService onCreate 后台预热 `ensureLoaded()`，把首次加载挪离浏览关键路径。

P2 记录不修（详情见审计报告）：Action.java onControl/onDanmaku 服务销毁窄竞态 NPE；`Task.execute` 裸执行语义（全应用决策，现存可达点在 VOD 历史保存）；Manage.reloadConfigs 主线程读 CSP 小文件；mobile LiveControlDialog Fragment 重建 parent NPE；leanback seek 250ms lambda 窗口；mobile refreshInjectedLives 主线程读小文件；Catchup 格式化异常致回看静默失败（后台兜底）；{date} 模板 EPG 超时不打断底层 OkHttp（资源占用）；EpgParser gzip 截断文件当有效缓存 + 无 zip 炸弹防护（归 LIVE2 域）。

审计"已查清无问题"面：EventBus 33 处订阅全 MAIN；配置加载/EPG/换台解析全后台且异常闭合；ensureLoaded 无主线程调用方；BrowseTree/LiveBrowse 并发缓存安全；Prefers 全 apply()；Clock/Traffic/OSD 定时器生命周期正确；适配器 clear-then-add；Room 主线程为既有允许模式。
