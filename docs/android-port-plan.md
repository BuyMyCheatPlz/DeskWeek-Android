# DeskWeek Android 与三端局域网同步规划

更新时间：2026-11-10。本文替代原来的“动态壁纸为主、仅文件互通”规划。状态：实现前设计基线；本次不实现功能。

## 1. 已定产品方向

Windows、Android 手机、Android 平板各自保存完整任务，离线可独立使用；首次配对后，在可互通的局域网中自动发现并交换改动。无需账号、云服务或固定主机；电脑关闭时手机和平板也能直接同步。

Android 采用**透明桌面周历小组件 + 完整管理 App**，保留用户已有壁纸，与 Wallpaper Engine 等动态壁纸共存。小组件占 Launcher 网格，不能通用实现图标与组件重叠。动态壁纸及系统悬浮窗不列入首版。

| 项目 | 决策 |
| --- | --- |
| 安装包 | 手机、平板共用一个 APK，按可用窗口与组件尺寸适配 |
| 桌面展示 | Glance 周历与任务摘要，透明/有底板可选，可调整尺寸 |
| 数据 | 本地优先、三端对等增量同步，保留 data.json 导入导出 |
| 配对 | 首次逐对确认；三台设备建立三条直接信任关系，此后自动重连 |
| 视觉 | 沿用桌面六色板、日期强调与深浅主题；透明文字颜色可独立配置 |
| 首版 | 任务管理、周视图、组件、局域网同步、备份和导入导出 |
| 后续 | 年/月/日视图增强、可选 AI、App 内拖拽等分阶段对齐 |
| 通知 | 首版不新增到期通知；仅限时后台同步会话使用必要服务通知 |

“同网即可”以完成首次配对、网络权限和必要防火墙授权为前提；不自动信任同网陌生设备。Android 被停止或深度休眠后不能保证联网瞬间同步，恢复执行后自动补齐。

## 2. 已同步的源码基线

本次在两仓库执行 git pull --ff-only，未自动提交或推送。

| 工程 | 本地目录 | 同步结果 |
| --- | --- | --- |
| Windows | C:\Users\Enjory Wong\Deskweek | origin/main：0ca4b0d → 97823792d5c8b7cd67f80583b38271286eac1cb8 |
| Android | C:\Users\Enjory Wong\DeskWeek-Android | origin/main 已最新：0fbb3807b2448214436c81753d32a2e2daa7681a |

Windows 仍是 Electron + Win32，package.json 标记 1.3.0。src/main/storage/store.cjs 已有 base/next/current 字段合并、冲突检查、原子写入和有效文件备份；它解决本机多窗口并发，尚无跨设备同步。

Android 当前为 Compose 入口、Glance 摘要和 WallpaperService 演示，使用 SampleTasks；Room、真实编辑和同步均待实现。暂留壁纸实验代码，实现首版时改默认入口为组件配置，不能把演示当作正式功能。

共同协议见 [三端局域网同步协议](lan-sync-protocol.md)。

## 3. 手机、平板与小组件

| 场景 | 手机/窄窗口 | 平板/宽窗口 |
| --- | --- | --- |
| App 周历 | 七日日期条 + 选中日任务，可切三日 | 七列周历，足够宽时增加详情面板 |
| App 导航/编辑 | 底部导航，页面或底部面板编辑 | 侧边导航与详情面板 |
| 小组件小尺寸 | 今日摘要与新增入口 | 同样依实际尺寸显示摘要 |
| 小组件中尺寸 | 七日日期条 + 选中日任务 | 三日任务或周摘要 |
| 小组件大尺寸 | 七日上下分区，可选紧凑七列 | 七列完整周历 |
| 外观 | 字号、文字颜色、底板透明度、显示数量 | 同样支持，适配横竖屏 |

按 dp 尺寸和字体缩放布局，不按机型、屏幕像素或固定网格数判断。任务过多时显示“还有 N 项”，不无限缩小字号。组件各自保存当前周、选中日及外观。

