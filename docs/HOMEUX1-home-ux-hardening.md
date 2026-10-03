# HOMEUX1 原生首页 UX 系统性加固

## Recovery anchor

- 目标：修复首页评估发现的问题并举一反三处理同类问题，一个原子提交落地。
- 验收标准：见「验收标准」节；最便宜决定性验证 = leanback + mobile 两变体 Java 编译通过（排除 `:chaquo:installArm64_v8aDebugPythonRequirements`，先例见 docs/LIVE3-live-subscription-autoupdate-preset.md:28，本机仅 Python 3.13 属环境限制）。
- 状态：实施完成并编译通过（2026-10-03 19:5x，leanback+mobile javac BUILD SUCCESSFUL）。
- 实施落点：HomeActivity（首屏本地渲染 showLocalContent/分区 HomeRows/工具栏补间/历史限流+尾卡/空态标记/清空历史确认/退出文案入资源/TTS Vod 焦点/滚动策略统一/时钟带日期）、新增 leanback HistoryActivity+HistoryAdapter+activity_history.xml+manifest、KeepActivity 清空确认、RestoreDialog×2 恢复与删备份确认、SettingActivity 备份文案、ImgUtil failed TTL、progress_vod 圆角、leanback×3+mobile×3 字符串资源。
- 回滚锚点：单一 commit + recovery tag，revert 即回滚；无数据/结构变更。
- 下一动作：编译通过后 task_guard finish。

## 背景

首页评估（会话内）确认的问题：删除模式二次长按清空全部历史无确认、空数据分区标题疑似残留、冷启动全屏 spinner、工具栏收起内容跳变、历史行无上限且无全量入口、封面失败永不重试、焦点播报缺 Vod 卡片、横向滚动策略不一致、硬编码中文。举一反三扫描（两个探查代理，2026-10-03）额外确认：

- leanback `KeepActivity.clearKeep()`（:72-78）清空收藏无确认；mobile 版有确认（MaterialAlertDialogBuilder）——两端不对称。
- `RestoreDialog`（leanback :69-85 / mobile :54）选择备份文件即恢复（`Backup.restore` → `clearAllTables` 清全表），无二次确认；删备份文件亦无确认。
- mobile `SyncDialog` 长按"覆盖本地"删除本地 Keep/History 无确认（远程同步场景，本轮延后）。
- leanback 无 HistoryActivity：历史只有首页一行（不限量），无全量管理入口；收藏行却有限流+查看全部。
- leanback 中文文案目录为 `values-zh-rCN`/`values-zh-rTW`；"再按一次返回键退出"（HomeActivity:989）、"备份应用数据"（SettingActivity:391）、"恢复应用数据"（RestoreDialog:70）无任何资源等价物。
- `ProgressLayout.showEmpty()` 仅 VideoActivity 使用；首页推荐区为空时无任何提示。
- `ImgUtil.failed` 为永不清理的 Set（影响全部界面的封面重试）。
- `Clock` 默认 "HH:mm:ss" 被 4 处共用（首页/播放器/直播/投屏），`format()` 公开方法无人调用。
- leanback `Cache` 为纯内存筛选器缓存，非持久化推荐结果。

## 最佳实践证据

- 外部证据类：developer.android.com 直连超时，环境未配置代理（env 无 proxy，WebFetch/连接均失败），官方 TV 质量指南原文未能获取。按契约记录：外部证据类不可用，本设计不声称外部最佳实践结论。
- 采用的替代证据（均为主仓库内既有先例，一致性风险最低）：
  - 破坏性确认：mobile `HistoryActivity.onDelete()`（:79-86）与 leanback `HistoryDialog.onDeleteClick`（:94-103）的 `MaterialAlertDialogBuilder` 模式 + main 资源 `dialog_positive/dialog_negative/dialog_delete_global_history`。
  - 空态：`ProgressLayout.showEmpty()` / view_empty（Lottie+文案）已存在；KeepActivity 自管 empty 视图模式。
  - 网格历史页：直接复用 KeepActivity（GridLayoutManager+SpaceItemDecoration+BaseDiffAdapter+焦点缩放）与 HistoryPresenter 的既有绑定/进度条/聚合站点名逻辑。
  - 行尾"查看全部"卡：KeepMorePresenter + Marker 路由（首页收藏行既有）。
  - 首屏本地数据先行：HomeActivity 的 func/history/keep 渲染本就不依赖网络配置（getHistory/getKeepRow/setFunc 均读本地 DB/设置）。

## 方案对比与推荐

### D1 冷启动首屏
- A 不变（全屏 spinner 直到配置加载）：慢网络黑屏数秒，最大痛点不解决。
- B stale-while-revalidate：立即渲染功能行/历史/收藏 + 推荐区进度行，配置加载后 `showContent()` 照旧刷新（web 首页站点加载完切 web overlay，行为同现状）。
- C B+持久化上次推荐结果：需新增持久化（现有 Cache 仅内存），陈旧封面有误导性，收益/成本比低。
- **推荐 B**。改动集中在 initView：去掉初始 `showProgress()`，改为 `showLocalContent()`。

