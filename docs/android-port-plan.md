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

## 2. 形态转译：桌面浮窗 → 动态壁纸（**主形态，已改**）

**产品要求**：和桌面版一样，"贴在桌面上、图标压在它上面"。

关键结论：**Glance 小组件做不到这件事**。小组件占的是桌面网格的格子，图标只能排在它旁边，永远不会压在它上面。能做到"图标压在文字上"的只有**动态壁纸（Live Wallpaper）**——壁纸层在图标层之下，这与桌面版挂在壁纸上的原理完全一致。

| 方案 | 图标能否压在内容上 | 交互 | 代价 |
| --- | --- | --- | --- |
| **A. 动态壁纸 `WallpaperService`（主，选定）** | **能** | 点击/双击（`Engine.onTouchEvent`） | 会替换系统壁纸，需内置"选图作底"；Canvas 手绘，不能直接用 Compose |
| B. Glance 小组件（辅，可选） | 不能 | 原生点击、可勾选 | 占格子，只能放入口和今日摘要 |
| C. `SYSTEM_ALERT_WINDOW` 悬浮窗 | 能，但会遮挡其他应用 | 完整 | 权限弹窗劝退、Play 风险、与桌面版"永不遮挡"定位相反 |

### 2.1 双形态分工

| 形态 | 职责 | 对应桌面版 |
| --- | --- | --- |
| 动态壁纸 | 常驻可见的周历/日历文字，图标压在其上 | `widget` 浮窗 |
| 主程序 | 录入、编辑、设置、AI、导入导出 | `manager` 管理中心 |
| 小组件（可选，二期） | 桌面上的"今日 3 条"入口 | 无 |

### 2.2 动态壁纸实现要点

- `WallpaperService` + `Engine.lockCanvas()` 手绘，**不复用 Compose**；配色/字号抽成与主程序共享的 design token，两套渲染保证一致。
- **底图**：用户选一张本地图片（或纯色/纯透明）作底，我们把它画上去再叠日历文字。桌面版是"不替换系统壁纸"，Android 壁纸服务天然抢占壁纸位，用"选图作底"还原体验。
- **位置与避让**：提供九宫格对齐 + 水平/垂直偏移 + 缩放，让文字落在图标稀疏的一侧（这是桌面版没有的、Android 特有的需要）。
- **刷新**：不能常驻渲染（耗电）。`onVisibilityChanged` 可见时订阅 Room 数据，数据变更或跨天时重绘一次；不可见时释放。
- **交互**：单击任务行 → 切换完成；双击空白 → 打开主程序对应日期；长按 → 无（壁纸层手势会与 Launcher 冲突）。
- **透明/玻璃**：沿用桌面版 `prefs.widgetBackground`，`transparent` 时只画文字。

### 2.3 风险（已确认要提前测）

部分国产 Launcher（MIUI / EMUI / ColorOS）对第三方动态壁纸支持不完整，可能表现为：设置页找不到、切换壁纸后被重置、深色模式下自动压暗。**这是本方案最大的落地风险，P0 阶段就要拿真机验证，不能留到最后。**

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
| **壁纸渲染** | `WallpaperService` + `Canvas`（`lockCanvas`），`onVisibilityChanged` 控制订阅 | 手绘，不能复用 Compose；与主程序共享 design token |
| 小组件（可选） | Glance 1.2 + WorkManager | 二期；仅今日摘要与入口 |
| 历法 | `cn.6tail:lunar`（Java 版，同作者 MIT） | 需与 `lunar.js` 逐日对比 |
| 网络 | OkHttp + kotlinx-serialization | AI 接口 |
| PDF 解析 | `com.tom-roush:pdfbox-android` | |
| DOCX/PPTX/XLSX 解析 | **ZIP + XmlPullParser 自写抽取（不引 POI）** | POI 在 Android 有 dex/体积坑；Office 格式本质是 ZIP+XML，自写约 400 行 |
| 图片 OCR | ML Kit Text Recognition v2（离线中文） | 包体 +约 10 MB |
| 密钥存储 | Android Keystore AES-GCM，密文存 DataStore | 等价桌面版 `data/ai-key.dat`(DPAPI)；`androidx.security.crypto` 已弃用，不用 |
| 构建 | **已实测**：Gradle 9.9.0 · AGP 9.5.1 · Kotlin 2.4.21（AGP 内置）· JDK 21 · compileSdk 37 / targetSdk 36 / minSdk 26 | 见 §3.1 |

### 3.1 实测记录（本机已验证，`assembleDebug` 通过）

装环境时踩了五个坑，记下来避免重复：

