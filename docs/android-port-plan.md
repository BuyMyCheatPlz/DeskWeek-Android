# DeskWeek Android 实现规划

> 基线：`EasonGuuu/Deskweek` v1.3.0（`0ca4b0d`），本地参考副本 `C:\Users\Enjory Wong\deepweek-andriod`
> 本仓库：`BuyMyCheatPlz/DeskWeek-Android`（public，从上游 fork 的 `main` 起步）
> 周期：约 **16 人日**（不含工具链安装）

---

## 0. 已定决策

| # | 决策 | 结论 |
| --- | --- | --- |
| 1 | 技术路线 | Kotlin + Jetpack Compose 原生重写，不复用 Electron 前端 |
| 2 | 仓库形态 | 独立仓库 `DeskWeek-Android`（public），与桌面版不共享代码 |
| 3 | v1 范围 | 周视图 + 任务 CRUD + 本地存储 + 主屏小组件 + 导入导出 + AI 总结 |
| 4 | 数据互通 | 无云同步，只做 `data.json` 导入导出 |
| 5 | 视觉 | 沿用桌面 6 色板与固定深浅主题，不启用 Material You 动态取色 |
| 6 | 通知 | v1 不做系统到期通知（桌面版也没有，只有 defer「提前进清单」） |
| 7 | minSdk | 26（Android 8.0+），`targetSdk 36` |
| 8 | 工具链 | 用户自行安装 Android Studio；CI 负责可重复构建 |

---

## 1. 桌面版能力盘点（源码通读结论）

**形态**：Electron 44 + `koffi` 直调 Win32，把浮窗挂到桌面壁纸层（在普通窗口**之下**、图标之上）。两个渲染进程：

| 进程 | 角色 | 源码 |
| --- | --- | --- |
| `widget` | 贴在桌面上的周历浮窗：展示 + 轻交互（打勾、翻页、记一条、AI 总结） | 1.6k 行 |
| `manager` | 管理中心：待办（未完成/已完成/垃圾箱）、日历（年/月/周/日）、设置 | 1.1k 行 |

**核心判断**：这个产品 60% 的价值在「始终在桌面上可见」，不在代码。前端无法复用，但**数据格式与业务规则必须逐条对齐**。

### 数据模型（`data/data.json`）

```jsonc
{
  "initialized": true,
  "items": [{
    "id": "uuid",
    "title": "≤100 字",
    "detail": "≤4000 字",
    "date": "YYYY-MM-DD",     // 可空
    "time": "HH:mm",          // 可空
    "done": false,
    "completedAt": "ISO",     // done 时必有
    "deleted": false,
    "deletedAt": "ISO",
    "color": "#9bd3c7",       // 6 色板，按当日任务数轮转
    "defer": "none",          // "none" | "1m" | "3d"
    "createdAt": "ISO"
  }],
  "prefs": {},
  "revision": 12,
  "updatedAt": "ISO"
}
```

`prefs` 实际使用的键：`theme`(`light|dark`)、`widgetBackground`(`normal|transparent`)、`calendarMode`、`showCalendar`、`showTasks`、`showDone`、`taskPanelHeight`、`v`。**未知键必须原样保留**。

**必须原样复刻的规则**：

- **defer 可见性**：`none` 立即显示；`Nd` 从 `date - N 天` 起；`1m` 从 `date - 1 个月`（月末夹紧）起。未到期在「待办」隐藏，但**日历视图中始终可见**（半透明 + 挂起标记）。
- **排序**：rank 0 未完成且过期 → rank 1 无日期（按 `createdAt`）→ rank 2 有日期（按 `dateTtime`，同刻按 `createdAt`）；已完成按 `completedAt` 倒序。
- **颜色轮转**：同日第 N 个任务取 `COLORS[N % 6]` = `#9bd3c7 #e7bd82 #f39aa4 #90b8ee #c8a6ef #a7d48d`。
- **持久化**：桌面端临时文件 + `fsync` + rename 原子替换并留 `.backup`；Android 用 Room（WAL）天然满足。
- **日历信息**：农历/节气/法定节假日/调休/国际节日，来自 `vendor/lunar/lunar.js`（6tail, MIT）。

---

## 2. 形态转译：桌面浮窗 → 主屏小组件

| 路线 | 对等度 | 代价 |
| --- | --- | --- |
| **A. Glance App Widget**（选定） | 高：常驻主屏、无需权限、不遮挡其他应用 | 信息密度受尺寸限制 |
| B. `SYSTEM_ALERT_WINDOW` 悬浮窗 | 最高 | 权限劝退、遮挡其他应用、Play 风险，且与桌面版"在应用之下"的定位相反 |
| C. 常驻通知 | 低 | 打扰 |

尺寸分档：

