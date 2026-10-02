# SUB2 — 聚合源池管理页 + 探测进度持久化（SUB1 §1.C1/C2）

## Recovery anchor

- **状态**：代码完成，编译+单测通过（2026-10-02）。真机端到端验证待用户。
- **目标**：为直播聚合提供源池管理界面（每源状态/上次拉取/频道数、增删/启停/排序、隔离区可视化+手动复活、聚合更新时间+立即更新+检测入口），并把探测进度持久化到该页面（LiveProbe 进度监听）。
- **验收标准**：
  1. 设置页（两 flavor）直播配置区新增「聚合源管理」入口，常显；从未聚合时页面显示引导态，添加首个源即建池并聚合。
  2. 源行展示：名称、状态（可用/失败/已停用）、上次拉取时间、频道数；点击行=启停切换（触发重聚合）；▲▼ 排序；✕ 移出池（不删历史配置）。
  3. 「添加」输入 URL（名字可空，默认 UrlUtil.getName）→ 落库 Config(type=1) + 入池 + 聚合 + 重载。
  4. 「立即更新」= aggregate(true)，内容变化静默重载；状态行显示上次聚合时间与频道/线路数。
  5. 「检测」= LiveProbe.toggle() 既有语义；运行中页面实时显示 n/m 进度（新增进度监听，替代仅 Toast）。
  6. 隔离区列表显示 组名+频道名，「复活」= 移出隔离区 + 对其线路重置探测 streak → 重聚合回插。
  7. 破坏性边界：启停/排序/移出/复活只改 meta 并显式重聚合；不自动删除任何 Config。
- **回滚**：单笔原子提交 `git revert`；meta 新字段（enabled/chan/agg）对旧代码透明（读取时容错缺省）。
- **唯一下一步**：用户真机验证（验收标准 1–7）；确认后波次 3 启动 C3（ConfigDialog 抽基类）/C4（jar 下载反馈）/C5（12h 可配置）。

## 设计决策

- **UI 形态**：跟随仓库惯例做两 flavor 对话框（`LivePoolDialog` + `dialog_pool.xml` + `PoolAdapter`），结构对齐 HistoryDialog；行内按钮用文本（▲ ▼ ✕）避免新增 drawable；入口图标复用 `ic_setting_refresh`。
- **meta 扩展**：sources[] + `enabled/chan`；新增 `agg:{ts,channels,lines}`；全部容错缺省，旧 meta 直接可用。
- **聚合触发**：池操作（启停/增删/排序/复活）后由 UI 显式 `aggregate(true)` + 变化才 `LiveConfig.load()`；聚合器不隐式聚合。
- **探测进度**：`LiveProbe.setProgressListener(volatile)`；execute 每条完成回调（UI 自行节流），轮末 onFinished；池页 onStart/onStop 挂卸。控制条入口行为不变（Toast+再点取消）。
- **复活语义**：仅移出隔离区并把其 http 线路 streak 清零（保留 ok=false → 沉底但不再满足隔离条件），避免复活后立即被 2 轮旧 streak 重新隔离。

## 实施记录（2026-10-02）

- **`LiveAggregator` 扩展**：
  - `aggregate()` 统计频道/线路数不再受 notify 门控，成功后写入 `meta.agg={ts,channels,lines}`；`fetchPool` 任务内回写 `state.chan`（该源解析频道数）。
  - 新增内部类 `SourceInfo{url,name,ok,ts,enabled,channels}` / `QuarantineInfo{group,name,key}`。
  - 新增管理接口（只改 meta，聚合由调用方显式触发）：`poolStatus()`、`aggInfo()`、`addSource(Config)`（追加末尾、默认启用、去重）、`removeSource(url)`（池空时 `reset()`）、`setEnabled(url,bool)`、`move(url,delta)`（相邻交换+重排 order）、`quarantineList()`、`revive(group,key)`（按 组+归一键 移出隔离区并对其 http 线路 `streak=0`，防旧失败轮次立即再隔离）。
- **`LiveProbe` 扩展**：`ProgressListener{onProgress(done,total), onFinished(results,ok)}` + `setProgressListener`（volatile，回调经 `App.post` 切主线程）；每条完成即回调（含取消轮）；结果为空/正常结束均保证 onFinished；既有 Toast/取消语义不变。
- **新增 `ui/adapter/PoolAdapter`（main，两 flavor 共用）**：三视图类型（源行/隔离标题/隔离行）；源行=名称+状态（可用/失败/已停用）+上次拉取时间（`MM-dd HH:mm`/未拉取）+频道数，整行点击=启停，▲▼=排序，✕=移出；隔离行=组·频道名+复活按钮。
- **新增 `LivePoolDialog`（leanback/mobile 各一）**：状态行（pool_none 引导态 或 上次聚合时间+频道/线路数）、添加/检测/立即更新按钮、RecyclerView（空池隐藏，320dp 固定高）；添加=程序化 EditText 对话框（URL 必填，名字缺省 `UrlUtil.getName`，Config.find 落库后入池）；所有池操作经 `apply()`：改 meta → 立即刷新 → 后台 `aggregate(true)`，变化且聚合为当前配置才 `LiveConfig.load()`；`onStart/onStop` 挂卸探测进度监听（进度行复用 `live_probe_progress` 文案）。
- **布局（两 flavor）**：`dialog_pool.xml`（同一份，mobile/leanback 样式一致）、`adapter_pool_source.xml`、`adapter_pool_quarantine.xml`、`adapter_pool_header.xml`；leanback 行背景用 `selector_item`（TV 焦点），mobile 用 `shape_item`。
- **设置页入口（两 flavor）**：直播配置行 liveHistory 后新增 `livePool` 图标（复用 `ic_setting_refresh`），liveHistory 补 marginEnd；leanback `SettingActivity` / mobile `SettingFragment` 注册 `onLivePool → LivePoolDialog.create().show(...)`。
- **strings 三语言**：+`pool_manage/pool_none/pool_add/pool_update_now/pool_last_agg/pool_src_ok/pool_src_fail/pool_src_off/pool_never/pool_channels/pool_quarantine/pool_revive/pool_input_hint/pool_input_name`。

## 验证记录（2026-10-02）

- 一次组合验证：`bash ./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac ':app:testLeanbackArm64_v8aDebugUnitTest' --tests 'com.fongmi.android.tv.live.*' -x :chaquo:installArm64_v8aDebugPythonRequirements` → **BUILD SUCCESSFUL in 41s**。
- 过程记录：首轮编译暴露 4 类错误（initView/initEvent 可见性、leanback 未实现抽象 getBuilder、静态内部类引用外部实例字段 format、RecyclerView 无 setMaxHeight）与 1 处 lambda 非终态捕获（ok），均已修复后一次通过；无遗留警告新增。
- 未验证项（真机端到端）：池管理页增删/启停/排序/复活/立即更新全链路、检测进度行、TV 焦点链（livePool 入口可达性）、空池引导态。
- 行为契约核对：聚合器输出/探测策略/隔离判定未改；meta 新字段（enabled/chan/agg）对旧读取路径透明；`savePool` 在 depot 重导时按既有语义整体重置（含 enabled）。
