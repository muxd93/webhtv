# SUB3 — ConfigDialog 抽基类（C3）+ jar 下载反馈降级（C4）+ 刷新周期可配置（C5）

## Recovery anchor

- **状态**：代码完成，编译+单测通过（2026-10-02）。真机验证待用户。SUB1 §1.C 全部闭环（C6 弃用，见下）。
- **目标**：SUB1 §1.C 剩余三项：C3 双 flavor `ConfigDialog` 抽公共基类并补齐 mobile 推送回填；C4 配置级 spider jar 下载提示与失败降级（jar 失败不阻断配置生效）；C5 订阅自动刷新周期可配置（默认 12h，可 6/12/24/48h/关闭）。C6（历史项 URL 副标题）正式弃用——被 SUB2 池管理页与自动命名取代，理由：按钮型单行布局改双行需重设计、收益低。
- **验收标准**：
  1. 两 flavor `ConfigDialog` 行为与现状一致（新增/编辑/删除/推荐源/文件选择/URL 快捷补全/推送回填），公共逻辑全部在 main 的 `BaseConfigDialog`；mobile 新增 ServerEvent 推送回填能力。
  2. 配置含不可达 spider jar 时：配置本体仍加载成功（站点列表可见），有一次性提示；jar 下载开始时有提示。
  3. 「增强」页新增「订阅刷新周期」行：6/12/24/48 小时/关闭 循环切换，默认 12h；关闭后 `refreshIfStale`（直播进页+首页 onResume）与聚合自动刷新均不再触发；手动聚合/换源不受影响。
  4. 两 flavor javac 编译 + live 单测通过；`ConfigDialog` 公共行为无回归。
- **回滚**：单笔原子提交 `git revert`（Setting 新键对旧版本无影响）。
- **唯一下一步**：用户真机验证（验收 1–4）；订阅源系列（SUB1–SUB3）闭环，后续按真机反馈决定是否开新任务（如站点级 jar 运行时报错文案、源池页进阶功能）。

## 设计决策

- **C3 基类形态**：`BaseConfigDialog extends BaseAlertDialog`（main）持有 type/edit/origin 状态与全部业务方法；子类仅提供 `nameView()/urlView()` 两个 EditText 访问器、flavor 专属 initView/initEvent/onConfigSaved。推送回填（`@Subscribe ServerEvent` + EventBus 注册/注销）上移基类 → mobile 获得与 leanback 同等能力。预设列表对话框用钩子 `listDialog()`（mobile 覆写为带主题样式）。文件选择统一为直接 `onConfigSaved + dismiss`（放弃 leanback 的 100ms 延迟双重 post——mobile 直连路径长期验证可行）。C5 不改 BaseConfig 对外行为（仅阈值来源改为 Setting）。
- **C4 边界**：`VodConfig.initSite` 的 `parseJar` 包 try/catch（Throwable → 日志 + 一次性 Toast 提示），配置继续解析；`JarLoader.parseJar` 下载分支前发「正在下载解析库」提示。站点级懒加载 jar 的运行时报错文案改进不在本轮（涉及 spider 调用链多处，单独立项）。
- **C5 语义**：`Setting.getStale()`（小时，默认 12，0=关闭）。`BaseConfig.refreshIfStale` 与 `LiveAggregator.refreshIfStale` 受其门控；`LiveAggregator.isStale/parse` 的阈值同源（0 时「立即更新」= 强制全量重拉，手动路径可接受）；depot 导入/换源等显式路径不门控。UI 放「增强」页（两 flavor 均有的杂项设置区，循环节切换模式与 cspWarmup 一致，行加入 reorderItems 列表）。

## 实施记录（2026-10-02）

- **C3**：新增 main `BaseConfigDialog extends BaseAlertDialog`——持有 `type/edit/origin/append` 状态与 `getConfig/getStoredConfig/getTypeName/getDialogTitle/initConfigViews/initConfigEvents/detect/onChoose/onPreset/importPreset/onPositive/saveConfig`（含自动命名与直播池删除联动）及 `launcher`、EventBus `onServerEvent` 推送回填（onStart/onStop 注册注销）；子类仅实现 `nameView()/urlView()/onConfigSaved` + flavor 专属 initView/initEvent。访问器类型：`nameView()` 返回 `TextView`（leanback 布局中 name 为 MaterialTextView，无光标操作），`urlView()` 返回 `EditText`（需要 `setSelection(int)`，该单参重载仅存在于 EditText）。预设列表对话框经 `listDialog()` 钩子（mobile 覆写带 `ThemeOverlay_WebHTV_LightDialog`）。流式 setter 在基类返回基类型，两子类以协变覆写保持 `ConfigDialog.create().vod()...` 调用链。文件选择统一为直接 `onConfigSaved + dismiss`（移除 leanback 旧的 100ms 双重 post）。**mobile 获得推送回填能力**（行为新增，见验收 1）。leanback 272 行/249 行 → 瘦身为约 90 行装配代码。
- **C4**：`VodConfig.initSite` 的 `BaseLoader.parseJar(spider, true)` 包 try/catch（Throwable → SpiderDebug 日志 + Toast `jar_failed`），配置本体继续解析；`JarLoader.parseJar` 下载分支前经 `App.post` 提示 `jar_downloading`。站点级懒加载 jar 的运行时报错文案改进留待后续（涉及 spider 调用链多处）。
- **C5**：`Setting` 新增 `stale` 键（`getStale/putStale/getStaleOptions`，候选 {6,12,24,48,0} 小时，默认 12）；`BaseConfig.refreshIfStale` 门控 + 阈值改由 Setting 计算（0=关闭自动刷新）；`LiveAggregator` 删除 `STALE_MS` 常量改 `staleMs()`（`parse`/`isStale` 同源——0 时手动聚合/立即更新强制全量重拉，`refreshIfStale` 直接 return）；depot 导入与换源等显式路径不受门控。两 flavor「增强」页新增「订阅刷新周期」行（`staleRefresh/staleRefreshText`，点击循环切换，加入 reorderItems 列表与 safeSet 文案刷新）。
- **C6 弃用裁决**：历史项 URL 副标题不再实施——SUB2 池管理页已承载源信息展示 + 波次 1 自动命名已消除空名歧义；单行按钮改双行布局的收益不抵风险。

## 验证记录（2026-10-02）

- 组合验证：`bash ./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac ':app:testLeanbackArm64_v8aDebugUnitTest' --tests 'com.fongmi.android.tv.live.*' -x :chaquo:installArm64_v8aDebugPythonRequirements` → **BUILD SUCCESSFUL in 1m 5s**。
- 过程修复：漏 `android.view.View` 导入；`TextView` 无单参 `setSelection(int)` 导致访问器类型拆分（name=TextView / url=EditText）；全部一次重编译通过。
- 未验证项（真机）：两 flavor 配置对话框全操作回归（新增/编辑/清空删除/推荐源/文件选择/TV 推送回填）、jar 不可达时配置仍可用 + 两条提示、刷新周期切换即时生效。
- 行为契约核对：`saveConfig` 语义与波次 1 完全一致（含池联动）；`refreshIfStale` 仅阈值来源变化，in-flight 守卫保留；SWR/聚合/探测主链路未触碰。
