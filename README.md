# DeskWeek · Android 手机与平板端

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="DeskWeek Android Icon">
</p>

DeskWeek 是一款专为 Android 打造的桌面周历与待办清单工具，手机与平板均可完美使用。

你可以把它作为一个安静又直观的**桌面透明小组件**，无需打开 App，在手机桌面上就能随时看本周日程、直接点击打勾完成任务；也可以打开主应用，轻松管理长远待办与灵感备忘。所有数据默认保存在本地，纯净、快速、无广告。

---

## ✨ 核心特性

- **桌面周历小组件**
  - **全透明背景**：完美融入你的手机壁纸，不遮挡桌面背景图。
  - **深浅文字自适应**：支持根据壁纸明暗随心切换浅色或深色文字，阅读舒适清晰。
  - **桌面直接交互**：桌面小组件上直接点击即可完成待办，任务更新即刻同步刷新。
  - **自适应多种尺寸**：从小号今日摘要，到中大号完整周历，适配多种桌面排版。

- **周历与日程管理**
  - **周视图一眼尽览**：按周排列任务，清晰掌握本周每天的轻重缓急。
  - **拖拽轻松改期**：长按任务直接拖动到指定日期即可完成改期，也可以点击弹窗选择日期。
  - **灵活排序与规则**：支持按到期时间、创建顺序排列，支持设置提前几天在待办中出现。
  - **误删恢复保障**：支持废纸篓机制，误删随时可一键还原。

- **农历、节气与节假日**
  - 内置离线农历算法，公历、农历双对照。
  - 自动标注二十四节气与法定节假日，方便安排休假与传统节日日程。

- **可选 AI 智能待办提取**
  - 支持粘贴整段会议记录、聊天记录或工作备忘，AI 自动为你提炼拆解出清晰的待办事项。
  - 提取出的待办支持预览和一键批量添加。
  - API 密钥由 Android 系统内置安全芯片（Android Keystore）高强度加密存储，绝不泄露。

- **本地数据与跨端互通**
  - **数据本地自主**：所有日程保存在手机本地存储中，不强制登录，不上传第三方服务器。
  - **电脑端无缝互通**：支持与 Windows 电脑版 DeskWeek 的 `data.json` 互相导入导出。
  - **防损坏与备份**：保存时采用安全原子写入机制，并自动留存最近备份，避免异常断电丢失日程。

- **局域网多设备互联**
  - 内置局域网自动发现功能，在同一个 Wi-Fi 网络下即可互相找到设备，正在持续完善电脑与移动端之间的离线增量互通。

---

## 📱 下载与安装

你可以在 [GitHub Releases](https://github.com/BuyMyCheatPlz/DeskWeek-Android/releases/latest) 下载最新发布的 APK 安装包：

- **安装方法**：下载 `DeskWeek-v1.3.0.apk` 后，在手机或平板上直接打开安装即可。
- **覆盖升级**：后续新版本使用相同签名打包，直接安装即可平滑升级，**原有日程数据完全保留**。

---

## 📖 相关文档

- [功能测试与体验指南](docs/acceptance-checklist.md)：快速了解如何体验小组件、任务管理、AI 提取与跨端文件互通。
- [版本更新与验证报告](docs/verification-report.md)：详细记录当前版本的修复项、性能优化与测试验证结果。
- [版本规划与设计思路](docs/android-port-plan.md)：Android 端的产品定位、多尺寸屏幕适配及后续规划。
- [局域网同步协议设计说明](docs/lan-sync-protocol.md)：局域网设备发现与端到端协同设计的技术说明。

---

## 💻 开发者编译与构建

如果你想自行编译或参与开发，请参考以下指南：

### 环境要求
- **JDK**：21
- **Android SDK**：API 36 / 37
- **Gradle**：9.9.0（项目已内置 Gradle Wrapper）

### 编译步骤

1. 在项目根目录创建 `local.properties` 并配置 Android SDK 路径：
   ```properties
   sdk.dir=C\:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
   ```

2. 运行单元测试：
   ```bash
   ./gradlew testDebugUnitTest
   ```

3. 打包 APK 安装包：
   ```bash
   # 打包正式版 APK（生成于 app/build/outputs/apk/release/app-release.apk）
   ./gradlew assembleRelease

   # 打包测试版 APK（生成于 app/build/outputs/apk/debug/app-debug.apk）
   ./gradlew assembleDebug
   ```

---

## 🤝 与 Windows 桌面版的关系

- Windows 桌面版开源项目：[EasonGuuu/Deskweek](https://github.com/EasonGuuu/Deskweek)
- Android 移动端与 Windows 桌面版共享统一的数据交换格式（`data.json`），你可以在两端之间随时导出并导入自己的任务日程。

---

## 📄 开源许可

本项目采用 [MIT 许可证](LICENSE)。原项目版权归 小虾 所有。
