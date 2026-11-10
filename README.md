# DeskWeek · Android

[DeskWeek](https://github.com/EasonGuuu/Deskweek) 的 Android 原生版：周历 + 待办，手机与平板通用。

Android 采用**透明主屏周历小组件**（Glance）与完整管理 App，保留现有壁纸，可与 Wallpaper Engine 等动态壁纸共存。
规划支持 Windows、Android 手机、Android 平板首次配对后的局域网自动发现与双向同步，不依赖云服务或电脑常开；保留 `data.json` 导入导出。

Android 休眠、强行停止及后台执行限制可能延迟同步，恢复执行后补齐，不承诺进程停止时仍能即时同步。

| | |
| --- | --- |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 Adaptive |
| 数据（规划） | Room + DataStore，本地优先，`data.json` 与桌面版双向兼容 |
| 同步（规划） | 三端对等局域网同步，首次配对，离线编辑后补同步 |
| 最低版本 | Android 8.0（API 26） |
| 状态 | **P0 展示层已实现并通过灰测**；本地数据闭环部分完成；**三端同步未实现** — 见 [实现规划](docs/android-port-plan.md)、[同步协议](docs/lan-sync-protocol.md) 与 [验证报告](docs/verification-report.md) |

## 与上游的关系

- 上游：[EasonGuuu/Deskweek](https://github.com/EasonGuuu/Deskweek)（Electron + Win32，MIT）
- 本仓库：独立原生实现，从上游 fork 的 `main` 起步，对齐业务规则、交换格式与同步协议
- 目标支持桌面版 `data/data.json` 导入和兼容导出；当前已按该格式读写本地数据（含原子写与 `.backup` 恢复）
- 桌面端也需要增加同步模块，当前上游版本尚不具备局域网同步能力

## 当前实现状态

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 透明周历小组件（首版主形态） | ✅ 已实现 | `docs/evidence/04-widget.png` |
| 主程序周视图 / 清单 / 编辑 | ✅ 已实现 | `docs/evidence/03-app-with-data.png` |
| `data.json` 兼容读写 + 原子写 + 备份恢复 | ✅ 已实现，32 条单测 | `app/src/test/` |
| 导入 / 导出 | ✅ 逻辑+单测；UI 未端到端点击验证 | `TaskStoreTest` |
| 动态壁纸形态（实验） | ✅ 可运行，**非首版形态** | `docs/evidence/02-icons-on-top.png` |
| 同步协议 **P0**：规范化 JSON / 帧 / 消息 / TLS 握手 | ✅ 两端实现 + 11 条共享向量 + 跨进程互通 | `docs/evidence/06-p0-sync.png` |
| 同步 **P2 切片**：ECDSA 签名操作 + 跨端验签 | ✅ 两端实现 + 签名向量 + 跨进程验签 | `docs/evidence/07-p2-signed.png` |
| 同步 **P1/P3**：配对、发现、操作日志、合并、冲突、调度 | ❌ 未实现 | 协议文档 §8 阶段表 |
| AI 总结 / 农历 / 拖拽 / 自适应双栏 | ❌ 未实现 | 后续阶段 |

完整门禁记录见 [验证报告](docs/verification-report.md)。

## 构建

已验证的工具链组合：

| 组件 | 版本 |
| --- | --- |
| JDK | 21（Temurin 21.0.13） |
| Gradle | 9.9.0（含 wrapper） |
| AGP | 9.5.1（**Kotlin 支持已内置，不需要 `kotlin-android` 插件**） |
| Kotlin | 2.4.21（经 AGP 内置） |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| SDK Platform | `platforms/android-37.2`（另装 `android-36` 备用）、`build-tools/36.0.0` |

```bash
./gradlew assembleDebug
```

需要 `local.properties` 指向本机 SDK（该文件不入库）：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

> 国内网络提示：`services.gradle.org` 走 307 重定向时经常超时，可把
> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成
> `https://mirrors.cloud.tencent.com/gradle/gradle-9.9.0-bin.zip`。

## 许可

MIT，见 [LICENSE](LICENSE)。原项目版权归 小虾 所有。
