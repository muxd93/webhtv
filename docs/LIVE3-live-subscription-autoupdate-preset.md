# LIVE3 — 直播订阅式自动更新 + 内置推荐源

## Recovery anchor

- **状态**：代码完成。main + leanback 编译通过（javac 全量类型检查）；mobile 编译被 **HEAD 预先存在**的 `mobile/.../LiveActivity.java:637`（`nextLine(false)` 符号缺失，LIVE1 重构遗留，`git show HEAD:` 已证实）阻断，与本任务无关。真机验证待用户。
- **目标**：① 长驻进程下频道内容自动跟随上游：进直播页时若内容超龄则静默重拉；② 设置页内置推荐订阅源清单（远端可更新），一键导入；③ 顺手兼容：m3u/txt URL 行剥离 `$` 信息后缀（iptv-api `open_url_info` 约定）。
- **验收标准**：
  1. 修改上游直播源内容 → 进直播页 → 频道静默更新（变化对下次进入生效，不打断当前观看）；内容未变 → 无任何事件/焦点扰动（复用 suppressEvent）。
  2. 断网/拉取失败 → 行为与现状一致（缓存兜底），不因超龄检查引入新的失败路径。
  3. 设置页可见推荐源列表，一键导入后写入 Config(type=1) 历史并直接加载；推荐清单可被远端 JSON 更新。
  4. 带 `$xxx` 后缀的 URL 行可正常播放（后缀不进入 URL）。
- **回滚**：单笔原子提交，`git revert` 即可。
- **唯一下一步**：用户决定是否以独立小任务修复 mobile LiveActivity:637 预存编译错误，并进行真机统一验证。

## 实施记录（2026-10-01）

- `BaseConfig`：新增 `onFetched(Config)` 钩子，在 `load(Config)` 的 `fetchJson` 成功且 `parseAndCache` 生效后回调；回退缓存的失败路径不回调。
- `LiveConfig`：新增 `STALE_MS`（12h，对齐主流聚合源更新节奏）、`onFetched` 覆盖（写 `Prefers` 键 `live_fetch_ts_<md5(url)>`——**缓存文件 mtime 不可用**，`parseAndCache` 每次加载都重写缓存文件）、`refreshIfStale()`（空配置/sync/非 http URL 跳过；超龄经 `App.post(this::load)` 走既有 SWR）。
- `LiveSession.start(!empty)`：进入直播页时调用 `refreshIfStale()`；空配置路径本就带 SWR，不重复触发。
- `LiveParser`：新增 `stripInfo`（`$` 后仅当不含 `://` 才剥离），应用于 m3u URL 行与 txt 多线路两处。
- `bean/LivePreset`（新）：assets `live_presets.json` 兜底 + 远端清单（`https://raw.githubusercontent.com/muxd93/webhtv/main/other/live_presets.json`）经 ConfigCache 覆盖，`refresh()` 静默后台更新。
- `app/src/main/assets/live_presets.json`（新）与 `other/live_presets.json`（新，远端源，内容一致）：范明明 global/ipv6 + iptv-org 三项。
- 两 flavor `dialog_config.xml` 新增 `preset` 按钮（仅 `type==1 && !edit` 显示）；两 flavor `ConfigDialog` 新增「推荐源」列表（`MaterialAlertDialogBuilder.setItems`）与 `importPreset`（预设 EPG 仅在全局 EPG 为空时挂载，避免覆盖用户配置）。
- strings 三语言（values/values-zh-rCN/values-zh-rTW）新增 `live_preset`。

## 验证记录（2026-10-01）

- 编译：`bash ./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac -x :chaquo:installArm64_v8aDebugPythonRequirements`
  - main + leanback：**通过**。
  - mobile：**失败于预先存在缺陷** `LiveActivity.java:637`（HEAD 即如此，见 `git show HEAD:...` 证据）；该次 javac 全量编译仅报此 1 个错误，故本任务新增的 mobile ConfigDialog/布局绑定类型检查通过。
  - `-x chaquo` 原因：本机仅 Python 3.13，Chaquopy 钉死 3.10（环境限制，非代码问题），该任务仅影响打包资产不影响 javac。
- 预设清单 JSON：`python -c json.load` 校验 3 项有效。
- 未验证项：设备冒烟（超龄刷新、推荐源导入、`$` 后缀播放）；mobile 全量编译（被预存缺陷阻断）。

## 背景与关键证据

- 触发点现状：冷启动 `HomeActivity.initConfig`、开机 `BootReceiver`、手动换源均走 `BaseConfig` SWR（缓存先行 + 静默网络刷新 + 内容不变抑制事件，CFGCACHE1）。**缺口**：长驻进程无重拉触发，`LiveParser.start` 在内存有分组时直接跳过（LiveParser.java:59-64），内容年龄无限增长。
- **关键核实（决定设计细节）**：`BaseConfig.parseAndCache`（BaseConfig.java:195-206）在缓存命中路径（:120）与内容未变的网络路径（:197-199）**都会重写缓存文件** → 缓存文件 mtime 不能当「内容年龄」。必须用独立时间戳：在真正网络成功的路径 `BaseConfig.load(Config)`（:171-173，`fetchJson` 成功后）写入 `Prefers` 键（如 `live_fetch_ts_<md5(url)>`），LIVE3 超龄检查读该键。
- 推荐源清单形态：assets 内置 `preset_live.json`（标准 `lives` JSON 或 Depot `{"urls":[...]}` 格式），设置页 `ConfigDialog`（leanback `ConfigDialog.java:188-224` / mobile 同名类）加「推荐源」入口一键导入；清单远端化走版本号比对 + ConfigCache 模式落盘，避免 APK 内清单过期。
- 主流上游更新周期 12h（Guovin/iptv-api 默认），超龄阈值默认 12h，可设。

## 设计要点

- 超龄检查入口：`LiveSession.start()`（LiveSession.java:120）进入直播页时；超龄则 `App.post` 静默 `LiveConfig.get().load()`。不在 LiveSession 常驻定时器（用户决策：TV 常驻不跑定时器；维持 CFGCACHE1 否掉 WorkManager 的决策）。
- 重拉后收藏/开机源/隐藏组保留由现有 `finishLive → Live.sync`（LiveConfig.java:208-214）覆盖，无需新逻辑。
- `$` 后缀剥离落点：`LiveParser.m3u` URL 行（:127-133）与 `LiveParser.txt` 多线路 split（:147-158），取 `$` 前段为 URL；注意 `$` 在个别源里可能是合法字符的误伤风险，实施时仅在 URL 以 `$` 分隔出非空白后缀且后缀不含 `://` 时剥离。
- 推荐清单内容（2026-10 基准，实施时复核可用性）：fanmingming `live.fanmingming.com/tv/m3u/global.m3u`（+IPv6 备选）、iptv-org `iptv-org.github.io/iptv/index.m3u`（中国源子集）、EPG 推荐 `epg.51zmt.top:8000/e.xml`。

## 关联

- LIVE4（多源聚合）依赖本任务的超龄刷新触发与推荐源导入；LIVE5 依赖 LIVE4。
- LIVE2（EPG 流式解析）独立，不受影响。
