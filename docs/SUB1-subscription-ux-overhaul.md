# SUB1 — 订阅源体验系统性修复（波次 1）

## Recovery anchor

- **状态**：波次 1 代码完成，编译+单测通过（2026-10-02）。真机端到端验证待用户；波次 2/3 立案待续（见 §1.C）。
- **目标**：把三轮评估发现的订阅源（直播/点播/墙纸配置）问题收敛为系统性方案，修复已确认缺陷集（波次 1），其余波次立案待续。
- **验收标准**：见「波次 1 验收标准」；全局要求两 flavor javac 编译通过、既有 live 单测通过、不回归 SWR/聚合/探测既有行为。
- **回滚**：单笔原子提交，`git revert` 即可（无数据迁移；aggregate.meta/aggregate.json 为运行时产物）。
- **唯一下一步**：用户真机验证波次 1（验收标准 1–8）；确认后按 §1.C 顺序启动波次 2（聚合源池管理页）。

## 1. 问题台账（三轮讨论 + 举一反三审计）

### A. 已确认需修复（波次 1 范围）

| # | 问题 | 证据位置 | 根因 |
|---|---|---|---|
| A1 | 点播多仓 depot 导入只取第一个源，其余静默丢弃（与直播 LIVE4 后行为不一致） | `VodConfig.parseDepot`（VodConfig.java:153-160） | 仅 `load(configs.get(0))`，未落库其余源 |
| A2 | 删除池内直播源后聚合内容残留最长 12h，且无法手动刷新 | `Config.delete`（Config.java:272）无联动；`LiveAggregator.refreshIfStale` 只看 ts | 删除动作不影响任何时间戳/触发器 |
| A3 | 聚合逐源串行拉取（每源 20s 超时），N 源导入等待时间长 | `LiveAggregator.aggregate`（LiveAggregator.java:239-247） | 顺序 for 循环 |
| A4 | 点播订阅无超龄静默刷新（长驻进程过期）；直播方案未沉淀为公共机制 | LIVE3 仅落在 `LiveConfig` | `refreshIfStale` 未上移 `BaseConfig` |
| A5 | 配置加载失败只有瞬时 Toast，无重试入口（首页/直播页均如此） | leanback `HomeActivity.getCallback`、mobile 同名、两 flavor `LiveActivity.onConfigError` | error 回调只 Notify.show |
| A6 | leanback `mConfigLoading` 失败后永不复位，进程存活期内 `initConfig` 被永久挡住（审计新发现） | HomeActivity.java:414-415，无复位点 | 标志位只在入口置 true |
| A7 | 历史列表隐藏当前使用中的配置，且每项无状态标注；删除无确认 | `ConfigAdapter.addAll`（ConfigAdapter.java:45）；`HistoryDialog.onDeleteClick` | removeIf(currentUrl) + 直接删 |
| A8 | 新增配置名字必须手输 | 两 flavor `ConfigDialog.saveConfig` | 无自动命名 |
| A9 | `Config.delete` 不清理 `ConfigCache`，孤儿缓存累积（审计新发现） | Config.java:272-276 | 无缓存联动 |
| A10 | 删除全部池内源后 aggregate.json 残留死频道（A2 边界） | `aggregate()` parsed.isEmpty() 直接 return false | 空池无清理路径 |

### B. 已确认维持现状（记录理由，不改）

| 项 | 决策 | 理由 |
|---|---|---|
| B1 | 进入直播页触发静默重拉、内容变化立即应用（可能短暂焦点跳动） | 进入页面本就是自然刷新点；延迟应用会让变化「永远不生效直到下次进入」，二者取即时生效；窗口仅 SWR 网络往返期 |
| B2 | WallConfig 不接入超龄刷新 | `isLoaded()` 恒 false，每次 ensureLoaded 都重新下载；墙纸低频变更，无 12h 需求 |
| B3 | 点播不做跨源聚合（只做全量展开进历史） | 点播聚合需合并 spider/parse/rules，冲突语义复杂（不同 jar 同 key、parse 优先级），收益存疑；直播聚合（LIVE4）有天然归一键（频道名），点播没有 |
| B4 | `LiveProbe` 探测对非聚合源弹 unsupported 而非隐藏按钮 | 按钮常显便于发现功能；toast 已说明原因，改动需动两 flavor 控制条布局，收益低 |

