# DeskWeek Android v2.2.0 · AI 文件导入与逐条勾选

> 2026-11-11

设置页的 AI 功能现在支持直接添加文件，和桌面版一样把资料拆成待办后导入。

## 新增

- **文件导入**：支持 PDF、Word（docx）、Excel（xlsx/xlsm）、PowerPoint（pptx）、ODT、RTF、txt/md/json/csv/xml/yaml/log，以及 png/jpg/webp/gif 图片。
- **本地解析**：文件在手机上读取；文字型 PDF 用 PDFBox 本地抽取文字，扫描版 PDF 才渲染成页面图片交给视觉模型。
- **逐条勾选**：拆出的待办可以只选其中几条导入，未选中的草稿会保留。
- **导入反馈**：成功提示“已导入 N 条”；失败保留草稿并显示原因。

## 限制

- 最多 5 个文件，单个不超过 20MB；单文件最多 6 万字；PDF 最多渲染 4 页；图片和 PDF 页面合计最多 4 张。
- 旧版 `.doc` 暂不支持，请另存为 DOCX 或 PDF。
- 扫描版 PDF 需要支持视觉的模型；纯文本模型可处理文字型 PDF、Office 文档和文本文件。

## 验证

- Android 单元测试 112 项全部通过，其中新增 `DocumentTextExtractorTest` 8 项、`AiClientTest` 附件/包装对象用例。
- `:app:lintDebug`、`:app:assembleDebug` 通过。
- 模拟器 `deskweek_tablet` 上 28 项仪器测试全部通过，其中新增 `AiAttachmentUiTest` 2 项：
  - 选 DOCX → 本地提取 → 本地 mock OpenAI → 勾选导入 → 写入 `data.json`
  - 文字型 PDF 本地提取，不退化成分页图片
- `scripts/gray-check.ps1`：4 项通过、0 项失败、1 项跳过（无设备数据可读）。

详见 [AI 文件导入说明](ai-file-import.md)。
