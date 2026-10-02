# LIVE4 — 直播多仓汇总聚合 + 本地合并数据 + 分组排序

## Recovery anchor

- **状态**：代码完成（2026-10-02）。归一化单测通过；两 flavor javac 编译通过。真机验证待用户。
- **目标**：导入多仓（`{"urls":[...]}` Depot）时全量展开、按频道归一合并生成一份完整数据落盘本地并注册为可切换的直播配置；归一化固定顺序分组排序。
- **验收标准**：
  1. 导入含 N(>1) 个 URL 的多仓 → 全部保存为 Config(type=1) 并聚合（改掉现状只取第一个的行为）；单 URL 多仓保持原行为。
  2. 不同源归一同名频道合并为同一 Channel 多线路；完全相同 URL 去重；来源标签进旁车 meta。
  3. 合并数据落盘 `filesDir/live/aggregate.json`（临时文件+rename 原子写），注册为 Config(type=1)「聚合」（file:// 绝对路径），现有 JSON lives 加载路径零改动解析。
  4. 分组顺序：央视 → 卫视 → 地方 → 专题 → 其他，隐藏组（pass）最后；组内按（数字台号, 名称）排序。
  5. 重聚合内容无变 → 不写文件不发事件；有变 → 静默 SWR 刷新。
  6. 收藏不受分组改名影响（Keep 键=频道显示名，已核实 `Keep.setKey(item.getName())`）。
- **回滚**：单笔原子提交，`git revert`。
- **唯一下一步**：用户真机统一验证（多仓导入聚合、切换/删除源、重聚合不跳台）；如需启停/排序管理界面另立任务。

## 实施记录（2026-10-02）

- **新增 `live/LiveAggregator`**（核心类，见下文「实施设计确认」全部落实）：
  - 归一化纯函数 `normalizeChannel/normalizeGroup/order`（无 Android 依赖，可单测）。
  - `savePool`（池定义变化才重置状态）/`pool()`（自动剔除已删历史源）/`aggregate()`（同步聚合：逐源超龄才走网络、成功回写 ConfigCache+Prefers ts、失败回退缓存标记 ok=false 跳过；合并落盘返回内容是否变化）。
  - `merge`：组归一映射（先建占位组再 `setName`，避开 `Group` 构造器对 `_` 的密码拆分）、频道按归一键合并（显示名/`tvgId/tvgName/logo` 首见优先、urls 精确去重、`epg` 并集）；组序央视→卫视→地方→专题→其他、隐藏组置尾；组内（数字台号，名称）排序；`renumber` 显式台号先到先得、重复与缺失补连续编号。
  - `toJson`：序列化前置空 `Channel.group` 反向引用防 Gson 递归；产物为标准 `{"lives":[…]}`。
  - `refreshIfStale`（`LiveSession.start` 挂点）/`isStale`（任一源超 12h 或有池无文件）。
- **`LiveConfig.parseDepot` 改造**：多 URL 仓 → `savePool` +（池变或超龄才）`aggregate()` + `ensureConfig` 并加载；单 URL 仓保持原行为；depot 配置仍删除。
- **`LiveSession.start(!empty)`** 增加 `LiveAggregator.refreshIfStale()`。
- **单测 `LiveAggregatorNormalizeTest`**：分隔符/全半角/大小写/`+` 保留/繁简/空安全/组族归并/固定顺序共 8 用例。

## 验证记录（2026-10-02）

- `:app:testLeanbackArm64_v8aDebugUnitTest --tests LiveAggregatorNormalizeTest`：**通过**（修正一处：`Trans.t2s(String)` 受 locale 门控、非 TW 环境原样返回，归一键改用强制转换重载 `Trans.t2s(false, …)`）。
- `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` + `:app:compileMobileArm64_v8aDebugJavaWithJavac`：**BUILD SUCCESSFUL**（含 `-x :chaquo:installArm64_v8aDebugPythonRequirements`，原因同 LIVE3）。
- 无关预存失败（未触碰、不在本任务范围）：`Avs3ExtractionTest`（缺 `avs3.fixtures` 系统属性致 NPE）、`ExoCompressedAudioDirectPolicyTest`（环境依赖）。
- 未验证项：真机端到端（导入多仓→聚合→选台/收藏/换线、删除源后重聚合、超龄后台重聚合不跳台）。

