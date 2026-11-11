package com.deskweek.android.core.ai

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * 本地文档文字提取（纯 JVM，无 Android 依赖，单元测试可直接跑）。
 *
 * 支持：txt / md / json / csv / xml / yaml / log / rtf / docx / xlsx / pptx / odt。
 * PDF 和图片需要 Android 的 PdfRenderer/Bitmap，由 [DocumentReader] 处理。
 * 旧版 .doc 是二进制格式，这里明确返回 null，由界面提示另存为 DOCX 或 PDF。
 */
data class ExtractedDocument(
    val name: String,
    val text: String = "",
    val images: List<String> = emptyList(),
    val truncated: Boolean = false,
)

object DocumentTextExtractor {

    const val MAX_CHARS = 60_000
    const val MAX_FILE_BYTES = 20 * 1024 * 1024

    private val textExtensions = setOf("txt", "md", "markdown", "json", "xml", "yaml", "yml", "log", "csv")
    private val supportedExtensions = textExtensions + setOf("rtf", "docx", "xlsx", "xlsm", "pptx", "odt")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isTextDocument(name: String): Boolean = extensionOf(name) in supportedExtensions

    /** 返回 null 表示该格式不在这里处理（PDF/图片/旧版 doc）。 */
    fun extract(name: String, bytes: ByteArray, maxChars: Int = MAX_CHARS): String? {
        if (bytes.isEmpty()) throw IllegalArgumentException("文件是空的")
        if (bytes.size > MAX_FILE_BYTES) throw IllegalArgumentException("文件不能超过 20MB")
        val text = when (extensionOf(name)) {
            in textExtensions -> decodeText(bytes)
            "rtf" -> extractRtf(decodeText(bytes))
            "docx" -> extractDocx(bytes)
            "xlsx", "xlsm" -> extractXlsx(bytes)
            "pptx" -> extractPptx(bytes)
            "odt" -> extractOdt(bytes)
            else -> return null
        }.let(::normalize)
        if (text.isBlank()) throw IllegalStateException("没有从文件中提取到文字")
        return text.take(maxChars)
    }

    fun normalize(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\u0000", "")
        .replace("\uFEFF", "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{4,}"), "\n\n\n")
        .trim()

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        // 部分 Windows 导出的 txt/csv 是 GBK，UTF-8 解不开时退回 GB18030。
        return runCatching { String(bytes, Charset.forName("GB18030")) }.getOrDefault(utf8)
    }

    private fun numericSuffix(name: String, token: String): Int =
        Regex("$token(\\d+)\\.xml$").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun extractDocx(bytes: ByteArray): String {
        val entries = zipEntries(bytes)
        val document = entries["word/document.xml"] ?: throw IllegalStateException("不是有效的 DOCX 文件")
        return xmlToText(String(document, Charsets.UTF_8))
    }

    private fun extractPptx(bytes: ByteArray): String {
        val entries = zipEntries(bytes)
        val slides = entries.keys
            .filter { Regex("^ppt/slides/slide\\d+\\.xml$").matches(it) }
            .sortedBy { numericSuffix(it, "slide") }
        if (slides.isEmpty()) throw IllegalStateException("不是有效的 PPTX 文件")
        return slides.mapIndexed { index, key ->
            val text = xmlToText(String(entries.getValue(key), Charsets.UTF_8))
            if (text.isBlank()) "" else "第 ${index + 1} 页\n$text"
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun extractOdt(bytes: ByteArray): String {
        val entries = zipEntries(bytes)
        val content = entries["content.xml"] ?: throw IllegalStateException("不是有效的 ODT 文件")
        return xmlToText(String(content, Charsets.UTF_8))
    }

    private fun extractXlsx(bytes: ByteArray): String {
        val entries = zipEntries(bytes)
        val shared = entries["xl/sharedStrings.xml"]?.let { sharedStrings(String(it, Charsets.UTF_8)) } ?: emptyList()
        val sheets = entries.keys
            .filter { Regex("^xl/worksheets/sheet\\d+\\.xml$").matches(it) }
            .sortedBy { numericSuffix(it, "sheet") }
        if (sheets.isEmpty()) throw IllegalStateException("不是有效的 XLSX 文件")
        return sheets.mapIndexed { index, key ->
            val grid = sheetRows(String(entries.getValue(key), Charsets.UTF_8), shared)
            if (grid.isBlank()) "" else "工作表 ${index + 1}\n$grid"
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun sharedStrings(xml: String): List<String> {
        val item = Regex("<si\\b[^>]*>(.*?)</si>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val text = Regex("<t\\b[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return item.findAll(xml).map { match ->
            text.findAll(match.groupValues[1]).joinToString("") { decodeXmlEntities(it.groupValues[1]) }
        }.toList()
    }

    private fun sheetRows(xml: String, shared: List<String>): String {
        val rows = Regex("<row\\b[^>]*>(.*?)</row>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val cells = Regex("<c\\b([^>]*)>(.*?)</c>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val valueTag = Regex("<v\\b[^>]*>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val inlineTag = Regex("<is\\b[^>]*>(.*?)</is>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val textTag = Regex("<t\\b[^>]*>(.*?)</t>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return rows.findAll(xml).map { row ->
            cells.findAll(row.groupValues[1]).mapNotNull { cell ->
                val attributes = cell.groupValues[1]
                val body = cell.groupValues[2]
                val type = Regex("\\bt=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)
                val raw = valueTag.find(body)?.groupValues?.get(1)
                val inline = inlineTag.find(body)?.let { inlineMatch ->
                    textTag.findAll(inlineMatch.groupValues[1]).joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                }
                val value = when {
                    !inline.isNullOrBlank() -> inline
                    raw == null -> ""
                    type == "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    type == "b" -> if (raw.trim() == "1") "是" else "否"
                    else -> decodeXmlEntities(raw)
                }
                value.trim()
            }.filter { it.isNotEmpty() }.joinToString("\t")
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun xmlToText(xml: String): String {
        val withBreaks = xml
            .replace(Regex("</(?:w:p|a:p|text:p|text:h|row|si)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(?:w:tc|a:tc|text:cell)>", RegexOption.IGNORE_CASE), "\t")
        val plain = withBreaks.replace(Regex("<[^>]+>"), " ")
        return decodeXmlEntities(plain)
    }

    private fun extractRtf(text: String): String = text
        .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ")
        .replace(Regex("[{}]"), " ")

    private fun decodeXmlEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { match ->
            runCatching { String(Character.toChars(match.groupValues[1].toInt())) }.getOrDefault(" ")
        }
        .replace("&amp;", "&")
}
