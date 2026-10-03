# X16 跨点播配置的历史聚合展示

## 恢复锚（Recovery anchor）

- 目标：历史记录不再按当前点播配置（cid）隔离展示——开启聚合后，历史页/电视端历史弹窗/首页"最近观看"行显示全部配置的近期历史；点击非当前配置的条目时，确认后自动切换到该条目所属配置并进入详情续播。
- 验收：聚合关闭（默认）时行为与现状完全一致；开启后列表跨配置按时间排序、每条删除正常、清空带"删除所有点播配置"明确确认、跨配置点击走"确认→切配置→详情续播"；**无 Room schema 迁移**（History.cid 列已存在，仅新增 DAO 查询）；双端 arm64 release 编译 + 现有单测全绿。
- 回滚：单提交 revert；设置键默认 0 保证零行为变化。

## 现状（基于 694126beaa 复核）

- 数据层：`History.cid` = 配置 id；`History.get()` 一律按 `VodConfig.getCid()` 过滤（60 天窗）；`HistoryDao.findAll()` 跨 cid 查询已存在但无时间窗、未被历史 UI 使用。`AppDatabase` version 38，本功能**不需要迁移**。
- UI 层：手机端 `HistoryActivity`（onItemClick → `VideoActivity.start(siteKey, vodId, ...)`，onItemDelete → `deleteAndSync()` 已带同步）；电视端无独立历史页，为 `HistoryDialog` + `HistoryPresenter`，另有首页"最近观看"行（`HomeActivity.getHistory()`，`History.get()`@582）。
- 换源/续播既有能力：同名历史 `findByName`/`merge`（站点内换源续播）继续生效，本功能不触碰播放页读写历史逻辑（saveHistory/checkHistory 仍按当前 cid）。

## 源参考与取舍（Silent1566 X5「全局历史续播」，2026-07-30 MVP）

源实现（History.java +945 行等）将三态设置、跨 cid 展示/排序、**TMDB 身份聚合**、自动全局搜索换源、标准季集持久化织为一体。本仓裁定不做 TMDB，因此 **X16 参考重写**，仅取其"跨配置聚合展示 + 点击续播"语义，做以下刻意偏离：

1. **不做** TMDB 身份聚合与全局搜索自动换源（删除其全部搜索/竞态/保护机器）；点击续播改为**确定性切换**：条目自带 cid → `Config.find(cid)` → 确认 → `VodConfig.load(config, callback)` → 成功后 `VideoActivity.start(...)`（siteKey/vodId 在目标配置下解析，天然正确）。
2. **不做**标准季集持久化（依赖 TMDB 集数映射），集数续播沿用基线既有提取逻辑。
3. 聚合范围 = 历史弹窗/历史页 + 首页最近观看行；播放页内换源续播不改动。
4. 设置项为二态（关/聚合），预留扩展。

## 方案

1. **设置键**：`Setting.getHistoryAggregation()` → `Prefers.getInt("history_aggregation", 0)`（0=关 1=聚合）；键加入 Backup APP_PREFS 白名单；设置开关 UI 随批次 C。
2. **DAO**：新增 `findAcrossConfigs(long createTime)`：`SELECT * FROM History WHERE createTime >= :createTime ORDER BY createTime DESC LIMIT 60`（与现单 cid 查询同窗同序；跨 cid 去重不做——同名不同源各自成条）。
3. **History**：新增 `getAll()`（`findAcrossConfigs(HISTORY_TIME 窗)`）；新增静态辅助 `configNameMap()`（`Config.getAll(VOD)` → id→desc 映射，供列表渲染一次性预取）。
4. **列表渲染（两端）**：聚合开启时数据源 `History.get()` → `History.getAll()`；当条目 `cid != 当前 cid` 时在既有副文案后追加来源标记（` · ` + 配置名；配置已删除显示"源已删除"），不新增布局。
5. **点击跨配置续播（两端同一语义）**：`item.getCid() == 当前` → 原路径；否则弹确认（"切换到「配置名」继续播放《vodName》？"）→ 确认后 `VodConfig.load(Config.find(cid), callback)` → success 后按原 `VideoActivity.start(...)` 打开；`Config.find` 为空 → toast"该记录所属配置已删除"。切换期间沿用两端既有加载反馈。
6. **删除/清空语义**：单条删除不变（`deleteAndSync()` 按条目自身 cid，天然跨配置）；"清空"在聚合模式下确认文案改为"确定删除所有点播配置的历史记录吗？"并清全部（DAO.delete()），非聚合模式保持现状。
7. **首页最近观看行**：同一数据开关（`History.get()` → `getAll()`），点击行为同上；无进度条等渲染改动。

## 验证

- 现有单测全绿（不触碰 failover/watchdog 路径）；
- 双端 arm64 release Java 编译；
- 聚合开关关闭时的回归 = 现状（代码评审 + 可选设备抽查）；
- 设备实测项（随批次 A 清单）：开启聚合后多配置历史混排、跨配置点击切换续播、删除/清空。

## 实施步骤（批次 B）

1. DAO 新查询 + History.getAll()/configNameMap() + Setting 键（含 Backup 白名单）；
2. 手机端 HistoryActivity（数据源、badge、点击切换、清空语义）；
3. 电视端 HistoryDialog/Presenter + 首页最近观看行（同语义）；
4. 单测（如 DAO 查询可测部分）+ 双端编译 + 文档收尾。

## 实施记录（批次 C 完成，2026-10-03）

- 增强功能页（两端 `SettingEnhance*`）新增"跨配置历史聚合"开关（`history_aggregation`，三语文案），与容灾模式选择器同一批次落地；`Setting.putHistoryAggregation` 存取器补齐。
- 至此 X16 功能闭环：开关在 UI 可达，聚合展示/跨配置切换续播/全局清空全部可由用户直接开启与验证；设备实测项并入批次 A。

## 实施记录（批次 B 完成，2026-10-03）

- **数据层**：`HistoryDao.findAcrossConfigs(createTime)`（同窗同序的跨 cid 查询）；`History.getAll()`/`configNameMap()`/`deleteAllAndSync()`（按表内现存 cid 去重后逐一走 `PlaybackProgressWriter.deleteAllFromUser(cid)`，保证删除墓碑完整）；`Setting.isHistoryAggregation()`（键 `history_aggregation`，默认 0）；键入 Backup 白名单并补测试断言。
- **UI**：新增 `utils/HistoryOpener`——聚合模式下跨配置条目弹确认（"切换到「xx」继续播放《yy》？"）→ `VodConfig.load` 成功后 `VideoActivity.start`；`Config.find` 为空提示"该记录所属的配置已删除"。手机 `HistoryActivity` 与电视 `HomeActivity`（首页最近观看行）的点击统一走该入口；两端列表数据源按开关切换，跨配置条目的站点名替换为来源配置名（`setConfigNames` 预取映射）；手机删除确认与电视 `clearHistory` 在聚合模式下使用全局清空文案并走全量删除。
- **实现修正**（编译期发现）：匿名 `Callback` 子类内 `start(...)` 被继承的 `Callback.start()` 遮蔽，改限定调用 `HistoryOpener.start(...)`；`HistoryOpener` 补 `Setting` import。
- **验证**：双端 arm64 release Java 编译通过；`:app:testMobileArm64_v8aDebugUnitTest` 聚焦 BackupPreferenceFilterTest 7/7 + setting 测试通过。设备实测项归入批次 A（聚合开关此时需经 backup/手动写偏好开启，UI 开关随批次 C）。
