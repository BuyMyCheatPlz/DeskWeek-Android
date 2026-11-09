# DeskWeek · Android

[DeskWeek](https://github.com/EasonGuuu/Deskweek) 的 Android 原生版：周历 + 待办，手机与平板通用。

桌面版的形态是「贴在壁纸上的周历浮窗」，Android 上不对应悬浮窗，对应**主屏小组件**（Glance）。
完整周历与任务管理放在主程序里。

| | |
| --- | --- |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 Adaptive |
| 数据 | Room + DataStore，`data.json` 与桌面版双向兼容 |
| 最低版本 | Android 8.0（API 26） |
| 状态 | **规划中** — 见 [实现规划](docs/android-port-plan.md) |

## 与上游的关系

- 上游：[EasonGuuu/Deskweek](https://github.com/EasonGuuu/Deskweek)（Electron + Win32，MIT）
- 本仓库：独立实现，从上游 fork 的 `main` 起步，**不共享代码**，只对齐数据格式与业务规则
- 上游数据文件 `data/data.json` 可直接导入本应用，也可从本应用导出回灌桌面版

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
