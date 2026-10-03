# Silent1566 → muxd93 移植方案（2026-10-02）

> 依据：`docs/FORK-S1566-diff-assessment-2026-10-02.md` 差异梳理 + 用户范围确认。
> 原则：每个任务独立 guard session、独立提交、独立 recovery tag，可单条回滚；不回退现有行为与性能（AGENTS.md 验收契约）。
> 状态：方案完成，未动代码。每阶段实施前需用户对该阶段明确批准。

## 1. 范围（用户已确认）

**纳入**：播放健壮性（E-SP 系列）、接口故障转移、崩溃静默重启、跨站续播与历史链、播放 UX 零散件、TV 小修复、多线程本地代理（评估后）、广告规则部分（仅规则学习 + HLS 规则源）、检索/分发小件。

**不纳入**（理由备查）：
| 项 | 理由 |
|---|---|
| 主题体系 WebTheme / 主题色 | 用户裁定个人意义不大 |
| TMDB 深度集成 | 同上 |
| 追更 Following | 同上 |
| AI 套件（字幕翻译/观影报告/推荐/日志诊断/标题提取/弹幕匹配记忆） | 同上 |
| AdAudio 音频指纹 + AI 广告检测 | 同上（仅保留规则部分） |
| 短剧 ShortDrama + E-SP8 队列 | 用户裁定不需要 |
| ISO 多 extent / DV7 P81 格式兼容（C2/C3/E7 系列） | 用户裁定不需要 |
| OCI APK 更新通道 | 现有 push→Release 流程已覆盖 |
| 个人推荐缓存 | 依赖推荐生态，个人价值低 |
| 触屏适配大改 | TouchMode 基线已有，增量小 |

## 2. 任务编号治理

- E-SP 条目**沿用共享评估索引既有 ID**（E-SP1~9，不重编号），实施记录写入本仓库 `docs/E-SP*-*.md`。
- 其余移植项启用新任务族 **`X*`**（cross-fork 移植），在本节登记映射，后续不得重号：

| ID | 内容 | 源参考（Silent1566 docs） |
|---|---|---|
| X1 | 崩溃静默重启 | CRASH-SILENT-RESTART-*.md |
| X2 | 接口故障转移 | interface-failover-design.md / interface-failover-v1.md |
| X3 | 重复起播修复 + tv-short-display-dead 修复 | fix-duplicate-playback-start-20260918.md / tv-short-display-dead-20260914.md |
| X4 | 统一媒体身份跨站续播 | unified-media-identity-cross-site-resume.md |
| X5 | 全局历史续播 | global-history-resume-design.md |
| X6 | 播放历史删除同步 | playback-history-delete-sync-design.md |
| X7 | OSD 统一标题栏/控制显示 | feature_osd_*.md（3 份） |
| X8 | 画面比例模式 | video-aspect-modes.md |
| X9 | 字幕源协议 + 外挂字幕恢复 | subtitle-source-protocol-design.md / SUB-EXT-HISTORY-*.md |
| X10 | 详情直连模式 | fix-detail-direct-play-mode-20260915.md |
| X11 | 多线程本地代理 | multithread-local-proxy-requirement-evaluation.md |
| X12 | 接口广告规则学习 + HLS 规则源 | interface-ad-rule-learning-design.md / hls-rule-sources.md |
| X13 | 搜索分组精确过滤 | search-group-and-precise-filter-design.md |
| X14 | 站点分组手动排序 | site-group-manual-order-assessment.md |
| X15 | APK 链接推送 | mobile-apk-link-push.md |

## 3. 阶段划分与顺序

### 阶段 0：审计与规格（只读 + 文档，无产品代码）
- 逐项 patch-id / 符号对照审计，剔除与原版基线重复的部分；读源设计文档产出移植规格（每任务一份 `docs/<ID>-*.md`：目标、diff 清单、验收标准、回滚锚）。
- 专项确认：本仓库 media3 自定义 AAR 的实际构建来源是否与 Silent1566 一致（决定 E-SP2 类条目可行性）。
- 预估：45–60 分钟。

### 阶段 1：快赢基础设施（小、独立、低风险）
| 任务 | 方式 | 验收草案 | 预估 |
|---|---|---|---|
| X1 崩溃静默重启 | 参考重写（5 文件 + Application 接入） | 崩溃后自动恢复到首页；连续崩溃保护（如短窗口 ≥3 次则停止自动重启）；默认开关设置项 | 45–60 min |
| X2 接口故障转移 | 参考重写（8 文件） | 接口失败按配置自动切换备用；不影响用户手选顺序；可关闭 | 45–60 min |
| X3 两个播放 bugfix | 对照 cherry-pick/重写 | 复现路径修复且无回归 | 30 min |

### 阶段 2：播放健壮性 E-SP 系列（核心价值，按价值×风险排序）
顺序：**E-SP3 缓冲停滞看门狗 → E-SP1 起播首帧 → E-SP5 ffmpeg 兜底卸载 → E-SP9 不支持格式软兜底 → E-SP4 起播阶段可见 → E-SP6 解码标签 → E-SP7 AVC 自适应 → E-SP2 远程 MKV 字幕（AAR 确认后）**。
- 方式：独立新类（看门狗等）优先定向 cherry-pick（保留上游署名）；与分叉区耦合的对照重写。每条独立提交 + tag。
- 每条验收：复用其自带单测（如 `ExoBufferingStallWatchdogTest`）+ 场景回归（起播、拖动、断流、切线路、换解码内核）+ 双端 arm64 release 构建。
- 预估：每条 45–90 min，全系列约 6–10 小时（分多次会话，每条一停一确认）。

