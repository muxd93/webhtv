# LIVE1 — 直播共享 LiveSession 状态机抽取

## Recovery anchor

- **目标**：把 leanback / mobile 两套 LiveActivity 中重复的约 20 个选台/播放状态机方法抽取为共享 `LiveSession`，同时收敛 bean 上的 UI 寄生状态（selected/width）、删除弱判等 `Group.equals`、统一 parse 错误出口。行为差异（双击弹线路、节目单弹窗、内嵌菜单等）按 flavor 保留。
- **验收标准**：两 flavor 编译通过；换源/收藏/隐藏组解锁/换线/断网自动切线/catchup/数字选台行为与重构前一致；bean 无 UI 选中态；无 `Group.equals` 依赖。
- **计划状态**：已实施，待用户统一编译与设备验证。
- **当前文件/符号**：`app/src/main/java/com/fongmi/android/tv/live/LiveSession.java`、`live/LiveWidthCache.java`；bean `Group/Channel/Epg/Live`；`model/LiveViewModel`（新增 `message` LiveData）；`api/parser/LiveParser.Setting.find`；两 flavor `LiveActivity` 与 `ChannelAdapter/GroupAdapter`。
- **已完成动作与结果**：
  - 新增 `LiveSession`（纯 Java 状态机：tune/stepChannel/stepGroup/nextLine/setLine/fetch/onEpgDataClick/play 防循环 reload/unlock/keep 增删/onEnded 接续）与 `LiveWidthCache`（WeakHashMap 测宽缓存，替代 bean width 字段）。
  - bean 收敛：`Group` 删 selected/width/equals；`Channel` 删 selected；`Epg/Live` 删 width；`Channel` EPG dataList 访问加 synchronized（后台写/主线程读）。
  - UI 判等全部改引用比较（`session.isTuned` / adapter 自持 selectedPosition）；mobile GroupAdapter 补自持 selectedPosition（addAll 重置、payload 局部刷新），leanback ChannelAdapter 对齐 mobile 的自持方案。
  - `LiveViewModel.parse` 失败经新增 `message` LiveData 带原因上报（ExtractException 仍走 Result.error 原路径）。
  - `LiveParser.Setting.find` 前缀误判收紧为显式指令（`ua=` 等 + `#EXT*`/`#KODIPROP`）。
  - 两 flavor LiveActivity 改为实现 `LiveSession.Listener`（渲染/播放器操作），移除各自 ~15 个重复方法与对应字段。
  - 修复用户编译反馈：`this::onParseError` → `this::showParseError`；mobile `setPosition`/`updateOverlayMenuWidths` 增加 mSession 初始化前空守卫（applyPadLiveMode 早期路径）。
- **未验证项**：用户统一编译 + 设备冒烟（用户明确免除本轮编译验证）。
- **风险**：leanback 程序化选组与选中监听的一次性互触（已用 index 不一致才 setSelectedPosition 防循环）；keep 组频道为副本对象，`isTuned` 引用判等对副本语义一致（副本即当前播放对象）。
- **回滚**：单笔原子提交，`git revert` 即可。
- **唯一下一步**：task guard finish 提交并打 recovery tag。

## 设计要点

- **身份即真相**：adapter 列表、session、bean group 三方持有同一批 Channel/Group 引用；"是否当前台" 一律 `==`。`Channel.equals` 保留并注释锁定其唯一用途：解析期同名频道合并（`Group.find/add`）与台号查找。
- **单写者方向**：Live 树写入仍发生在 ViewModel 任务线程 + Session（主线程），本轮以 `Channel` EPG synchronized 收敛最危险的跨线程结构访问；更大范围的单写者串行化经评估（XML 解析与 LIVE 解析共享单线程会串行化 15s+30s 超时路径）暂不引入。
- **扩展性**：`LiveSession.Listener` 是纯回调契约，未来 Pad 模式、语音选台、外部遥控等新入口可直接复用状态机。