### D2 分区标题残留
- A 继续手工 index 运算：已证易错。
- B `HomeRows` 小助手：ensure/remove 以 header+row 为单元原子化，三区统一走它。
- C 放弃 ArrayObjectAdapter 换多 type RecyclerView：破坏 leanback 焦点/Presenter 体系，风险不可控。
- **推荐 B**（新增 `ui/custom/HomeRows.java`，仅首页使用）。

### D3 破坏性操作确认
- A 各处行内 `MaterialAlertDialogBuilder`（仓库既有惯例，资源可复用）。
- B 新建统一 Confirm 工具类：多一层抽象，偏离仓库惯例。
- C 双击确认：不可发现，TV 上更差。
- **推荐 A**。落点：leanback HomeActivity 清空历史、leanback KeepActivity 清空收藏、leanback+mobile RestoreDialog 恢复前与删备份文件前。延后：mobile SyncDialog 覆盖本地（远程同步长按，场景小众，记录为后续）。

### D4 历史行溢出
- A 保持不限量：行超长难找片，且与收藏行不一致。
- B 限流（10 条）+ 行尾"查看全部"卡 → 新建 leanback HistoryActivity（复用 KeepActivity 结构 + HistoryPresenter 数据逻辑），删除全部带确认。
- C 仅限流无尾卡：切除全量入口，体验回退，不可取。
- **推荐 B**。新文件：HistoryActivity/HistoryAdapter/activity_history.xml + manifest 注册。

### D5 封面失败重试
- A `Map<url,时间戳>` TTL（5 分钟）+ 超阈值惰性清理：内存有界、网络恢复后自动恢复。
- B 定长 LRU + 换配置清空：语义复杂。
- C 每次进首页清空：粒度错。
- **推荐 A**（改 `ImgUtil`，全界面受益）。

### D6 工具栏收起跳变
- A padding 变更 150ms 补间（ValueAnimator）：最小改动，保留现有几何与 web overlay 联动。
- B toolbar 常驻 overlay 化：改几何语义，影响 web 三种 chrome 模式联动，回归面大。
- **推荐 A**。

### D7 其他（低风险小项）
- Vod 卡片焦点 TTS 播报 + contentDescription：`VodPresenter.OnClickListener` 加 default 方法，三个 Holder 挂焦点监听，仅 HomeActivity 覆写（TTS 开启时生效）。
- 历史行滚动策略 ALIGNED→ITEM：与其它行统一（一行）。
- 首页时钟 `.format("MM-dd EEE HH:mm:ss")`：仅改首页调用点，不动默认值（播放器/直播/投屏 widget 不受影响）。
- 推荐区空态标记行：`HomeEmpty.Marker` + `HomeEmptyPresenter` + 居中文案（复用 `error_empty`）。
- `progress_vod` 进度条 2dp 圆角。
- 硬编码中文入资源（三语目录）：exit_press_again / dialog_clear_history / dialog_clear_keep / dialog_restore_confirm / dialog_delete_backup / backup_title / restore_title。
- KeepMorePresenter 尾卡 +30dp 高度：确认为与带名称条卡片底对齐，保留不动。

## 不做（记录）

- mobile SyncDialog 长按覆盖本地确认（后续）；功能行重排/收纳（产品决策）；卡片渐变 scrim 视觉重设计（需设计稿）；主线程 DB 全面异步化（独立性能任务）；mobile 首页对齐本轮首页结构性改动（mobile 无该 toolbar/行结构，不适用）。

## 实施步骤

1. task_guard start（standard）。
2. ImgUtil TTL、Clock 调用点、progress_vod 圆角、字符串资源（leanback×3 目录 + mobile 所需目录）。
3. HomeRows + HomeActivity 改造（首屏/分区/工具栏动画/历史限流+尾卡/空态标记/确认弹窗/退出文案/滚动策略/时钟/TTS）。
4. 新增 leanback HistoryActivity/HistoryAdapter/activity_history.xml + manifest。
5. KeepActivity、RestoreDialog(×2 flavor)、SettingActivity 确认弹窗与文案。
6. 编译验证（leanback + mobile 变体 Java 编译）+ 变更路径复查。
7. task_guard finish（提交 + recovery/HOMEUX1/* 标签）。

## 验收标准

- 冷启动立即见功能行/历史/收藏与推荐加载行，无全屏 spinner 阻塞；配置加载完成后推荐填充或切 web 首页，行为与现状一致。
- 无历史/无收藏时不出现无内容行的分区标题；出现数据后标题+行成对出现。
- 清空历史/清空收藏/恢复备份/删除备份文件均先弹确认；聚合开启时提示文案覆盖"所有配置"语义。
- 历史行最多 10 条 + 查看全部尾卡；尾卡进入新历史页，历史页支持删除模式与清空（带确认）。
- 封面加载失败 5 分钟后可重试；工具栏收起时内容平滑过渡无跳变；焦点播报覆盖推荐卡片（TTS 开启时）。
- 全部新增文案走资源（EN/zh-rCN/zh-rTW）；leanback+mobile 编译通过。
- 无设备可用：设备级确认项（标题残留视觉、动画手感、TTS 播报）标注为待真机抽查，不声称已验证。

## 回滚

单 commit + 注释标签，`git revert` 即可整体回滚；无 DB schema/配置迁移；新增文件均为增量。
