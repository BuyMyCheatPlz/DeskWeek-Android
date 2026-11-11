# Android AI 文件导入

> 2026-11-11 · DeskWeek Android 2.2.0

设置页的“AI 总结”现在可以直接添加文件。文件在手机上本地解析，解析结果和手写文字一起交给兼容 OpenAI 的接口，拆出的待办可以逐条勾选后导入。

## 支持的格式

| 类型 | 格式 | 解析方式 |
| --- | --- | --- |
| 文本 | txt、md、markdown、json、xml、yaml、yml、log、csv | 本地按 UTF-8/UTF-16/GB18030 解码 |
| Word | docx | 解压读取 `word/document.xml` |
| Excel | xlsx、xlsm | 读取共享字符串和工作表单元格 |
| PowerPoint | pptx | 按页码读取幻灯片文字 |
| OpenDocument | odt | 读取 `content.xml` |
| RTF | rtf | 去除 RTF 控制字 |
| PDF | pdf | 先用 PDFBox 本地抽取文字；扫描版才渲染页面图片 |
| 图片 | png、jpg/jpeg、webp、gif | 作为视觉输入发送 |

限制：最多 5 个文件，单个不超过 20MB；单文件最多 6 万字；PDF 最多渲染 4 页；图片和 PDF 页面合计最多 4 张。

## PDF 的两种路径

- **文字型 PDF**：PDFBox 在本机抽取文字，纯文本模型（例如 DeepSeek）也能用，不消耗视觉额度。
- **扫描版/图片型 PDF**：抽不到文字时，用系统 `PdfRenderer` 渲染成页面图片，交给支持视觉的模型。如果模型不支持图片，接口会返回错误，界面会保留文件并显示原因。

## 隐私

解析全部在本机完成，只有提取出的文字和页面/图片会发给你配置的接口。API Key 仍由 Android Keystore 加密保存，界面不显示明文。文件读取使用系统文件选择器，App 不申请存储权限。

> 发布版按 Android 网络安全策略只允许 HTTPS 接口；`http://127.0.0.1` 的明文放行只存在于 debug 测试包，用于自动化测试里的本地 mock 服务。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\gray-check.ps1 -SdkRoot "C:\path\to\sdk" -Device emulator-5554
```

新增的自动化测试：

- `DocumentTextExtractorTest`：docx/xlsx/pptx/odt/rtf/编码/BOM/截断/超大文件。
- `AiClientTest`：附件文字进入请求体、页面图片进入 `image_url`、包装对象与裸数组两种回复。
- `AiAttachmentUiTest`：真实文件选择器选 DOCX → 本地提取 → 本地 mock OpenAI → 勾选导入 → 写入 `data.json`；以及文字型 PDF 本地提取、不退化成分页图片。

## 第三方组件

- [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android)（Apache-2.0）：文字型 PDF 的本地文字提取。
- 页面渲染使用 Android 系统自带的 `PdfRenderer`，不额外引入渲染库。

## 已知限制

- 旧版 `.doc` 是二进制格式，暂不支持，请另存为 DOCX 或 PDF。
- 加密 PDF 由 PDFBox/PdfRenderer 拒绝，界面会提示。
- 扫描版 PDF 需要支持视觉的模型；纯文本模型只能处理文字型 PDF 和 Office/文本文件。
- 极复杂的 Excel 公式、图表和嵌入对象只提取单元格文本，不提取图表内容。
