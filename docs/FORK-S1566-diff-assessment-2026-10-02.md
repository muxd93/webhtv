# Silent1566 ↔ muxd93 分叉差异梳理（2026-10-02）

> 状态：梳理完成，未动任何代码。结论供决策，采纳任何一组前需按 AGENTS.md §8 流程单独评估与确认。

## 1. 三仓拓扑与规模

| 仓库 | 基线 | 独有提交 | 版本 | 最近活动 |
|---|---|---|---|---|
| fish2018/webhtv（原版） | — | — | — | 持续 |
| muxd93/webhtv（本仓库 main） | 原版 `d187f6ae8b`（2026-09-24） | 6 个提交 | 1.1.2 (112) | 2026-10-02 |
| Silent1566/webhtv | 原版 `8e4d9333de`（2026-09-21） | 2567 个提交 | 5.6.0 (560) | 2026-09-22 |

- Silent1566 是从 2026-05-22 起持续四个月的深度分叉（386 feat / 674 fix / 175 份任务文档），并周期性 merge 原版。
- Silent1566 缺原版 1 个提交：`d187f6ae8b` test(exo)（muxd93 已含）。
- 三仓共用同一套开发流程（AGENTS.md、任务文档、`E*`/`P*`/`C*` 编号体系、`docs/upstream-player-dependency-merge-assessment-2026-08-20.md` 评估索引）。

## 2. 已确认同源、无需移植的部分

- **播放器 native 链完全同版本**：ffmpeg `177f090e05…`（release-9.0-fongmi）、mpv `cca559b41c…`、libplacebo `b694a21bf2…`、mpv-android builder `99a60ad21…`，两边锁完全一致。
- **media3 版本一致**（`1.11.0-alpha01-fongmi` / media 1.8.0），`app/libs` 下 5 个 AAR 逐字节一致，`third_party/` 目录树一致。
- **约 16 份同名任务文档对应的功能已同源**：E4-LIBASS、E9-3、E11、C-AVS3、P2-4、P9、P10、P11、MPV-REBUFFER-PANEL、MPV-SCRIPT-TRIGGERS、leanback-pan-switch-layout、AV-DIAG-01、HISTORY-COVER-PROGRESS、bluray-menu-assessment、FIX-GITCLOUD-KEYSTORE、上游合并评估索引。
- 抽查证实缺失项为真：`ExoBufferingStallWatchdog` 等符号 muxd93=0。
- **muxd93 独有（反向差异，无需行动）**：SUB1–3 订阅源重构、LIVE3–5 直播自动更新/多仓聚合/probe 降级、SMB/本地播放与收藏行、配置缓存离线回退、LiveSession 调台状态机、老人模式（已移除）。

## 3. Silent1566 独有功能分组评估

