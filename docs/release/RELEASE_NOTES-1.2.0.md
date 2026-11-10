# DeskWeek Android v1.2.0

发布日期：2026-11-10
构建：`versionCode 5` / `versionName 1.2.0`
产物：`DeskWeek-1.2.0.apk`，SHA-256 见 [checksums](../release/checksums.txt)

## 相对 1.1.0 新增

**局域网发现（mDNS / DNS-SD）**

- Android 用 `NsdManager` 搜索 `_deskweek-sync._tcp`，解析后自动填入主机与端口
- 桌面端原型服务默认广播该服务（`bonjour-service`），可用 `--no-mdns` 关闭
- 设置页新增「搜索设备」按钮，发现结果会更新端点输入框
- TXT 只含协议版本与 groupId，不广播任何任务内容

**改期交互**

- 任务行支持长按拖拽到某一天（拖动时跟随悬浮标签，松手落点判定）
- 同时保留「长按 → 选择日期」对话框，两条路径都能改期

## 验证结果

| 门禁 | 结果 |
| --- | --- |
| Android 单元测试 | 59 / 59 |
| Android Lint | 0 error |
| 灰测 | 全 PASS |
| 桌面单元测试 | 62 / 62 |
| 桌面主机回归 | HOST_REGRESSION_PASS |
| 长按改期 | 实测通过：任务从 11-10 移动到 11-12 并落盘 |
| mDNS 广播 | 桌面端已广播 `_deskweek-sync._tcp` |

## 已知限制（含未经验证的部分）

- **mDNS 发现未在真实局域网验证**：模拟器使用用户态网络，不转发组播，
  在模拟器里点「搜索设备」只能看到"搜索中"，收不到桌面端广播。
  代码路径与 NSD 生命周期已实现并通过启动日志确认，但**真实发现需要两台真实设备**。
- **拖拽手势未被自动化验证**：`adb input draganddrop` 无法触发 Compose 的
  长按拖拽（日志确认手势未开始）。落点判定逻辑已随长按改期路径一并验证，
  手势本身建议在真机上人工确认。
- Android 的 mDNS 注册（让桌面端发现手机）尚未实现，当前只有单向发现。

## 安装

```bash
adb install -r DeskWeek-1.2.0.apk
```

与 1.0.0 / 1.1.0 同一签名，可直接覆盖升级。
