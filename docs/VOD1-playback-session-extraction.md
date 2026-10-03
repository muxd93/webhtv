# VOD1 — VideoActivity 域状态机抽取（交接文档，未实施）

> 状态：**已登记待实施的独立任务**。样板已就绪（直播侧 LIVE1/LIVE3 已落地同一模式），接手人按本手册执行。

## 1. 背景与根因（已核实）

- `VideoActivity` leanback 6462 行 / mobile 6632 行，与直播重构前同构：选集/换源/播放/错误恢复状态机在两个 flavor 各复制一份，git 历史已出现大量单侧修复漂移。
- 共享基座 `ui/activity/PlaybackActivity.java`（940 行）已承担播放器管线（service 绑定、表面、错误分发、startPlayer）；缺的同样是**域状态机层**。

## 2. 样板（照抄的结构）

- `live/LiveSession.java`：纯 Java 状态机，持有状态 + 编排 ViewModel/LiveConfig，`Listener` 接口只做渲染回调；"身份即真相"（UI 判等一律引用比较，`equals` 只留给解析期合并）。
- `docs/LIVE1-live-session-refactor.md`：抽取过程的方法清单、行为分叉证据、flavor 差异保留原则。
- LIVE3 补充的 `softReload`（后台内容更新静默换树）也应在 VOD 端有对应物（换源/订阅刷新不重启播放页）。

## 3. 实施步骤

1. **只读盘点（先行，单独 commit 或仅记录）**：列出两文件重复方法清单与漂移点，方法同 LIVE1 前置分析（git log 单侧修复证据）。预计清单比直播更大（选集分页、历史续播、投屏、下载、相似推荐等）。
2. **抽取 `VodSession`**（建议 `tv/vod/VodSession.java`）：候选职责——当前 site/分类/选集指针、换源映射、历史续播位置、fetch/reload 防循环、自动下一集、收藏。渲染回调按 LIVE1 的 Listener 风格。
3. **判等收敛**：排查 `Episode/Flag/Result` 等 bean 的 equals 在 UI 路径的使用（同直播 Channel.equals 问题），解析语义保留、UI 改引用。
4. **两 Activity 接入**：平台差异（PIP/手势/投屏/竖屏内嵌）留在 flavor，与直播处理方式一致；行为基线=状态机统一、UI 差异保留。
5. **每步验证**：先编译（`compileLeanbackArm64_v8aDebugJavaWithJavac` + mobile 同名任务）再自测关键路径（选集/换源/续播/自动连播/投屏中断恢复）。

## 4. 规模与拆分

- 预计体量大于 LIVE1（6.5k 行 ×2），拆多个 guard 会话：盘点 → VodSession 核心 → flavor 接入 → 判等收敛。每个会话独立可交付、可回滚。
- 风险点：VideoActivity 与 PlaybackService/History/Keep 的耦合比直播深，`softReload` 对应物（订阅刷新）需要先盘点 `ConfigEvent.vod` 的消费方。

## 5. 验收标准

- 两 flavor 编译通过；重复方法全部归一（清单逐项勾销）；修复一次两侧生效；无 bean 选中态寄生；现有播放行为无回归（重点：续播、自动下一集、换源记忆、投屏）。