组件支持点日期、完成任务、按钮翻周、快捷新增、打开详情；横向滑动交给桌面翻屏，App 内可使用横向手势。支持文字阴影/描边与明暗颜色；不承诺分析 Wallpaper Engine 实时画面自动取色。

本地编辑或收到远端改动后，先提交数据库，再主动刷新组件。小组件不是同步服务，不以分钟级轮询刷新维持数据。

## 4. 业务与数据对齐

- 首版包括新建/编辑、完成/撤销、软删除、垃圾箱恢复和永久删除。
- 标题 100 字、备忘 4000 字，具体截断口径以 JS/Kotlin 共同样例固化，注意 UTF-16 与 Unicode 差异。
- defer 支持 none、1m 和正整数天数，例如 3d；提前一月按月末夹紧。未到显示日期的任务在待办隐藏，在日历仍可查看。
- 排序、完成时间、同日重新分色直接提取桌面共同样例；并列情况增加稳定 taskId 顺序，避免各端随数组顺序不同而分色不同。
- done/completedAt、date/time/defer 等关联字段作为完整业务操作提交。
- date/time 表示日历本地日期时间，不跨时区自动平移；历史 ISO 时间戳不用于决定冲突胜负。
- 未识别的任务扩展字段与 prefs 键保存在兼容层，导入导出不静默丢弃。
- 同步任务、完成状态、垃圾箱和必要历史；主题、窗口位置、组件布局、AI 配置与密钥保留在各设备。
- 农历、节气、节假日对照桌面算法和资料；未来调休需更新资料，不能仅凭算法一致判断正确。

Room 事务和 WAL 保证数据库一致性；另做导入前备份、迁移备份与可恢复导出，WAL 不代替历史备份。

## 5. 自动发现及 Android 执行边界

使用 mDNS/DNS-SD 发现，Android 用 NsdManager，保留手动 IP/端口和二维码兜底。Windows 有线与移动端 Wi-Fi 只要网络可互通即可；同名 Wi-Fi 不代表跨 VLAN 或访客网络可达。手动地址不能绕过 AP 隔离。

| 状态 | 策略与承诺 |
| --- | --- |
| Windows 托盘运行 | 在获准的局域网接口发现并同步，退出后停止 |
| Android 前台 | 发现、监听、主动连接，自动交换增量 |
| 编辑任务/点击组件 | 立即保存本地，再尝试短时同步；执行受限则排队 |
| 网络恢复且进程可执行 | 触发发现和重连，失败退避 |
| Android 普通后台 | WorkManager 机会性补同步，系统可延迟 |
| 主动开启限时持续同步 | 前台服务与可见通知；超时/结束即停止 |
| Doze、强行停止、权限拒绝 | 标明待同步与最后成功时间，恢复执行后补齐 |

**纯局域网方案无法通用保证被系统停止的 App 仍在联网瞬间同步。** 两个休眠 Android 的后台任务也不保证同时运行；手机↔平板即时验收要求双方前台或主动限时会话。不能以小组件常驻显示推断进程常驻。

Android 15+ 后台 dataSync 前台服务有总时长限制；不虚报服务类型绕过。周期 WorkManager 至少 15 分钟且非精确定时，不承诺每 15 分钟完成。Android 17 且 targetSdk≥37 时处理 ACCESS_LOCAL_NETWORK 运行时权限；当前 targetSdk=36。无互联网 Wi-Fi 也必须可同步，不能要求互联网验证成功。

## 6. 技术与两端接入

保留当前工具链，本次不升级依赖，也不将历史构建记录当成本次验证。

| 层 | 方向 |
| --- | --- |
| UI | Kotlin、Compose、Material 3 Adaptive |
| 组件 | Glance 响应尺寸，提交后 updateAll 或定向更新 |
| 数据 | Room 存任务、日志、冲突、同步状态；DataStore 存设备与组件设置 |
| 协议 | 按作者递增的操作日志，因果向量、幂等合并、版本协商 |
| 发现 | Android NsdManager；Windows DNS-SD 实现于 P0 选型验证 |
| 传输 | 标准 TLS socket，Node tls 与 Android SSL socket；逐对固定身份 |
| 调度 | Android 生命周期、短任务、WorkManager；Windows 主进程管理连接 |
| 凭据 | Android Keystore、Windows safeStorage/系统凭据 |