| 组 | 用户可感知能力 | 规模（符号文件数） | 依赖/风险 | 建议 |
|---|---|---|---|---|
| A 播放健壮性补丁集（E-SP1~9 + exo/mpv fix 群） | 起播首帧更快、缓冲停滞看门狗自恢复、seek 预加载隔离、起播阶段可见、ffmpeg 兜底卸载、解码标签反映实际、AVC 自适应选轨、不支持格式软兜底 | 每项 4~10 文件，文档含验收标准 | 纯 Java 层补丁，但需逐条 patch-id 对照防与原版重复 | ★★★ 优先评估 |
| B 追更 Following | 站点/剧集更新追更与提醒，FOLLOW-1 设计文档 | 64 | 大 UI 功能，独立性强 | ★★☆ 按产品需求 |
| C 主题系统 WebTheme + 主题色体系 + 通用 WebHome 主题 | 界面主题/配色定制、移动端站点主题 | 55 | 依赖 settings/主题重构链 | ★★☆ 按需 |
| D 广告治理套件（AdAudio 43 + AiAdDetect 8 + 规则学习 + 音频指纹 SDK 评估 + HLS 规则源） | 片头广告音频识别跳过、AI 广告检测 | 51 | 实验性强、体积大、误判风险 | ★☆☆ 谨慎/暂缓 |
| E TMDB 深度集成（TmdbService 19 + 并行预取 + 手动匹配季 + 相关推荐 + 可播性探测 + C16 契约） | 刮削、匹配、相关内容 | 19+ | 依赖 C16 契约，改详情页数据流 | ★★☆ |
| F 短剧 ShortDrama + E-SP8 播放队列 | 短剧内容形态与连播 | 20 | 内容生态依赖 | ★★☆ 按内容需求 |
| G 多线程本地代理 MultiThreadProxy | 本地代理多线程吞吐 | 24 | 与本地代理链路耦合 | ★★☆ 基础设施 |
| H 接口故障转移 Failover | 接口失败自动切换备用 | 8 | 小而独立 | ★★★ 基础设施优先 |
| I 统一媒体身份跨站续播（MediaIdentity 6 + 全局历史续播 + 历史删除同步 + 更新历史） | 跨站/换源续播一致、历史可同步清理 | ~10 | 触及历史/详情数据模型 | ★★☆ |
| J 崩溃静默重启 CrashRestart | TV 端崩溃无人工干预自恢复 | 5 | 小而独立 | ★★★ 基础设施优先 |
| K 播放体验零散件：OSD 统一标题栏/控制、画面比例模式、字幕源协议+外挂字幕恢复、详情直连模式、重复起播修复 | 播放 UI 与细节 | 各 1~5 | 零散，逐条评估 | ★★☆ 按条 |
| L TV/触屏适配：触屏优化模式（muxd93 已有 TouchMode 部分）、TV 焦点搜索导航、tv-short-display-dead 修复 | 遥控器/触屏体验 | 各 1~5 | 零散 | ★☆☆ |
| M 格式兼容：ISO multi-extent（C3/E7-2）、DV7 P81 BSF（C2） | 蓝光原盘/杜比视界兼容 | 每项 2~6 | native/解析层 | ★★☆ 按设备需求 |
| N AI 周边：字幕翻译、观影报告、推荐元数据、日志诊断、真实标题提取、弹幕手动匹配记忆 | AI 辅助功能 | 各 1~5 | 依赖 AI 服务配置 | ★☆☆ |
| O 基础设施其他：OCI APK 更新通道（OCI1）、搜索分组精确过滤、站点分组手动排序、个人推荐缓存、APK 链接推送、TV adblock/CSP 快捷方式 | 分发与检索体验 | 各 1~5 | 零散 | ★☆☆ 按需 |

## 4. 重点推荐与最小实施路径

1. **A 播放健壮性系列（E-SP）**——与播放体验直接相关且纯 Java 层。步骤：先对 E-SP1~9 逐条做 patch-id/符号对照，剔除原版已含项；再按 E-SP3 看门狗 → E-SP1 首帧 → E-SP5 兜底卸载顺序逐条移植（上游自带单测如 `ExoBufferingStallWatchdogTest` 可直接复用）；验证：单测 + 直播/点播起播、拖动、断流场景回归。注意：E-SP2 等 media3 补丁类需先确认自定义 AAR 实际构建是否一致。
2. **H 接口故障转移**——步骤：读 `docs/interface-failover-design.md` + `-v1` → 移植 Failover 核心 8 文件 → 接入接口选择链路；验证：单测 + 断网/坏接口切换场景。
3. **J 崩溃静默重启**——步骤：移植 5 文件 + 接入 Application 崩溃处理；验证：人为崩溃恢复场景 + 崩溃循环保护（避免死循环重启）。
4. **I 跨站续播 / E TMDB 深度集成 / B 追更**——产品价值高但触面大，按需逐个立项。

## 5. 方法与风险

- Silent1566 改动量：main java 22.7%、测试 16.6%、leanback/mobile UI 各 ~5%。**不建议全量合并**（等于把 muxd93 变成 Silent1566 下游）；建议按上表功能簇 + 其任务文档做"按规格重写/定向 cherry-pick"。
- 功能簇之间有依赖链（WebTheme↔主题色体系↔settings 分类重构；TMDB↔C16 契约），移植前需读对应 design 文档确认依赖闭包。
- Silent1566 main 停在 2026-09-22；其 E-SP/E/P/C 系列的**设计文档和验收标准本身就是最好的实施规格**，"借鉴参考"零成本。
- 若长期吸收，建议像 Silent1566 一样建立"定期 merge 原版 + beta-sync review"节奏，避免三方漂移。

## 6. 下一步

- 移植方案已产出：`docs/FORK-S1566-porting-plan-2026-10-02.md`（含 X* 任务编号映射、阶段划分、验收与回滚）。用户已裁定范围：不做主题/TMDB/追更/AI/短剧/ISO·DV 格式兼容；广告仅保留规则部分。各阶段开工前需用户逐阶段批准。