### C. 立案待续（波次 2/3，不在本 session）

- C1 聚合源池管理页（每源状态/上次拉取时间/频道数、增删启停排序；隔离区可视化+手动复活；承载「上次更新时间+立即更新」入口）——LIVE4 文档已记录延后，数据基础（meta.sources/probe/quarantine/lines）齐备，纯 UI 工作。
- C2 探测进度持久化（替代 25% 步进 Toast）——与 C1 同一 UI 波次。
- C3 双 flavor `ConfigDialog`（共 500+ 行重复）抽公共基类——破坏性重构，单独 guard session；同时补 mobile 缺失的推送回填（EventBus）能力。
- C4 jar 下载进度提示 + 失败降级（jar 失败不阻断配置本体生效）——涉及 `JarLoader`/`Download` 错误语义分层。
- C5 12h 阈值可配置 + 推荐源列表增强（频道数/日期字段、导入确认）——需设置 UI 波次。
- C6 历史项附 URL 副标题（需两 flavor 布局改造，C1 管理页会取代该入口）。

## 2. 方案对比与最终选择（按实际使用视角）

### A2+A10 删除联动重聚合

- 方案一：在 `Config.delete()`（bean）里直接调 `LiveAggregator`。❌ bean 层反向依赖 live/config 层，破坏分层；`Config.delete` 还有 Keep/History 联动语义，不宜再加权。
- 方案二：只在 `HistoryDialog.onDeleteClick` 挂钩。❌ 漏掉 `ConfigDialog.edit` 清空 URL 删除当前配置的路径（两 flavor），该路径同样可能删池内源。
- **方案三（采纳）**：`LiveAggregator.onSourceDeleted(String url)`（后台：池内无此源→忽略；池空→reset 文件+缓存+按需重载；否则重聚合+按需重载），由两处删除流程显式调用（HistoryDialog 两 flavor + ConfigDialog edit 两 flavor）。调用点少而明确，`LiveAggregator` 内聚池语义。
- 空池边界：`reset()` 删 aggregate.json/meta/ConfigCache(url()) 并按需 `load()`；聚合配置尚在历史中，加载得空频道列表（诚实呈现，可切其他源）。

### A3 聚合并发拉取

- 方案一：每源独立线程无上限。❌ 源数不可控（depot 可含几十 URL）。
- 方案二：复用 `Task.submit` 每源一任务。❌ Task 是单线程调度器（需核实），不提供并发度。
- **方案三（采纳）**：对齐 `LiveProbe` 的既有模式——`Executors.newFixedThreadPool(min(6, pool.size()))` + `CountDownLatch`，`findState` 仍在提交循环内串行（避免并发写 `sources` 数组），`lines` 映射在 latch 后按池序合并（`JsonObject` 非线程安全），结果按池序重组保证 merge 确定性。失败源语义不变（跳过+缓存回退）。

### A4 统一超龄刷新

- 方案一：照抄 `refreshIfStale` 到 `VodConfig`。❌ 第三份复制（Live/Vod/Wall 未来各一份）。
- **方案二（采纳）**：上移 `BaseConfig.refreshIfStale()` + `fetchTsPrefix()` 钩子 + `onFetched` 默认实现写时间戳；新增「加载进行中则跳过」守卫（`future != null && !future.isDone()`），消除冷启动 `initConfig` SWR 与 `onResume` 检查的双重拉取。`LiveConfig` 保留 `live_fetch_ts_` 前缀（与 `LiveAggregator` 写入的键兼容，不孤立既有数据）；`VodConfig` 新增 `vod_fetch_ts_` 前缀，钩子在两 flavor `HomeActivity.onResume`。WallConfig 不接入（B2）。
- 放弃方案三（WorkManager 周期任务）：CFGCACHE1 已否决常驻定时器方向，TV 长驻进程以内进页面/回前台为自然触发点。

### A5+A6 失败重试

- 方案一：`ProgressLayout` 增加 ERROR 态+重试按钮。❌ view_empty 被全应用复用，加按钮会污染所有空态；ERROR 态会遮住首页仍可用的内容（设置入口等）。
- **方案二（采纳）**：`Notify.retry(Context, String msg, Runnable)` 轻量确认对话框（MaterialAlertDialogBuilder，重试/关闭），四个 error 回调点接入；leanback 同时修复 `mConfigLoading` 复位（A6）。侵入最小、两 flavor 行为一致、TV 焦点可导航。