Android 划分 model、data、calendar、designsystem、sync、tasks、week、widget、settings 边界；先有清晰包边界，按实际需要拆 Gradle 模块。

Windows 增加 src/main/sync/，在 src/main/index.cjs 接入生命周期，通过 IPC 暴露状态和设置。网络不得直接覆盖 data.json，渲染进程不得开放端口。所有写入进入统一提交入口；本机窗口三方合并后仅记录真实差异，远端操作应用后广播窗口，保留来源防止回声。

## 7. 导入导出与迁移

| 操作 | 规则 |
| --- | --- |
| data.json 导出 | 兼容业务格式，不含私钥与配对凭据；不代表完整同步备份 |
| 合并导入 | 预览新增、修改、ID 冲突；确认后生成普通操作 |
| 覆盖导入 | 备份并提示会传播删除；转换为当前组的增删改，保留墓碑和日志 |
| 独立数据集 | 使用新 groupId，重新配对；旧组操作不能跨组应用 |
| 完整备份 | 单独版本化格式，含日志、冲突、墓碑与校验；默认排除凭据 |
| 恢复安装 | 新 actorId，不克隆身份和 seq；重新配对 |

旧程序不认识同步历史，data.json 往返只保证业务数据兼容。旧版修改后的文件必须显式导入，不能把文件 revision 当作跨设备全局版本。

## 8. 阶段与验收

| 阶段 | 交付 | 验收门槛 |
| --- | --- | --- |
| P0 风险竖切片 | 手机/平板透明周历、Wallpaper Engine 共存；两平台 TLS、发现、Android 直连、无外网、后台行为、桌面日志原型 | 截图和互通记录齐全，冻结 wire schema 与依赖 |
| P1 本地闭环 | Room、编辑、清单、共同规则样例、导入导出和备份 | 重启不丢任务，真实格式往返，非法导入不污染数据 |
| P2 同步正确性 | 日志、幂等、并发冲突、墓碑、续传、桌面统一提交 | 离线/乱序/重复/崩溃测试通过 |
| P3 自动连接 | 配对向导、发现、重连、手动兜底、状态与后台调度 | 任意两端同步，电脑关闭后手机与平板互通 |
| P4 日常界面 | App 自适应周历、响应式组件、保存与同步反馈 | 横竖屏、缩放、大字号、动态壁纸共存通过 |
| P5 发布准备 | APK、升级迁移、权限拒绝、Windows 防火墙、回归 | 安装升级可恢复，断联不影响本地管理 |
| 后续 | 年/月/日视图、可选 AI 与文档提取、App 内拖拽 | 单独验收，不阻塞上述闭环 |

旧规划的固定人日估算失效；P0 实测后重估。功能和发布构建在实现阶段运行，本轮只校验文档。

关键验收：

1. 三端可增删改、完成和恢复；离线分别编辑后重连最终收敛，不丢新增。
2. 受控普通局域网、双方可执行、少量改动：已连接目标 5 秒内更新对端及组件；首次发现目标 10 秒。此为测试目标，不是锁屏后台 SLA。
3. 不同字段自动合并；同字段并发保留候选；永久删除不因旧设备回归而复活。
4. 换 IP 和可互通网络无需重新配对；隔离网络、权限拒绝和执行受限状态可解释。
5. 电脑关闭时两 Android 可双向发起及接受连接；无互联网可用。
6. 组件勾选先本地成功，再显示待同步/已确认状态，不把联网当保存前提。

## 9. 官方依据

- [App Widgets 尺寸与交互](https://developer.android.com/develop/ui/views/appwidgets/overview)
- [Glance 状态与刷新](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)
- [NSD / DNS-SD](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)
- [前台服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [WorkManager 周期与约束](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Node TLS](https://nodejs.org/api/tls.html)、[Android SSLServerSocket](https://developer.android.com/reference/javax/net/ssl/SSLServerSocket)
