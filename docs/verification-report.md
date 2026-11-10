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
| G1 | Android 单元测试 | `./gradlew :app:testDebugUnitTest` | **45 / 45 通过**（8 协议 + 5 签名 + 9 合并 + 其余本地规则） |
| G2 | Android 静态检查 | `./gradlew :app:lintDebug` | **0 error**（8 warning，1 hint） |
| G3 | 构建 | `assembleDebug` / `assembleRelease` | 13.56 MB / 9.72 MB |
| G4 | 冒烟（安装+冷启动） | `scripts/gray-check.ps1` | **PASS**，无崩溃 |
| G5 | 动态壁纸 | 手动设置 + 截图 | **PASS**，图标压在文字之上（见证据 02） |
| G6 | 小组件上屏 | `requestPinAppWidget` + 截图 | **PASS**，7 列透明卡片，与壁纸共存（见证据 04） |
| G7 | 数据写入与备份 | 勾选任务 → 读 `data.json` | **PASS**，`done:true` 且生成 `.backup` |
| G8 | 桌面语法检查 | `npm run check` | **PASS** |
| G9 | 桌面单元测试 | `node --test test/*.test.cjs` | **57 / 57 通过** |
| G10 | 主机回归 | `npm run test:host` | **HOST_REGRESSION_PASS**（14 项检查全绿） |
| G11 | 协议向量跨语言一致 | JS `canonical.cjs` ↔ Kotlin `CanonicalJson` | **11 条向量逐字节一致** |
| G12 | TLS 回环互通 | `node --test test/sync-tls.test.cjs` | **3 / 3 通过** |
| G13 | **跨进程 P0 互通** | 模拟器 → `10.0.2.2:47821` | **PASS**：TLSv1.3 + HELLO/SUMMARY/PONG（证据 06） |
| G14 | 签名向量跨语言 | JS 生成签名 → Kotlin 验证 | **PASS**：篡改字段/换密钥均被拒 |
| G15 | **签名 OPS 跨端校验** | Android 签名 → Node 验签 → ACK | **PASS**：`verified <actorId>` + ACK（证据 07） |
| G16 | 合并场景跨语言一致 | 11 个场景（并发、墓碑、乱序、重复、缺口） | **PASS**：投影逐字节一致 |
| G17 | **双向缺口补齐** | Android 拉取 `seed-actor` 的操作并推送本地操作 | **PASS**：拉取 1 / 推送 2 / 向量双向一致（证据 08） |

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
| `docs/evidence/06-p0-sync.png` | **P0 跨进程互通**：Android 经 TLSv1.3 连上宿主 Node 服务，HELLO/SUMMARY/PONG 全通 |
| `docs/evidence/07-p2-signed.png` | **P2 签名互通**：Android 用 ECDSA 签名的操作被 Node 验签通过并返回 ACK |
| `docs/evidence/08-p2-catchup.png` | **P2 双向补齐**：SUMMARY → WANT → OPS → ACK，拉取远端操作并推送本地操作 |

## 未覆盖 / 未实现（不得当成已完成）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 三端局域网同步 | ⚠️ P2 正确性已成型 | 已实现：规范化 JSON、帧、消息、ECDSA 签名、操作日志（幂等/缺口/重放）、合并与墓碑、SUMMARY/WANT 补齐。**未实现：配对向导、mDNS 发现、后台调度、冲突解决 UI** |
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
