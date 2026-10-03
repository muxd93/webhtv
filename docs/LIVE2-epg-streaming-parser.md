# LIVE2 — EPG XMLTV 流式解析（交接文档，未实施）

> 状态：**已登记待实施**。启动前置条件：真实痛点证据（具体机型 + 具体 EPG 源出现解析慢/OOM/卡顿），或产品决定支持超大 EPG（≥50MB）。接手人按本手册可直接开工。

## 1. 背景与根因（已核实，2026-10-03）

- `api/parser/EpgParser.parseXmlData` 用 simpleframework `Persister.read(Tv.class, file)`（`bean/Tv.java`）一次性把整个 XMLTV 构建成对象树。内存峰值 ≈ 全树 + 结果映射，几十 MB 的 7 天 × 数百频道 EPG 在低端盒子上可触发 OOM/长 GC。
- 相关事实：
  - 磁盘缓存策略已合理（`EpgParser.start`：当天且 <6h 复用；gzip 先落盘解压），**本任务不需要动**。
  - mobile `LiveProgramDialog` 依赖 `channel.getDataList()` 的多天数据按天切换节目单 → "只保留 ±1 天"的方案**不可行**（会砍功能）。
  - `EpgParser.processProgramme` 已有 channelCache/channelMiss 优化，瓶颈在 Persister 全量 DOM，不在匹配循环。
  - LIVE3 已为 `Channel` EPG 访问加 synchronized（后台写/主线程读契约），本任务必须保持。

## 2. 目标与非目标

- 目标：XMLTV 解析内存峰值从"全树"降为"匹配频道的节目单 + 流式扫描常量"；解析耗时同步下降（不构建无用对象）。
- 非目标：不改磁盘缓存策略、不改 `Channel/Epg` 数据模型、不改模板型 EPG（`{date}` URL）路径——模板路径可选优化见 §6。

## 3. 方案对比（基于实际使用）

| 方案 | 内存峰值 | 改动面 | 风险 | 结论 |
|---|---|---|---|---|
| A. Persister 换 `XmlPullParser` 两遍流式 | 常量级 + 匹配结果 | 仅 `EpgParser` + 新增流式 reader 类 | 中（需复刻 simpleframework 的容错语义：strict=false、缺属性、多语言 title） | **推荐** |
| B. Persister + 自定义 XmlMapper 分批回调 | 同 A，但被库能力限制 | 依赖 simpleframework 内部 API，升级脆弱 | 高 | 否 |
| C. SAX + Handler | 同 A | 回调式代码可读性差 | 中 | 备选（若 pull 解析器有兼容问题） |
| D. 只解析 ±N 天（裁剪数据） | 低 | 改数据模型 + 砍 mobile 节目单功能 | 功能回归 | **否决** |

## 4. 推荐方案实施步骤（方案 A）

1. 新增 `api/parser/EpgStreamReader.java`（或并入 EpgParser）：`XmlPullParser`（`Xml.newPullParser()`，INPUT_ENCODING UTF-8）。
2. **第一遍**：只读 `<channel>` 元素，建 `Map<String channelId, Channel target>`，复用 `EpgParser.findTargetChannel` 的三级匹配（xmlChannelId → liveChannelMap[tvgId/tvgName/name] → display-name 回退）。注意 XMLTV 的 channel 可能出现在 programme 之后，需兼容（若首遍未见 channel 表，退化为按 programme.channel 直接匹配）。
3. **第二遍**：流式读 `<programme>`，`findTargetChannel` 命中才构建 `EpgData`（复用 `parseFull/getEpgData` 的时间解析与时区逻辑），未命中直接 `parser.next()` 跳过、不构建对象。
4. 输出结构与现 `ProgrammeResult` 完全一致（`Map<tvgId, Map<date, Epg>>` + srcMap），`bindResultsToLive` 不变。
5. `EpgParser.parseXmlData/processProgramme` 替换为新 reader 调用；删除 `Tv.java` 依赖（保留类直到确认无引用）。
6. 单测：新增 `EpgStreamReaderTest`——用字符串样本覆盖：标准 xmltv、gzip 由既有路径处理不测、programme 先于 channel、缺 start/stop、多 display-name、tvgName 回退、UTC 偏移格式（`+0800` 与带冒号）。对照断言：新旧解析对同一样本产出相同 `Epg` 结构（可临时保留旧路径做双跑断言后再删）。

## 5. 验证方案

- `bash ./gradlew :app:testLeanbackArm64_v8aReleaseUnitTest --tests "*EpgStreamReader*"`。
- 真实样本回归：取 1 个大 EPG（≥20MB）+ 1 个普通 EPG，对比改造前后 `bindResultsToLive` 的 with-epg/without-epg 计数日志（`EpgParser` 已有该日志）。
- 手工：直播页节目单（mobile 节目单弹窗按天切换）、leanback EPG 面板、catchup 换节目。

## 6. 可选附带（同次或后续）

- 模板型 EPG `{date}` URL 每频道 3 天 × 串行同步请求且仅内存缓存（`LiveApi.getEpg`/`fetchEpgDay`）。可选优化：`ConfigCache` 式磁盘缓存（键含 URL+date，当日有效）。独立可交付，不阻塞主任务。

## 7. 非目标记录（本轮评审结论，不必再议）

- 探测 UA 与播放 UA 不一致（LiveProbe 无默认 UA）：有"2 轮全死才隔离 + 复活"缓冲，优先级低。修法：`LiveProbe.probe` 的 Request 附加播放侧默认 UA。
- `LiveProbe.collect` 去重取首个频道的 header：同 URL 多频道 header 不同时探测 header 可能与播放不一致。修法：Entry 以 url+headers 组合去重。
- `LiveConfig.parseDepot` 多仓首次导入同步 `aggregate(true)` 阻塞首屏（有 Toast 进度）。修法：先加载缓存聚合文件，后台聚合后 `reloadQuietly`。
- `server/process/Manage.java:370` 等远端路径使用带事件 `LiveConfig.load()`：属显式管理动作，保留。
