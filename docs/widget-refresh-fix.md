# 小组件新增任务后仍为空：排查与修复

日期：2026-11-11。基于 `9264757` 排查，未提升版本号，未发布 release。

## 原因与复现

发现两个可独立复现的问题：

1. **小组件固定使用首次加载的空快照。** `provideGlance` 在 `provideContent` 外读取一次任务。Glance 会复用活跃会话，调用 `updateAll` 只触发会话刷新，不会重新执行这次快照读取。因此先添加空组件、再新增今天的任务，组件仍显示「还剩 0 项 / 今天空着」，即使显式调用 `updateAll` 也不变。
2. **未设置日期的任务始终被过滤。** 小组件只分组显示 `date` 非空的任务。即便刷新链路修好，从清单新增一个无日期任务，组件仍为空。

这两种失败都由真实 `AppWidgetHost` 回归测试复现，测试通过 Android 系统的 RemoteViews 链路读取组件内容，没有用假的快照替代。先只修复快照观察，今天任务回归转为通过；无日期任务测试仍失败，再独立修复日期展示。

对应的框架行为见 [Glance 官方源码说明](https://github.com/androidx/androidx/blob/androidx-main/glance/glance-appwidget/src/main/java/androidx/glance/appwidget/GlanceAppWidget.kt) 和 [Android 小组件状态更新文档](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)。

## 修改

- 在 `provideContent` 内用 `collectAsState` 观察实际任务状态，并从最新任务构造周历快照。活跃会话能看到新增、编辑、完成、删除和导入后的变化；保留原有 `updateAll` 通知，以便唤醒未运行的组件会话。
- 小号、中号组件在今天的任务之后展示无日期任务，并加「未排期」标记。大号周历增加「未排期」区域。任务仍保持空日期，不偷偷改成今天；垃圾箱任务不显示。
- 小号最多展示 2 条、中号最多 3 条，大号未排期区域最多 2 条；多余内容保留数量提示。小号/中号仍以今天为主，大号展示本周，其他日期的已排期任务不属于今天列表。
- 刷新异常不再静默丢弃，会写入 `DeskWeekWidget` 日志；协程取消正常传播，不影响已成功保存的任务。

## 回归测试

新增 `WidgetRefreshTest`，测试真实组件宿主：

- 初始空组件 → 新增今天任务 → 编辑 → 完成 → 删除 → 恢复 → 永久删除 → JSON 导入，均观察系统收到的组件内容。
- 通过 App 的「清单 → +」新增无日期任务，小号组件显示标题和「未排期」，检查任务日期仍为空。
- 中号与大号组件分别显示无日期任务。

测试日志、失败证据和测试包保存在项目外：

`C:\Users\Enjory Wong\.workspace\ChatGPT\deskweek-widget-20261111\`

其中 `repro-stale-widget.xml`、`repro-undated.xml` 是修复前失败，`fix-stale.log` 是第一步修复通过，`widget-regression.log` 是四项组件测试通过。完整构建、单测、Lint 和界面回归结果见 `final-gates.log`。

整套界面回归结果为 **19 / 20 通过**，四项组件测试全部通过；未通过的一项是二维码屏幕截图识别测试。对该项做过测试解码方式的复核，但未能稳定通过，已撤回这部分无关测试改动，没有据此宣称整套功能通过。该失败、截图与初次完整报告保存在 `first-full-gates.log`、`first-full-results/`、`qr-check.log`、`qr-failure.png`。本轮没有修改二维码的产品代码。

最终定向门禁 `widget-phone-final.log` 通过：97 项 JVM 单测、Lint（0 errors，45 warnings，2 hints）、Debug 构建以及 4 项真实组件宿主测试。组件测试分别在 API 36 模拟器的平板尺寸和手机尺寸执行通过；手机尺寸为 1080×2400 / 420 dpi，完成后恢复原来的 2560×1600 / 320 dpi。测试报告在 `phone-widget-results/`、`jvm-results/`。没有将模拟器结果表述为真实手机桌面已验收。

修复测试包为证据目录中的 `DeskWeek-2.0.0-widget-fix-debug.apk`，同名 `.sha256` 文件记录其校验值。

## 安装后验收

1. 安装本轮修复测试 APK，打开 **DeskWeek Debug**。Debug 与正式版是两个独立应用，任务和组件也各自独立；需要原数据时，从原 App 导出 JSON，再导入 Debug。
2. 添加 **DeskWeek Debug** 对应的小组件。若桌面上是旧正式版组件，它不会读取 Debug 的任务。
3. 先确认组件为空，再从清单新增一条今天任务；返回桌面应出现标题，不应要求重启 App 或等待半小时。
4. 新增一条无日期任务，检查出现「未排期」；小号、中号、大号分别检查。任务日期应继续为空。
5. 修改标题、完成、删除、恢复，再检查组件对应变化；重开 App 后任务仍存在。

自动化宿主测试覆盖的是实际 Android 组件机制，不代表已经验证了用户手机品牌桌面的所有行为。仍需按以上步骤在实际手机或平板确认。
