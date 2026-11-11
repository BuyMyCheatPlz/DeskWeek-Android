# DeskWeek · Android

DeskWeek 2.1.0 现已支持 [二维码无损压缩优化](docs/qr-density.md)，传输张数大幅减少 70%~90%，面对面扫码更快速。

DeskWeek 的 Android 手机和平板客户端。主要功能是**桌面周历小组件**和**任务管理 App**，支持透明/有底板模式以及浅色/深色文字，放在桌面上不会遮挡你原来的壁纸。

## 能做什么

| 功能 | 说明 |
| --- | --- |
| 管理任务 | 新建、编辑、完成、删除到垃圾箱、恢复、永久删除；支持提前显示、排序、跨周翻页和改期 |
| 手机/平板 | 根据屏幕宽度自动切换单栏或双栏布局，都有配套的自动化测试 |
| 桌面小组件 | 大小自适应，显示真实任务，点一下就能完成，数据变了会自动刷新，还能调透明度和文字颜色 |
| 数据存储 | 存在本地的 JSON 文件里，写入时会自动备份上一版；文件损坏时可以恢复 |
| 数据互通 | 和桌面版的 data.json 格式相同，导入时可以选择合并还是替换，不会偷偷覆盖 |
| 二维码传输 | 手机和电脑面对面扫二维码就能传任务，不需要联网（详见下方） |
| AI 文本提取 | 可接入兼容 OpenAI 的大模型，粘贴一段文字就能批量生成任务；密钥加密保存在手机上 |
| 农历/节气/节日 | 已接入，和桌面版算法一致；不保证包含未来年份的官方调休安排 |
| 动态壁纸 | 实验功能，启用后会替换你的壁纸，不是默认开启的 |

> 透明小组件和动态壁纸是两个不同的东西：小组件在桌面格子里，壁纸是背景。目前 Wallpaper Engine 兼容性还没在真机上验证。

完整的功能规划见 [实现规划](docs/android-port-plan.md)；当前测试结果见 [验证报告](docs/verification-report.md)。

新增后组件仍为空的修复及验收见 [小组件刷新修复记录](docs/widget-refresh-fix.md)。无日期任务现在以「未排期」展示。

还没做完或需要在真手机上确认的功能，可以参照 [验收清单](docs/acceptance-checklist.md) 来检查。

## 二维码离线传输

手机和电脑不在同一网络也能互传任务，只要两台设备面对面就行：

- **电脑 → 手机**：电脑生成二维码，手机扫码后确认导入
- **手机 → 电脑**：手机生成二维码，电脑用摄像头扫码
- **手机 ↔ 平板**：也可以互扫二维码传输

详见 [二维码离线传输方案](docs/offline-json-qr.md)。

## 开发与测试

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\gray-check.ps1 -SkipBuild -EvidenceDirectory "C:\path\to\test-artifacts"
```

- 设备测试需要先用 USB 连上手机（adb 可用即可）
- Debug 版包名是 `com.deskweek.android.debug`、名称是 "DeskWeek Debug"，和正式版可以同时安装
- 测试用的截图、日志等产物放在项目外面，不会写回到项目里

## 和桌面版的关系

- 桌面版（上游）：[EasonGuuu/Deskweek](https://github.com/EasonGuuu/Deskweek)，Electron + Win32，MIT 许可
- Android 和桌面版不共用代码，靠相同的数据格式和共同的测试样例来保持一致
- data.json 只包含任务数据，不包含同步历史或配对信息

## 构建

需要的工具：

| 组件 | 版本 |
| --- | --- |
| JDK | 21（推荐 Temurin 21.0.13） |
| Gradle | 9.9.0（已包含在项目里） |
| AGP | 9.5.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

```bash
./gradlew assembleDebug
```

需要一个 `local.properties` 文件指向你电脑上的 Android SDK（这个文件不提交到 Git）：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

> 国内网络提示：Gradle 下载经常超时，可以把 `gradle/wrapper/gradle-wrapper.properties` 里的下载地址换成腾讯镜像：
> `https://mirrors.cloud.tencent.com/gradle/gradle-9.9.0-bin.zip`

## 许可

MIT，见 [LICENSE](LICENSE)。原项目版权归 小虾 所有。
