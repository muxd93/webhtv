# X1 崩溃静默重启

## 恢复锚（Recovery anchor）

- 目标：崩溃页新增"静默重启"按钮——重启应用且下一次启动跳过一次远程点播/直播配置加载，用于坏配置导致的反复崩溃自救。
- 验收标准：点击"静默重启"后应用重启且本次启动不加载远程配置（一次性标记消费后即清除）；普通"重新启动"与"错误信息"行为不变；基线自动 `Prefers "crash"` 标记（SiteApi 消费）行为保留；双端 arm64 release 编译通过。
- 回滚：整任务单提交，`git revert` 即可；任务文档随提交。

## 规格（源自 Silent1566，参考重写为纯增量）

来源：`Silent1566` 分支 `docs/CRASH-SILENT-RESTART-crash-silent-restart.md`、`CrashRestartMode.java`、`CrashActivity.java` 与两端 `HomeActivity.initConfig()`、两端 `activity_crash.xml`。

1. 新增 `utils/CrashRestartMode.java`：`arm()` 同步 `commit()` 写一次性标记 `crash_restart_skip_config_once`（崩溃库会立即杀进程，必须同步落盘）；`consume()` 读后即删。
2. `CrashActivity.initEvent()`：新增 `silentRestart` 点击 → `CrashRestartMode.arm()` + 原有 restartApplication 流程。**不移植**其"删除 setCrash()"与错误信息文案改动（保持基线行为，纯增量）。
3. 两端 `activity_crash.xml`：在 restart 与 details 之间插入 silentRestart 按钮（样式按各自 flavor 既有按钮）。
4. 字符串：`crash_silent_restart`（en "Silent restart" / zh "静默重启"）。
5. leanback `HomeActivity.initConfig()`：首句 `if (CrashRestartMode.consume()) { SpiderDebug.log("startup", "skip config load once after crash restart"); showContent(); return; }`（置于 mConfigLoading 判断之前）。
6. mobile `HomeActivity.initConfig()`：首句 `if (CrashRestartMode.consume()) { checkAction(getIntent()); StateEvent.empty(); return; }`（与源实现一致）。

## 实施记录

- 2026-10-03：按上述规格实施；guard 会话 X1-crash-silent-restart（standard）。