### 阶段 3：历史与跨站续播（数据模型，需先设计）
- 3a：X4 统一媒体身份 + 跨站续播 → 3b：X5 全局历史续播 → 3c：X6 删除同步（墓碑）。
- 涉及 Room schema（当前版本 38）迁移，必须先出设计再动代码；以三份源设计文档为规格基线。
- 预估：设计 1 小时 + 每子步 1–2 小时。

### 阶段 4：播放 UX 零散件（按需逐条启用）
X7 OSD、X8 画面比例、X9 字幕源协议、X10 详情直连。每条独立任务、独立验收，用户可随时裁剪。预估每条 30–60 min。

### 阶段 5：基础设施与检索增强（评估后按需）
X11 多线程代理（先读其需求评估文档确认收益）、X12 广告规则部分、X13/X14 检索小件、X15 APK 推送。预估每条 30–90 min。

## 4. 移植方式决策规则

1. 改动落在两边高度分叉区域（main java 22.7% 分叉）→ **参考重写**：以 Silent1566 任务文档 + diff 为规格，按本仓库现行代码风格实现。
2. 改动为独立新文件 / 干净小 patch → **定向 cherry-pick**，保留原作者署名（`git cherry-pick -x`）。
3. 任何一条与现有行为冲突时，以 muxd93 现状为准做适配，必要时放弃该条并在任务文档记录原因。

## 5. 风险与回滚

- 每任务一个 guard session、一个原子提交、一个 `recovery/<task-id>/<ts>` 标签；回滚 = revert 单提交。
- E-SP 与 X4–X6 触及播放/数据核心，验收契约：不回退现有起播/seek/续播性能与行为；回归门槛 = 阶段末双端 arm64 release 构建（现有流程 4–5 分钟）+ 关键场景手测清单。
- Silent1566 main 停在 2026-09-22，移植时以其文档 + 具体 commit diff 为准，不做整仓合并。

## 6. 启动条件

任一阶段开工前需用户明确批准该阶段；建议首批启动「阶段 0 + 阶段 1」（一次会话内可完成审计与 X1–X3）。

## 7. 执行日志（2026-10-03，阶段 0 + 阶段 1 完成）

### 阶段 0 审计结论（E-SP 系列 + AAR）

| 条目 | 判定 | 说明 |
|---|---|---|
| E-SP1 首帧可见 | 基线已含 | 基线多出"首帧即 progress GONE"行为，E-SP3 移植时应一并纠正 |
| E-SP2 远程 MKV 延后 Cues | 基线已含 | media3 patch blob 与 AAR 均逐字节一致（third_party/maven 本地仓） |
| E-SP3a 缓冲停滞看门狗 | **缺失，优先移植** | 耦合仅 PlayerManager 单点（14 处），自带 20 条单测 |
| E-SP3b seek 预载隔离 | 部分已含 | A/B/C/CP13 基线已有；仅缺 CP16 TV 加载圈收口（注意 silent main 并非终态，需按文档补齐） |
| E-SP4 起播耗时可见 | **缺失，可移植** | PlaybackTrace startupSummary/slowestStage + OSD 一行；deferred-cues 开关在源分叉也是空接，勿搬 |
| E-SP5 FFmpeg 兜底降负载 | **文档与代码严重不符** | 符号在所有已审计分支均为 0，实施前必须先考古定位真实提交 |
| E-SP7 AVC 自适应选轨 | 基线已含 | 无需动作 |
| E-SP9 硬解禁自动软解 | 基线已含 | 无需动作 |

### 阶段 1 完成记录

| 任务 | 提交 | 恢复标签 | 验证 |
|---|---|---|---|
| X1 崩溃静默重启 | `85a2ecf357` | `recovery/X1-crash-silent-restart/20261003094419-85a2ecf3579e` | 双端 arm64 release Java 编译通过 |
| X2 阶段 1（策略/状态机/顺序存储 + 12 条单测） | `cb0ab60a0c` | `recovery/X2-interface-failover-core/20261003095149-cb0ab60a0c0d` | 聚焦单测 12/12 通过；未接线，行为无变化 |
| X3 重复起播竞态修复 | `3c8a9803d9` | `recovery/X3-playback-fixes/20261003095547-3c8a9803d9dc` | 双端 arm64 release Java 编译通过；null 消费点核查通过 |

- X2 阶段 2（VodConfig/BaseConfig 集成 + 设置 UI + Backup 白名单）因与 SUB1–SUB3 重构冲突面大，独立会话实施（见 `docs/X2-interface-failover.md` 阶段 2 设计要点）。
- X3 的短显按钮修复经核查**不适用**（基线从未有该缺陷，是源分叉自己的合并事故）；预加载稳定窗部分推迟至 E-SP3a。
- 环境备注：`gradle-wrapper.properties` 的腾讯镜像改动被"丢弃本地修改"移除后，官方 URL 的 wrapper 缓存目录缺失导致构建失败；已将镜像缓存复制到官方哈希目录解决，仓库文件保持干净。`app/schemas/.../38.json` 会在每次单测/构建后重新生成，属 Room 构建产物（上游未跟踪），如确认保留价值可考虑入库。
- 阶段末回归：双端 arm64 release 全量构建 + ABI 核验见下节。