### A7 历史列表

- **采纳**：当前项保留在列表内并置顶、文本加「（使用中）」后缀、隐藏其删除按钮；其余项删除前弹确认。理由：隐藏当前项导致用户无法确认「正在用哪个」，置顶+标注是主流订阅管理器的惯例；禁止删除当前项同时天然规避「删除激活配置」的边界问题（清空 URL 的显式清除路径保留）。

### A8 名字自动填充

- **采纳**：新增配置时 name 为空则取 `UrlUtil.getName(url)`（已有工具，取 path 或 host）。编辑路径不自动填充（用户可能有意清空）。

### A9 孤儿缓存

- **采纳**：`Config.delete()` 内追加 `ConfigCache.delete(getUrl())`。bean → ConfigCache 单向依赖（同模块 util），无分层问题；重新添加同 URL 配置时天然走新鲜拉取。

### A1 点播多仓

- **采纳**：全量 `Config.find(item, VOD)` 落库进历史 + 加载第一个 + Toast「已导入 N 个点播订阅源」。不做聚合（B3）。

## 3. 波次 1 实施清单

1. `BaseConfig`：+`STALE_MS`、`fetchTsPrefix()`、默认 `onFetched`、`refreshIfStale()`（含 in-flight 守卫）。
2. `LiveConfig`：删本地 `refreshIfStale`/常量/`onFetched`，加 `fetchTsPrefix()`=live_fetch_ts_、`onSourceDeleted(String)` 委托。
3. `LiveAggregator`：+并发 `fetchPool`、+`onSourceDeleted(String)`、+`containsSource`、+`reset()`、空池边界。
4. `VodConfig`：+`fetchTsPrefix()`=vod_fetch_ts_、`parseDepot` 全量展开+提示。
5. `Config`：delete 追加 `ConfigCache.delete`。
6. `HistoryDialog` 两 flavor：当前项置顶标注+禁删、删除确认、调 `onSourceDeleted`。
7. `ConfigDialog` 两 flavor：新增路径名字自动填充；edit 清空删除路径调 `onSourceDeleted`（type==1）。
8. `Notify`：+`retry(Context, String, Runnable)`。
9. `HomeActivity` 两 flavor：error 回调→retry 对话框+mConfigLoading 复位（leanback）+`onResume` 挂 `VodConfig.get().refreshIfStale()`（mobile 新增 onResume）。
10. `LiveActivity` 两 flavor：`onConfigError`→retry 对话框（重试=`mSession.start(isEmpty())`）。
11. strings 三语言：`action_retry`、`config_in_use`、`config_delete_confirm`、`vod_depot_imported`。

### 波次 1 验收标准

1. 导入含 N(>1) URL 的点播多仓 → N 个源全部出现在点播历史，加载第一个，有导入提示；单 URL 行为不变。
2. 直播历史删除任一池内源 → 后台立即重聚合（Toast/重载语义与手动导入一致），被删源频道即刻消失；删除非池源无副作用；删光池内源 → 聚合文件与缓存清空，聚合配置呈现空频道。
3. 多源聚合导入耗时 ≈ 最慢单源（而非各源之和）；结果与串行版本一致（池序 merge、确定性排序不变）。
4. 冷启动首帧无双重网络拉取（in-flight 守卫）；长驻进程回首页超 12h 后静默更新点播站点。
5. 点播/直播配置加载失败 → 出现可聚焦的「重试」对话框，重试能恢复；leanback 首页失败后 mConfigLoading 复位可再次 initConfig。
6. 历史对话框显示当前项（置顶+使用中标注、无删除钮）；删除他项需确认。
7. 新增配置不填名字 → 自动取 URL path/host。
8. 既有行为回归契约：SWR 缓存先行/内容不变抑制事件/聚合确定性输出/LIVE5 探测策略全部不变（单测守护）。

## 4. 实施记录（2026-10-02）

