# DeskWeek Android v1.0.0

发布日期：2026-11-10
构建：`versionCode 3` / `versionName 1.0.0`，`minSdk 26`，`targetSdk 36`，`compileSdk 37`
产物：`DeskWeek-1.0.0.apk`（9.77 MB），SHA-256 见 [checksums.txt](checksums.txt)

## 这个版本包含什么

**本机功能**

- 透明周历小组件：7 列周视图，点击任务切换完成，按尺寸三档布局，与用户壁纸共存
- 主程序：周视图（手机单栏 / 平板双栏自适应）、清单三态（未完成/已完成/垃圾箱）、编辑面板
- 数据：与桌面版同构的 `data.json`，原子写 + `.backup` 滚动 + 损坏自动恢复
- 导入导出：SAF 选择文件，导出文件可直接替换桌面版 `data/data.json`
- 动态壁纸（实验形态）：Canvas 手绘周视图，图标压在文字上

**同步（局域网，P2 正确性）**

- TLS 1.2+ 传输，自签证书按 SHA-256 固定指纹
- ECDSA P-256 签名操作，两端交叉验签
- 操作日志：唯一键幂等、乱序缓冲、缺口补齐、连续向量
- 合并：字段组原子合并、trash 优先于并发 restore、purge 终结、同日确定性分色
- 手动端点 + 最多 3 次退避重试

## 验证结果

| 门禁 | 结果 |
| --- | --- |
| Android 单元测试 | 45 / 45 |
| Android Lint | 0 error |
| 灰测（安装/启动/组件/落盘/截图） | 7 / 7 |
| 桌面单元测试 | 57 / 57 |
| 桌面主机回归 | HOST_REGRESSION_PASS（14 项） |
| 签名校验 | `apksigner verify` 通过，证书 CN=DeskWeek |

完整记录见 [验证报告](../verification-report.md)。

## 已知限制（不要当成已完成）

- **配对与发现未实现**：只有手动填 IP/端口，没有二维码配对，也没有 mDNS 自动发现
- **信任模型是 TOFU**：首次连接即信任对方公钥，不构成安全模型，不要用于不受信任的网络
- **后台同步未实现**：需要用户在前台点"立即同步"，没有 WorkManager 补同步，Doze 下不会自动补齐
- AI 总结、农历/节气、拖拽调天、冲突解决 UI 均未实现
- 只在 Android 16 模拟器（2560×1600 平板）验证过，**未在真机与国产 ROM 上验证**

## 安装

```bash
adb install DeskWeek-1.0.0.apk
```

升级会保留 `data.json`；卸载会清空数据并重置动态壁纸（系统行为）。

## 签名

发布包由 `keystore.properties` 指定的 keystore 签名。该文件与 `*.jks` 均不入库，
密钥与口令保存在仓库外的 `android-toolchain/release/`。
**升级必须使用同一 keystore**，否则系统会拒绝安装。