| # | 现象 | 原因 | 解决 |
| --- | --- | --- | --- |
| 1 | JDK 下载只有 70 KB/s | Adoptium API 307 重定向到 GitHub Releases | 换清华镜像 `mirrors.tuna.tsinghua.edu.cn/Adoptium`（3.6 MB/s） |
| 2 | `sdkmanager "platforms;android-36"` 静默无效 | 新版 cmdline-tools(23.0.0) 包名改为斜杠 | `platforms/android-36`、`build-tools/36.0.0` |
| 3 | `The 'org.jetbrains.kotlin.android' plugin is no longer required since AGP 9.0` | AGP 9 内置 Kotlin 支持 | 删掉该插件，只保留 `kotlin-compose` |
| 4 | `core-ktx:1.19.1 requires compileSdk 37` | 2026 年 androidx 基线已升到 37 | 装 `platforms/android-37.2`，`compileSdk = 37` |
| 5 | `gradle wrapper` 报 `Test of distribution url failed` | `services.gradle.org` 307 超时 | `--no-validate-url`，或换腾讯镜像 |

> Android SDK 平台从 37 起改为「主.次」版本命名（`android-37.0` / `37.2`），不是 `android-37`。

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
│  ├─ week/                    周视图（Compose，主程序内）
│  ├─ wallpaper/               WallpaperService + Canvas 周历渲染 + 底图/对齐设置
│  ├─ ai/                      AI 总结面板 + 建议列表 + 批量入库
│  ├─ settings/                主题、透明、导入导出、AI 配置、壁纸外观
│  └─ widget/                  Glance（二期）
└─ sync/                       data.json 导入导出
```

---

## 5. 任务拆解与验收（合计约 17.5 人日，不含可选小组件）

### P0 工程初始化（1d）

| ID | 任务 | 验收 |
| --- | --- | --- |
| A0 | Android Studio 安装 JDK 21 + SDK 36（用户自装） | `./gradlew --version` 通过 |
| A1 | 用 Android Studio 向导生成最小工程（保证 Gradle/AGP/Kotlin/KSP 版本自洽），再拆多模块 + version catalog | `./gradlew assembleDebug` 通过 |
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

### P5 动态壁纸（3.5d）— 主形态，最高优先级

| ID | 任务 | 验收 |
| --- | --- | --- |
| F0 | **真机验证可行性**：在目标机型（含一台国产 ROM）装一个空 `WallpaperService`，确认能设置、能常驻、不被系统重置 | 三台真机均能选中并保持 |
| F1 | `WallpaperService` + `Engine` 骨架，`onVisibilityChanged` 驱动订阅 | 可见时订阅、不可见时释放，无泄漏 |
| F2 | Canvas 渲染器：周历 7 列、日期/农历/节气/任务文字、6 色板 | 与主程序同一日期渲染一致 |
| F3 | 底图：选本地图片/纯色/透明，按屏幕裁切填充 | 切换后立即生效 |
| F4 | 布局设置：九宫格对齐、偏移、缩放、字号、显示项开关 | 平板横竖屏都不越界 |
| F5 | 刷新：Room 变更、跨天 00:00、时区/语言变更触发重绘 | 改一条任务，壁纸 2 秒内更新 |
| F6 | 触摸：单击任务切完成、双击空白打开主程序对应日期 | 不与 Launcher 手势冲突 |
| F7 | 耗电与内存：静态画面不重复绘制，锁屏/不可见时零 CPU | 待机 8 小时耗电 < 1% |

### P5b 小组件（可选，1d，二期）

| ID | 任务 | 验收 |
| --- | --- | --- |
| F8 | Glance 今日摘要（2×2 / 4×2），点击进主程序 | 主屏渲染正确 |

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
| 1 | **国产 ROM 对第三方动态壁纸支持不完整** | 主形态可能直接不可用 | F0 前置验证；准备降级路径：退化为"大尺寸透明小组件" |
| 2 | 动态壁纸抢占系统壁纸位 | 用户原壁纸丢失 | 内置"选本地图片作底"，设置页明确提示 |
| 3 | 壁纸层手绘与 Compose 两套渲染 | 视觉漂移、维护成本 | 抽共享 design token（字号/间距/颜色），P5 与 P3 用同一份 token |
| 4 | 壁纸常驻影响续航 | 用户卸载 | 静态不重绘、不可见零 CPU；F7 量化验收 |
| 5 | `data.json` 兼容层做错 | 导入导出报废 | P1 第一件事 + 真实 fixture round-trip |
| 6 | 跨列拖拽无现成组件 | P3 超期 | 兜底「长按选日 + 弹层改期」，拖拽作增强 |
| 7 | 自写 Office 抽取器覆盖不全 | AI 导入失败 | 先支持 Word/PDF，XLSX/PPTX 降级为"仅文本" |
| 8 | ML Kit 增包体约 10MB | 安装包变大 | App Bundle 按 ABI/语言拆分 |
| 9 | 农历口径漂移 | 日历信息错 | 逐日对比测试（§6） |

---

## 8. 待确认

1. **AI 默认状态**：桌面版默认关闭、需手填配置；Android 是否同样默认关闭、首次启用弹隐私确认？（当前按"默认关闭"设计）
2. **仓库是否要 CI**：GitHub Actions 构建 APK 产物，还是只跑 lint/test？