## 风险

- 归一化误合并：规则保守（`+` 与别名后缀均不合并）+ meta.lines 可溯源。
- 同频道不同显示名导致 Keep 失配：低频，可重新收藏。
- 源多时导入聚合耗时：每源 20s 超时、失败跳过。

## 关联

- LIVE3 提供刷新时间戳约定与推荐源导入；LIVE5 的探测/降级建立在 meta.lines 与本任务合并器之上。

## 实施设计确认（2026-10-02，基于逐项代码核实）

- **源池**：`filesDir/live/aggregate.meta.json`（非 Config.json 列，避免侵入 Config 语义）。`sources:[{url,name,order,ok,ts}]` + `lines:{播放url→源名}`（LIVE5 探测/溯源用）。
- **导入流程**：`LiveConfig.parseDepot` 多 URL 时：逐项 `Config.find(depot,LIVE)` 落库（已核实 create 带 insert）→ meta 覆盖为本批 urls → **同步**聚合（已在 Task 线程）→ 写聚合文件 → `Config.find/create` 聚合配置 → `load(聚合)`；depot 配置仍按现状删除。
- **聚合文件加载**：url=`file://<filesDir 绝对路径>`。链路已核实：`UrlUtil.convert` file://→本机 Server `/file/`→`Path.local` 绝对路径回退可读 filesDir；内容为 `{"lives":[{name,groups:[{name,channel:[...]}]}]}`（Group `@SerializedName("channel")`、Live `groups` 可往返，已核实）。
- **序列化安全**：`Channel.group` 反向引用会致 Gson 无限递归 → 序列化前 `setGroup(null)`（合并产物丢弃式，内存态由文件重载重建）；Gson 默认省略 null。
- **每源解析**：`LiveParser.text(new Live(name,url), content)`；网络成功→`ConfigCache.put`+`Prefers(live_fetch_ts_<md5>)`（复用 LIVE3 键约定）；失败→ConfigCache 回退→再失败标记 failed 跳过该源。`OkHttp.string(url, headers, timeout)`。
- **归一化（纯函数单测）**：trim→去全部空白→全角转半角→大写→去分隔符集（`-—–_·•.（）()[]【】「」：:，,｜|` 等，**保留 `+`**）→`Trans.t2s`。保守不含别名/后缀剥离（`CCTV1综合`≠`CCTV1`，v1 不合并，防误杀）。
- **组归一**：含 `央视/CCTV/CGTN`→「央视」；含 `卫视`→「卫视」；其余去空白原样。组序：央视→卫视→地方→专题（体育/电影/纪录/少儿/动漫/教育/新闻/音乐/财经/科技/文艺/都市/法治/港澳台/国际/海外/戏曲/旅游等模式）→其他；pass 组最后。组内频道按（数字台号, 名称），**不重编台号**。
- **频道合并**：key=归一名；显示名/`tvgId/tvgName/logo/ua/catchup` 等首见优先；`urls` 追加+精确去重；`epg` 并集逗号连接；合并后重绑 `channel.live(聚合Live)` 并补台号。
- **重聚合触发**：`LiveSession.start(!empty)` 调 `LiveAggregator.refreshIfStale()`：当前配置是聚合 且（meta/文件缺失 或 任一源 ts 超 12h）→ `Task.submit` 后台重聚合 → 文件内容有变 → `App.post(load())`。
- **删除联动**：重聚合时剔除 meta 中已不在 Config(type=1) 的源（HistoryDialog 删源自然生效）。
- **UI**：本阶段无新增界面；聚合配置作为普通历史项出现在 HistoryDialog/LiveDialog。启停/排序管理界面延后（记录为后续任务）。

## 风险

- 归一化误合并：规则保守 + 来源标签可追溯（meta.lines）。
- 同频道不同显示名导致 Keep 失配（首见显示名与他源不同）：低频，可重新收藏。
- 源多时导入聚合耗时：每源 20s 超时上限、失败跳过；depot 导入本就是显式等待操作。
- mobile/leanback 编译均需通过（LIVE1FIX 已修复 mobile 预存错误）。

## 关联

- LIVE3 提供刷新时间戳约定与推荐源导入；LIVE5 的探测/降级建立在 meta 与本任务合并器之上。
