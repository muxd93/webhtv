# SMBFIX1: 播放链路根修 + SMB 封面接入

## Recovery anchor

- **目标**：落实 2026-09-29 review 结论与用户三项决策——①推送 id 规范化（PushId 单一格式/解析点，修复单文件本地播放回归与 SMB/推送标题乱码）；②移除「每日自动更新站源」；③重实现「主页键清理后台」；④Room 迁移对齐；⑤应用列表直启修复；⑥SMB 鉴权统一；⑦ElderCard 封面体系接入 SMB（服务器卡片弹窗 + 换封面 + 删除服务器）。
- **验收标准**：
  1. 本地单文件播放恢复（file:// 全链路不被剥坏）；文件夹连播/SMB 单文件/SMB 文件夹连播标题正确；
  2. 配置未就绪时点击历史/收藏等待 15s 而非立即空页；
  3. 主页「局域网/应用/文件」三入口有图标；应用列表可启动 leanback-only 应用；
  4. `:app:compileLeanbackArm64_v8aDebugJavaWithJavac`（mobile 变体视改动）通过；
  5. 各阶段独立提交 + `recovery/SMBFIX1-p<N>/<ts>` 标签。
- **分支/HEAD**：main @ d187f6ae8b（任务开始时）。
- **受保护脏路径**：除各阶段 adopt 外的全部预存脏路径（含 mobile `KeepActivity/KeepAdapter` —— ELDER1 记录为另一会话工作，本任务禁改；`.zcodeignore`、`app/build.gradle`、`Github.java` 等）。
- **回滚锚点**：每阶段独立 commit + tag；revert 对应提交即还原。
- **决策记录（supersede HOME1/ELDER1 旧计划）**：封面经 ElderCard 接入 SMB（用户 2026-09-29 明确）；每日自动更新移除（复核确认每次冷启动本就全新拉取配置，功能冗余）；清理后台用可启动应用列表重实现（getRunningAppProcesses 自 Android 5.1 对第三方无效）；凭证明文存储风险用户确认可接受。
- **执行方式变更（用户 2026-09-29 指示）**：五个阶段一次性写完代码，编译由用户统一执行后一起修；提交/打标推迟到编译通过之后。原 SMBFIX1-p1 会话手工置为 finished 并由 SMBFIX1 全阶段单会话取代（scope = 各阶段文件并集，adopt 各预存脏文件；mobile KeepActivity/KeepAdapter 仍为保护路径）。
- **当前状态**：阶段 1-5 代码全部完成；静态一致性检查（残留符号/新符号落点/import）已通过；编译验证推迟。
- **下一步（唯一）**：用户统一编译 → 按「统一编译点验清单」一起修错 → 全部通过后 `task_guard.sh finish` 单笔提交 + recovery 标签。

## 统一编译点验清单

1. 编译目标：`:app:compileLeanbackArm64_v8aDebugJavaWithJavac` + `:app:compileMobileArm64_v8aDebugJavaWithJavac`。
2. 无编译器可查但需人工留意的点：
   - `SmbHelper.ReadHandle` 内 `file.read(byte[], long)` 为 smbj 0.14 签名（原 SmbDataSource 已用同签名编译过，风险低）；
   - 新布局 `dialog_smb_server.xml` / `adapter_smb_server.xml` 的 ViewBinding 类随编译生成；
   - `ElderCardDao.findByType(ElderCard.Type)` 依赖 Room 2.8.4 内建枚举转换（已确认版本）。
3. 迁移 SQL 与实体 schema 一致性已逐列人工比对（无 DEFAULT 子句）；Room 启动校验为最终裁决。

## 真机回归清单（编译通过后）

1. 本地单文件播放（文件入口点击视频）：能播、标题=文件名、OSD/队列标题正确；
2. 本地文件夹长按连播（含/不含子文件夹两种开关）：能播、详情标题=文件夹名；
3. SMB：扫描→添加→浏览→单文件播放→文件夹长按连播（匿名与带密码各一）；
4. 「局域网」卡片弹窗：默认色块封面可见、点击进浏览、长按换封面三形态（内置/本地图/网址）、删除服务器；
5. 配置未就绪时（冷启动立即点历史/收藏）：等待加载而非立即空页；
6. 主页「局域网/应用/文件」三入口图标可见；应用列表能启动 leanback-only 应用；
7. 设置页无「每日自动更新站源」行；主页键清理后台开关生效。

## 阶段与内容

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | PushId 规范化（新 utils/PushId；Flag.setEpisodes 删防御剥离；SiteApi 用 playList/displayName；两端 VideoActivity push/file 重写、删 3 份重复 helper、configWaitStart 修复；FileActivity/SmbBrowserActivity 连播走 PushId.folder；Func 图标映射 + 3 个 vector） | 代码完成 |
| P2 | MIGRATION_36_37 恢复 HEAD（只建 tombstone）；MIGRATION_37_38 建两张新表且 SQL 与实体 schema 逐字一致（去 DEFAULT）；移除每日更新全链路（HomeActivity/Setting/SettingActivity/布局/三语 strings） | 待做 |
| P3 | AppListUtil.launch 直启（setClassName）；killBackgroundApps 用 AppListUtil.query 重实现；删 Clock.period | 待做 |
| P4 | SmbHelper.openForRead 统一鉴权+超时；SmbDataSource 改调；**顺带**：VideoFolderUtil/SmbHelper 段名经 PushId.segment 消毒（P1 scope 未含这两个文件，推迟至此） | 待做 |
| P5 | SmbServerDialog（卡片网格 + ElderCard SMB 行同步 + ElderCoverPickerDialog 接线 + 删除服务器）；HomeActivity.onSmbEntry 接入；ElderCard.applyCover | 待做 |

## 已知限制（不修，记录）

- **待查问题（用户同意延后）**：本地文件夹连播时，集数列表首项显示名偶为「播放」，其余项正常；播放本身不受影响。已埋三层定位日志（SpiderDebug，tag=`push-folder`）：①`FileActivity.playFolder` 输出构建的复合串；②`SiteApi.detailContent` PUSH 分支输出剥离后的 playUrl 与展示名；③leanback `VideoActivity.setEpisodeAdapter` 输出首集 name/desc/displayName/url。下次调试开 SpiderDebug 对比三处即可定位责任层（拼串/解析/渲染）。排查中已排除：EpisodeTitleCompact（未生效）、Source.parse（仅展开 Thunder/Youtube）、Episode.trans（仅简繁转换）。
- 文件名/文件夹名含 `$`/`#` 时会被替换为空格显示，属预期行为（P4 已覆盖连播与单文件入口）。
- `extractFileUrl`（SiteApi）保留：处理 push_agent spider 返回复合串的真实场景。
- OSD 标题对无末段的裸 URL（如 magnet:）回退为整串，与 HEAD 行为持平。
- 阶段 5 后仍休眠：ElderCard 其余 Type（KEEP/HISTORY/LIVE/APP/LOCAL_FILE/ADD）与 FIRST_FRAME（无帧提取实现）。