- **`BaseConfig`**：+`STALE_MS`、`fetchTsPrefix()`（默认 `fetch_ts_`）、`onFetched` 默认写时间戳（键=前缀+md5(url)）、`refreshIfStale()`（in-flight 守卫 `future` 未完成即跳过，防冷启动双重拉取；空/sync/非 http 跳过；超龄 `App.post(load(Callback))`）。
- **`LiveConfig`**：删除本地 `STALE_MS/KEY_FETCH_TS/onFetched/refreshIfStale`；`fetchTsPrefix()` 返回 `live_fetch_ts_`（与 LiveAggregator 写入键兼容）；+`onSourceDeleted(int type, String url)`（type!=LIVE 空操作）委托聚合器。
- **`LiveAggregator`**：`aggregate(boolean)` 的逐源循环替换为 `fetchPool`（`newFixedThreadPool(min(6,N))`+`CountDownLatch`，`findState` 保持在提交循环串行、`lines` 溯源映射在 latch 后单线程按池序合并，结果按池序重组保证 merge 确定性）；+`onSourceDeleted`（池内才动作：池空→`reset()`+按需 `LiveConfig.load(ensureConfig(), Callback)`；否则 `aggregate()` 变化才重载）、+`containsSource`、+`reset()`（删 aggregate.json/meta/ConfigCache）。
- **`VodConfig`**：`parseDepot` 全量落库+多源 Toast 提示（仍加载第一个，depot 配置照旧删除）；`fetchTsPrefix()`= `vod_fetch_ts_`。
- **`Config`**：三个 delete 方法（静态×2+实例）统一追加 `ConfigCache.delete(url)` 清孤儿缓存。
- **`Notify`**：+`retry(Context, String, Runnable)`（MaterialAlertDialogBuilder，消息+重试/关闭；retry 为空仅提示）。
- **`ConfigAdapter`（两 flavor）**：当前项不再隐藏——按 URL 移到列表首位并加「（使用中）」后缀，隐藏其删除按钮；新增 `currentUrl` 字段。
- **`HistoryDialog`（两 flavor）**：删除前确认对话框（确认后才删）；删除后调 `LiveConfig.get().onSourceDeleted(...)`。leanback 补 `R` 导入。
- **`ConfigDialog`（两 flavor）**：新增路径名字为空时按 `UrlUtil.getName(url)` 自动命名；edit 清空 URL 删除路径追加 `onSourceDeleted` 联动。
- **`HomeActivity`（leanback）**：error 回调复位 `mConfigLoading` 并改 `Notify.retry(getActivity(), msg, () -> VodConfig.get().init().load(getCallback()))`；`onResume` 追加 `VodConfig.get().refreshIfStale()`。
- **`HomeActivity`（mobile）**：error 回调改 `Notify.retry(HomeActivity.this, msg, () -> initConfig())`；新增 `onResume` 挂超龄检查。
- **`LiveActivity`（两 flavor）**：`onConfigError` 改 `Notify.retry(this, msg, () -> mSession.start(isEmpty()))`。
- **strings 三语言**：+`action_retry`/`config_in_use`/`config_delete_confirm`/`vod_depot_imported`。

## 5. 验证记录（2026-10-02）

- 一次组合验证：`bash ./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac ':app:testLeanbackArm64_v8aDebugUnitTest' --tests 'com.fongmi.android.tv.live.*' -x :chaquo:installArm64_v8aDebugPythonRequirements` → **BUILD SUCCESSFUL in 2m 19s**（两 flavor javac 通过；live 单测——归一化+探测策略——全部通过）。
- 未验证项（真机端到端，与 LIVE3/4/5 一并验证）：多仓导入提示与历史展开、删除池内源即刻重聚合、首页失败重试对话框、长驻进程 12h 点播刷新、自动命名。
- 行为契约核对（代码级）：SWR 缓存先行/suppressEvent/聚合确定性（池序+稳定排序）/LIVE5 探测策略均未触碰语义；`refreshIfStale` 新增的 in-flight 守卫仅消除重复拉取。
- 构建副作用说明：单元测试编译触发了 Room schema 重新导出，暴露出预存缺口——`AppDatabase.VERSION` 已是 38 但 `app/schemas/.../38.json` 从未入库（跟踪止于 37.json）。该文件与本任务无关，按"无生成文件混入"原则未纳入提交（已删除，再次构建会重新生成）。建议在下次涉及 DB 的任务中把 38.json 补交入库。