| 尺寸 | 内容 |
| --- | --- |
| 2×2 / 4×2 | 今日日期 + 农历 + 3 条待办（可勾选） |
| 4×4 | 整周 7 列：日期、农历/节气、每日 2–3 条 |
| 5×4+ / 平板 | 整周 7 列 + 选中日期的任务列表 |
| 透明背景 | 沿用 `prefs.widgetBackground`，`transparent` 时去掉底板与阴影 |

主程序承担原 `manager` 的全部职责。

---

## 3. 技术选型

| 层 | 选型 | 备注 |
| --- | --- | --- |
| 语言/UI | Kotlin 2.1 + Compose（BOM）+ Material 3 | Edge-to-edge |
| 自适应 | `material3-adaptive`：`currentWindowAdaptiveInfo()`、`ListDetailPaneScaffold` | 手机/平板/折叠一套代码 |
| 导航 | `navigation-compose` 类型安全路由 | 单 Activity |
| 持久化 | Room 2.7（KSP）+ DataStore | Room 存任务，DataStore 存 prefs 与 `rawJson` |
| 序列化 | `kotlinx-serialization-json` | 与 `data.json` 双向兼容 |
| DI | Hilt（KSP） | |
| 小组件 | Glance 1.1 + WorkManager | 数据变更主动刷新 + 30 min 兜底 |
| 历法 | `cn.6tail:lunar`（Java 版，同作者 MIT） | 需与 `lunar.js` 逐日对比 |
| 网络 | OkHttp + kotlinx-serialization | AI 接口 |
| PDF 解析 | `com.tom-roush:pdfbox-android` | |
| DOCX/PPTX/XLSX 解析 | **ZIP + XmlPullParser 自写抽取（不引 POI）** | POI 在 Android 有 dex/体积坑；Office 格式本质是 ZIP+XML，自写约 400 行 |
| 图片 OCR | ML Kit Text Recognition v2（离线中文） | 包体 +约 10 MB |
| 密钥存储 | Android Keystore AES-GCM，密文存 DataStore | 等价桌面版 `data/ai-key.dat`(DPAPI)；`androidx.security.crypto` 已弃用，不用 |
| 构建 | AGP 8.7 + Gradle 8.11，`minSdk 26` / `targetSdk 36` | |

---

## 4. 工程结构

```
DeskWeek-Android/
├─ settings.gradle.kts
├─ gradle/libs.versions.toml
├─ app/                        组装：Activity、导航、主题、Hilt 装配、分享入口
├─ core/
│  ├─ model/                   Task、Prefs、Defer、TaskColor
│  ├─ data/                    Room、DAO、Repository、data.json 编解码
│  ├─ designsystem/            6 色板、字体、任务行/周列/勾选组件
│  ├─ calendar/                农历、节气、节假日、周计算
│  └─ ai/                      OpenAI 兼容客户端、文档抽取、密钥加密
├─ feature/
│  ├─ tasks/                   清单三态 + 编辑面板
│  ├─ week/                    周视图（手机 3 列 / 平板 7 列）
│  ├─ ai/                      AI 总结面板 + 建议列表 + 批量入库
│  ├─ settings/                主题、透明、导入导出、AI 配置
│  └─ widget/                  Glance 三种尺寸 + 刷新调度
└─ sync/                       data.json 导入导出
```

---

## 5. 任务拆解与验收（合计约 16 人日）

### P0 工程初始化（1d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| A0 | Android Studio 安装 JDK 21 + SDK 36（用户自装） | `./gradlew --version` 通过 |
| A1 | 建 Gradle 多模块工程、version catalog、插件 | `./gradlew assembleDebug` 通过 |
| A2 | 模块依赖方向约束（feature → core，feature 之间禁止互依） | `./gradlew projects` 正确 |
| A3 | CI：`lint` + `test` + `assembleDebug` | PR 绿灯 |

### P1 数据层与桌面版兼容（2d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| B1 | `Task`/`Prefs`/`Defer` + `data.json` 序列化 | 单测：读真实 `data.json` fixture，字段零丢失；未知 prefs 键保留 |
| B2 | Room 建表、DAO、迁移占位 | 增删改查/软删除测试通过 |
| B3 | `TaskRepository`(`Flow<List<Task>>`) + `PrefsRepository`(DataStore) | 写入后 Flow 立即发射 |
| B4 | defer 可见性、排序、颜色轮转纯函数 | 单测覆盖 `none/1m/3d/月末夹紧/过期/无日期` |

**B1 最先做**：字段语义偏了，后面全部返工。

