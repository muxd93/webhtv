# LIVE5 — 直播线路有效性探测 + 先降级后剔除

## Recovery anchor

- **状态**：代码完成（2026-10-02）。策略单测通过；两 flavor javac 编译通过。真机验证待用户。
- **目标**：对聚合后线路做轻量可用性探测；失败线路沉底，连续 2 轮全线路失效的频道隔离隐藏（可自动复活）；线路按延迟排序。
- **验收标准**：
  1. 直播控制条「检测」入口 → 手动全量探测；并发 6、可取消（再点一次）、Toast 进度；结果批量写 meta 并跨轮复用。
  2. 聚合写盘后自动增量探测 probe 状态缺失的线路（不阻塞配置加载）。
  3. 单轮失败 → 线路沉底；连续 2 轮全线路失效（且全部线路可探测）→ 频道移出聚合文件进 meta 隔离区（UI 隐藏）；任一线路探测成功 → 自动复活回插。
  4. 线路在频道内按「可用(延迟升序) → 未探测(原序) → 失效(原序)」稳定排序；播放层自动换线行为不变。
  5. `rtp://` 等非 http 协议跳过探测、视为未探测，绝不误杀（含隔离判定）。
- **回滚**：单笔原子提交，`git revert`（meta/aggregate 文件为运行时产物，残留无害）。
- **唯一下一步**：用户真机统一验证（聚合源上点「检测」→ 观察进度/完成统计、死线沉底、全死频道消失、恢复源后复活）。

## 实施记录（2026-10-02）

- **新增 `live/LiveProbe`**：`toggle()`（运行中再点即取消）→ `startFull()`/`startMissing()`；采集基于聚合文件（不依赖内存树，聚合中途也能增量），按 url 去重、非 http 跳过、隔离区线路始终参与复活探测；`OkHttp.client(timeout)`（跟随重定向）+ 自建 Request（tag/随线 headers，非法头名忽略），读到响应头判活（2xx）、不读流实体；单线 5s 超时、并发 6 自建线程池、`OkHttp.cancel(TAG)` 取消；结果轮末批量交 `LiveAggregator.applyProbe`；进度/统计经 `App.post(Notify)`。
- **`LiveAggregator` 增强**：
  - 纯策略函数 `orderUrls`（可用按延迟→未探测原序→失效原序，稳定排序）、`score`、`allDead`（全部线路可探测且全部失效且 streak≥STREAK_LIMIT=2 才隔离）。
  - `merge(parsed, probe, quarantine)`：隔离区频道（组归一名+频道归一名）不参与合并；组内频道线路落盘前按探测状态重排。
  - `applyProbe`：轮次更新 probe（成功 streak=0+latency，失败 streak+1）、清理已消失 url 的探测项 → `rewrite`（复活回插→线路重排→全死频道移入 meta 隔离区，组空则删组）→ 文件有变才写盘（返回变化，由引擎触发 `LiveConfig.load()` 静默重载）。
  - `aggregate()` 写盘后 `Task.submit(LiveProbe::startMissing)` 自动增量。
  - meta 扩展 `probe:{url:{ok,latency,ts,streak}}` 与 `quarantine:[{group,pass,key,channel}]`；`lines` 保持 LIVE4 形态。
- **UI**：mobile 直播设置菜单（`LiveControlDialog`，「直播」操作行）提供「检测」→ `LiveProbe.toggle()`；leanback（TV）无独立设置菜单，入口保留在播放控制条（`view_control_live_action.xml`）。反馈全用 Notify（开始 N 条/每 25%/完成统计/取消/非聚合源不支持）。strings 三语言新增 `live_detect` + 6 条 `live_probe_*`。
- **单测 `LiveProbePolicyTest`**（5 用例）：延迟排序桶序、未探测/失效原序稳定、非 http 视为未探测、隔离判定四条护栏（streak 不足/有活线/混入 rtp/未探测线路均不隔离）、空频道不隔离。

## 验证记录（2026-10-02）

- `:app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.live.*`（含 LiveAggregatorNormalizeTest + LiveProbePolicyTest）：**通过**。
- `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` + `:app:compileMobileArm64_v8aDebugJavaWithJavac`：**BUILD SUCCESSFUL**（同一 gradle 调用，`-x :chaquo:installArm64_v8aDebugPythonRequirements`，原因同前）。
- 未验证项：真机端到端（检测进度/取消、死线沉底、全死频道隐藏与复活、换线命中率提升）。

## 风险

- 探测通过 ≠ 保证可看（鉴权/地域）：判活为 2xx + 随线请求头，语义是连通性；失效线路只是沉底不消失，用户仍可手动选中。
- 全量首轮耗时（500 线路 × 并发 6 × ≤5s ≈ 3–5 分钟）：仅手动触发，再次点击可取消。
- 隔离误杀余量：阈值 2 轮 + 隔离区每轮自动复测 + 复活自动回插。

## 关联

- LIVE4 的 meta/合并器/落盘是本任务底座；LIVE3 的 ts 键约定沿用。
