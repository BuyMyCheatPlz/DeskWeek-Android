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

需要 JDK 21 + Android SDK（`platform-36`、`build-tools`）。用 Android Studio 打开本目录即可，或用命令行：

```bash
./gradlew assembleDebug
```

## 许可

MIT，见 [LICENSE](LICENSE)。原项目版权归 小虾 所有。