### P2 自适应外壳与清单（2d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| C1 | 单 Activity + 自适应 Scaffold：<600dp 底部三 Tab；600–840dp NavigationRail；≥840dp ListDetail | 三档截图无破版 |
| C2 | 折叠屏半折态（`FoldingFeature`）上下分区 | 模拟器验证 |
| C3 | 清单页三态 + 侧栏计数 | 排序与桌面版一致 |
| C4 | 任务行组件（色点、勾选、日期/时间、defer 标签、过期标红） | 视觉对齐 |
| C5 | 编辑面板（标题/备忘/日期/时间/defer/颜色），新建与修改复用 | 校验同桌面版（100/4000 字） |

### P3 周视图（3d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| D1 | 周计算：ISO 周（周一起）、翻周、今日判定 | 跨年周单测 |
| D2 | 平板密度：7 列整周 | 800dp 下可读 |
| D3 | 手机密度：横向 3 列 `HorizontalPager` | 360dp 下标题可用 |
| D4 | 拖拽调天：手机长按拖到目标列；平板跨列 | 落点高亮、松手写库、失败回滚 |
| D5 | 日历信息叠加（农历/节气/节假日/调休） | 与桌面版同日一致 |

D4 需自绘 `pointerInput`（Compose 无现成跨列拖放），预算 1.5 天。

### P4 AI 总结（4d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| E1 | OpenAI 兼容客户端（baseUrl/model/key，`/chat/completions`，JSON 模式） | 与桌面版相同配置可跑通 |
| E2 | 文本类抽取：TXT/MD/CSV/JSON/XML | 中文不乱码 |
| E3 | Office 抽取：DOCX/PPTX/XLSX（ZIP+XmlPullParser） | 三类文件各出 3+ 条待办 |
| E4 | PDF 抽取（PDFBox-Android）+ 20MB 上限 | 超限友好报错 |
| E5 | 图片 OCR（ML Kit 离线中文） | 截图可识别 |
| E6 | 分享入口：文本/图片/文件从其他 App 分享进来 | 分享后直达 AI 面板 |
| E7 | 建议面板：解析结果逐条勾选、批量入库（对应桌面版 proposal 流） | 只插入勾选项 |
| E8 | 密钥加密（Keystore AES-GCM）+ 首次启用隐私确认 | 重启后仍可用，明文不落盘 |

### P5 小组件（2d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| F1 | Glance 三种尺寸，共用 Room 数据 | 主屏渲染正确 |
| F2 | 点击勾选 + 打开主程序 | 状态双向同步 |
| F3 | 透明模式 + 深浅主题 | 与 `prefs.widgetBackground` 联动 |
| F4 | 刷新：变更即 `updateAll`，WorkManager 兜底 | 冷启动 1 分钟内刷新 |

### P6 导入导出（1d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| G1 | 导出 `data.json`（结构/`revision`/`updatedAt` 一致） | 可直接替换桌面版 `data/data.json` 启动 |
| G2 | 导入：SAF 选文件 → 校验 → 合并/覆盖 | 非法文件报错且不污染现有数据 |

### P7 打磨与发布（1.5d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| H1 | 深色主题、Edge-to-edge、insets | 三档无遮挡 |
| H2 | 无障碍：TalkBack、字号 2.0 不破版 | Scanner 无阻断项 |
| H3 | 真机矩阵回归 | 见 §6 |
| H4 | 签名 release APK | 可安装 |

---

## 6. 测试策略

- **JVM 单测**：defer 可见性、排序、颜色轮转、`data.json` round-trip（用桌面版真实数据做 fixture）。
- **历法对比**：`lunar.js` 与 `cn.6tail:lunar` 对 2020–2030 逐日比对，差异必须为 0。
- **Compose 截图回归**：Paparazzi 覆盖 360/411/800/1280dp。
- **真机矩阵**：小屏 360dp、常规 411dp、平板 800dp、1280dp、折叠屏（展开 + 半折）。

---

## 7. 风险与对策

| # | 风险 | 影响 | 对策 |
| --- | --- | --- | --- |
| 1 | 小组件装不下原浮窗信息密度 | 核心体验打折 | 尺寸分档；完整周历留在主程序 |
| 2 | `data.json` 兼容层做错 | 导入导出报废 | P1 第一件事 + 真实 fixture round-trip |
| 3 | 跨列拖拽无现成组件 | P3 超期 | 兜底「长按选日 + 弹层改期」，拖拽作增强 |
| 4 | 自写 Office 抽取器覆盖不全 | AI 导入失败 | 先支持 Word/PDF，XLSX/PPTX 降级为"仅文本" |
| 5 | ML Kit 增包体约 10MB | 安装包变大 | App Bundle 按 ABI/语言拆分 |
| 6 | 农历口径漂移 | 日历信息错 | 逐日对比测试（§6） |

---

## 8. 待确认

1. **AI 默认状态**：桌面版默认关闭、需手填配置；Android 是否同样默认关闭、首次启用弹隐私确认？（当前按"默认关闭"设计）
2. **仓库是否要 CI**：GitHub Actions 构建 APK 产物，还是只跑 lint/test？
