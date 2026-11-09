# 验证报告

日期：2026-11-10
版本：`0.2.0`（versionCode 2）
验证人：自动化灰测脚本 + 人工确认截图

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | Temurin 21.0.13 LTS |
| Gradle | 9.9.0 |
| AGP | 9.5.1（内置 Kotlin 2.4.21） |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| 测试设备 | Android Emulator `medium_tablet`，Android 36（google_apis, x86_64），2560×1600 |
| 桌面端 | Node 24.17.0，Electron 44.9.0（真实窗口） |

## 门禁结果

| # | 门禁 | 命令 | 结果 |
| --- | --- | --- | --- |
| G1 | Android 单元测试 | `./gradlew :app:testDebugUnitTest` | **24 / 24 通过** |
| G2 | Android 静态检查 | `./gradlew :app:lintDebug` | **0 error**（8 warning，1 hint） |
| G3 | 构建 | `assembleDebug` / `assembleRelease` | 13.56 MB / 9.72 MB |
| G4 | 冒烟（安装+冷启动） | `scripts/gray-check.ps1` | **PASS**，无崩溃 |
| G5 | 动态壁纸 | 手动设置 + 截图 | **PASS**，图标压在文字之上（见证据 02） |
| G6 | 小组件上屏 | `requestPinAppWidget` + 截图 | **PASS**，7 列透明卡片，与壁纸共存（见证据 04） |
| G7 | 数据写入与备份 | 勾选任务 → 读 `data.json` | **PASS**，`done:true` 且生成 `.backup` |
| G8 | 桌面语法检查 | `npm run check` | **PASS** |
| G9 | 桌面单元测试 | `node --test test/*.test.cjs` | **30 / 30 通过**（含 3 条跨端兼容用例） |
| G10 | 主机回归 | `npm run test:host` | **HOST_REGRESSION_PASS**（14 项检查全绿） |

G10 覆盖的检查项（脚本自报）：preload/IPC、任务持久化、双窗口同步、主题、迷你模式恢复、
混合 DPI 多屏移动、桌面宿主恢复注入、全屏检测、刷新暂停、草稿保护、渲染进程释放、
自动恢复、数据保留、无渲染进程控制台错误。

## 证据

| 文件 | 说明 |
| --- | --- |
| `docs/evidence/01-wallpaper-preview.png` | 动态壁纸预览页，7 列周历 + 今日高亮 + 真实任务 |
| `docs/evidence/02-icons-on-top.png` | 壁纸形态最终效果：Launcher 图标/时钟组件压在文字之上 |
| `docs/evidence/03-app-with-data.png` | 主程序读取真实 `data.json` 后的周视图 |
| `docs/evidence/04-widget.png` | **首版主形态**：透明周历小组件，7 列、任务可读、与用户壁纸共存 |
| `docs/evidence/05-wallpaper-live-data.png` | 勾选任务后壁纸层实时重绘（完成态变灰） |

## 未覆盖 / 未实现（不得当成已完成）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 三端局域网同步 | ❌ 未实现 | `lan-sync-protocol.md` 是设计基线，无任何同步代码 |
| 手机/平板自适应双栏 | ❌ 未实现 | App 内仍是单一布局，P4 范围 |
| AI 总结 | ❌ 未实现 | 后续阶段 |
| 农历 / 节气 / 节假日 | ❌ 未接入 | 后续阶段 |
| 跨列拖拽调天 | ❌ 未实现 | 后续阶段 |
| 小组件透明/底板切换 | ❌ 未实现 | 当前固定为半透明底板（纯透明在深色壁纸下不可读） |
| 小组件尺寸档位 | ⚠️ 部分 | 已按宽度分三档，但只在 4×2 一种尺寸实测 |
| 导入/导出 UI | ⚠️ 仅单测 | `TaskStore.importFrom/exportTo` 有单测，UI 点击链路未端到端验证 |
| 真机 / 国产 ROM | ❌ 未验证 | 只有模拟器 |
| 卸载后壁纸复位 | ⚠️ 已知行为 | 系统行为，脚本已改为默认 `install -r` |

## 验证中发现并已修复的问题

1. **灰测脚本默认卸载应用** → 会清空用户数据并把动态壁纸重置为系统默认。改为默认 `install -r`，仅 `-CleanInstall` 时卸载。
2. **PowerShell 布尔比较陷阱** → `$true -eq 'SKIP'` 因字符串转布尔恒为真，导致所有检查被吞成 SKIP。改为先判 `-is [string]`。
3. **Release 包不可 `run-as`** → 数据检查退回 `adb root` 读取；干净安装无 `data.json` 时记 SKIP 而非 FAIL。
4. **Lint 10 个 error** → Glance `ColorProvider(Int)` 属受限 API、Composition 内读 `StateFlow.value`。改为 `GlanceTheme.colors` / 颜色资源，并在 `provideGlance` 外取快照。
5. **`SizeMode.Responsive` 报的是档位尺寸而非实际尺寸** → 7 列被压成每列约 30dp，文字逐字换行。改 `SizeMode.Exact` 后按 `LocalSize` 计算列宽。
6. **深色壁纸下深色文字不可读** → 默认加半透明底板；纯透明模式留作设置项。
7. **App 原地升级会丢掉已放置的小组件实例** → 每次 `install -r` 后需要重新 pin。这是 Launcher 行为，灰测脚本要在升级后补一步重新上屏，否则会误判"组件消失"。

## 复现命令

```powershell
# Android 全量灰测（构建 + 安装 + 冒烟 + 证据）
.\scripts\gray-check.ps1 -SdkRoot "C:\Users\Enjory Wong\android-toolchain\sdk" -Device emulator-5554

# 桌面端
cd ..\deepweek-andriod
npm run check
node --test test/*.test.cjs
npm run test:host
```
