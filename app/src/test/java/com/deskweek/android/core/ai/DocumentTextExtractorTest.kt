package com.deskweek.android.core.ai

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextExtractorTest {

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun `extracts docx paragraphs and decodes entities`() {
        val bytes = zip(mapOf(
            "word/document.xml" to """
                <w:document><w:body>
                  <w:p><w:r><w:t>提交课程报告</w:t></w:r></w:p>
                  <w:p><w:r><w:t>截止：周五 17:00 &amp; 不要迟到</w:t></w:r></w:p>
                </w:body></w:document>
            """.trimIndent(),
        ))
        val text = DocumentTextExtractor.extract("通知.docx", bytes)!!
        assertTrue(text.contains("提交课程报告"))
        assertTrue(text.contains("截止：周五 17:00 & 不要迟到"))
    }

    @Test fun `extracts pptx slides in numeric order`() {
        val bytes = zip(mapOf(
            "ppt/slides/slide2.xml" to "<p:sld><a:t>第二页</a:t></p:sld>",
            "ppt/slides/slide1.xml" to "<p:sld><a:t>第一页</a:t></p:sld>",
        ))
        val text = DocumentTextExtractor.extract("答辩.pptx", bytes)!!
        assertTrue("text=$text", text.indexOf("第一页") < text.indexOf("第二页"))
        assertTrue("text=$text", text.contains("第 1 页"))
    }

    @Test fun `extracts xlsx shared strings and inline cells`() {
        val bytes = zip(mapOf(
            "xl/sharedStrings.xml" to "<sst><si><t>任务名称</t></si><si><t>截止日期</t></si></sst>",
            "xl/worksheets/sheet1.xml" to """
                <worksheet><sheetData>
                  <row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row>
                  <row><c t="inlineStr"><is><t>交报告</t></is></c><c><v>2026-11-12</v></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
        ))
        val text = DocumentTextExtractor.extract("计划.xlsx", bytes)!!
        assertTrue(text.contains("任务名称\t截止日期"))
        assertTrue(text.contains("交报告\t2026-11-12"))
    }

    @Test fun `extracts odt and rtf`() {
        val odt = zip(mapOf("content.xml" to "<text:p>第一段</text:p><text:p>第二段</text:p>"))
        assertTrue(DocumentTextExtractor.extract("说明.odt", odt)!!.contains("第一段"))
        val rtf = """{\rtf1\ansi 会议通知\par 周五交材料}""".toByteArray()
        assertTrue(DocumentTextExtractor.extract("通知.rtf", rtf)!!.contains("会议通知"))
    }

    @Test fun `decodes utf8 bom and gbk text`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "中文资料".toByteArray()
        assertEquals("中文资料", DocumentTextExtractor.extract("a.txt", utf8Bom))
        val gbk = "中文资料".toByteArray(charset("GB18030"))
        assertEquals("中文资料", DocumentTextExtractor.extract("b.txt", gbk))
    }

    @Test fun `returns null for pdf and legacy doc so the reader can handle them`() {
        assertNull(DocumentTextExtractor.extract("a.pdf", "x".toByteArray()))
        assertNull(DocumentTextExtractor.extract("a.doc", "x".toByteArray()))
    }

    @Test fun `truncates long documents and rejects empty content`() {
        val text = DocumentTextExtractor.extract("长文.txt", "字".repeat(500).toByteArray(), maxChars = 100)!!
        assertEquals(100, text.length)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            DocumentTextExtractor.extract("空.docx", zip(mapOf("word/document.xml" to "<w:document></w:document>")))
        }
    }

    @Test fun `rejects oversized files`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DocumentTextExtractor.extract("big.txt", ByteArray(DocumentTextExtractor.MAX_FILE_BYTES + 1))
        }
    }
}
