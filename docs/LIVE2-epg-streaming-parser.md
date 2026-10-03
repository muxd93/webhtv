# LIVE2 — EPG XMLTV 流式解析（登记，未实施）

**状态**：登记待定（用户指示"先登记"）。在获得真实痛点证据（具体机型 + 具体 EPG 源出现解析慢/内存吃紧）之前不启动。

## 背景（2026-09-29 梳理结论）

- `EpgParser.parseXmlData` 使用 simpleframework `Persister.read(Tv.class, file)` 一次性构建整棵 XMLTV 对象树（`bean/Tv.java`）。大 EPG（7 天 × 数百频道，数十 MB）内存峰值 ≈ 全树 + 结果映射。
- 磁盘缓存策略（当天且 <6h 复用、gzip 落盘解压）已合理，无需改动。
- **约束**：mobile `LiveProgramDialog` 依赖 `channel.getDataList()` 的多天数据按天切换节目单，"只保留 ±1 天"会砍功能，不可行。

## 候选方案（启动时再决策）

- 两遍 `XmlPullParser` 流式：第一遍建 channel 表，第二遍流式过滤匹配频道后即弃；匹配规则与 `EpgParser.findTargetChannel` 现有三级匹配（tvgId/tvgName/name + display-name 回退）保持不变。
- 模板型 EPG（`{date}` URL）串行 3 请求无磁盘缓存的问题可一并评估。

## 关联

- LIVE1 已为 `Channel` EPG 访问加 synchronized；本任务若实施需保持该并发契约。
