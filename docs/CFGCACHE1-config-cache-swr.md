# CFGCACHE1 站源配置磁盘缓存与缓存先行刷新

## Recovery anchor

- 目标：站源配置（Vod/Live）拉取失败时回退本地缓存；启动/加载时缓存先行渲染，后台静默网络刷新，内容未变则抑制重复事件。
- 验收：见「验收标准」；阶段 3（多源整合）/阶段 4（站点健康验证）明确不做。
- 状态：代码完成，静态一致性核查通过；**编译与真机验证由用户统一执行**。
- 回滚锚点：recovery tag `recovery/CFGCACHE1/<ts>`（见 git log）；`git revert` 单提交即可整体回退。
- 下一动作：等待用户统一验证反馈。

## 背景与证据

- 原行为：配置 JSON 每次启动全量网络拉取（`Decoder.getJson`），OkHttp 无 HTTP Cache，无任何本地持久化；失败即空首页；reload 路径 `clear()` 后失败会把在用配置清空。
- 本地先例：spider jar 已按 `md5` 落盘缓存（`JarLoader.parseJar`，`Path.jar()`），本任务复用同一模式。
- 取舍记录：OkHttp HTTP Cache 被否（依赖服务器 validator，过期后失败仍空首页）；WorkManager 定时刷新被否（TV 常驻场景收益低）；对称多源合并推迟到阶段 3。
- 用户决策（2026-09-29）：离线仅需局域网/本地可用；json 可能失效但站源不一定失效 → 缓存不设 TTL，任何旧缓存好过空首页；认可缓存策略；阶段 3/4 暂缓。

## 设计

- `ConfigCache`（新）：`filesDir/config/<md5(url)>.json`，临时文件+rename 原子写；filesDir 而非 cacheDir，避免系统清缓存后断网无回退。
- `BaseConfig`：
  - 抽象拆分：`fetchJson(Config)`（网络拉取，含 Decoder 解码链）+ `parse(Config, String)`（子类解析）；`load(Config)` 变为具体方法：网络 → 解析 → 落盘；网络或解析失败 → 读缓存重解析（Toast 提示一次）；缓存内容与内存状态一致时静默成功。
  - `load(Callback)` 分发：后台线程判定缓存 → `loadCachedConfig`（缓存先渲染 → `App.post` 静默网络刷新）或 `loadConfig`（原网络路径）。
  - 变化检测：`loadedJson` 记录当前状态对应原文；静默刷新取回相同内容时 `suppressEvent=true`，抑制 `postEvent` 与公告 Toast，避免首页二次重载、焦点跳动。
  - 缓存损坏：删除缓存文件后直接走网络路径，等同无缓存。
  - Depot 递归：`parseDepot → load(sub)` 落到具体 `load(Config)`，子配置同样获得缓存回退；已知局限——Depot 模式下变化检测以顶层 depot JSON 为键，子配置漂移在 depot JSON 变化前不会被静默刷新发现。
- `VodConfig`/`LiveConfig`：`load(Config)` 拆为 `parse` 覆盖（Live 保留空配置短路）；`clear()` 增加 `invalidateJson()`。
- `WallConfig`：保留自有 `load(Config)` 覆盖，行为不变，不参与缓存。
- 不覆盖 `RemoteConfigSiteParser`（独立路径，用户已确认可不含）。
- 回退提示文案暂为硬编码「网络异常，已加载缓存配置」（主线程经 `App.post` 弹出）；后续如需多语言可迁移到 strings 资源。

## 实施记录

- 2026-09-29：`ConfigCache.java` 新建；`BaseConfig.java` 重构 load 链路；`VodConfig.java`/`LiveConfig.java` 拆分 parse 并接入；任务守卫 CFGCACHE1（standard），HEAD 基线 52150b3ae95cfdd96d9e2bb6a95ac25dd2a4d7f4。
- 静态核查：子类覆盖关系（Vod/Live=parse，Wall=load）、Depot 递归落点、`Util.md5(String)`/`Path.read(File)` 签名、无 Decoder/UrlUtil 残留引用、`Notify.show` 需主线程（已包 `App.post`）。

## 验收标准（待用户统一验证）

1. 有缓存冷启动：首页立即由缓存渲染；随后静默刷新；内容未变 → 无二次刷新；有变化 → 正常刷新。
2. 无缓存冷启动：与现状一致。
3. 飞行模式 + 有缓存：直接可用（含切换到已缓存过的其他配置）。
4. 网络失败 + 有缓存：回退缓存并 Toast 提示一次；无缓存：现状错误路径。
5. 缓存文件损坏：自动删除并走网络，不崩溃。
6. 直播配置（开机广播/进直播页）同样获得缓存回退与缓存先行。
7. 壁纸配置行为不变。

## 风险与回滚

- 风险：并发加载取消语义沿用既有 taskId/future.cancel，未改变；缓存读写均在 Task 线程；写入失败静默降级为无缓存。
- 回滚：revert 本任务单提交；缓存目录 `filesDir/config/` 残留文件无害，可手动清理。
