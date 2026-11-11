package com.deskweek.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskweek.android.core.ai.ApiKeyStorage
import com.deskweek.android.core.ai.DocumentReader
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.data.TaskStore
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器上的 AI 文件导入：选 DOCX → 本地提取 → 本地 mock OpenAI → 勾选导入。
 * 验证文件文字确实进入请求体，且导入后任务落到真实 JSON 存储。
 */
@RunWith(AndroidJUnit4::class)
class AiAttachmentUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun reset() {
        ui.runOnIdle {
            val dir = File(ui.activity.cacheDir, "ai-functional-${System.nanoTime()}")
            TaskRepository.initWithStore(TaskStore(dir))
        }
        ui.activity.getSharedPreferences("deskweek_ai", 0).edit().clear().commit()
    }

    private fun docx(name: String, text: String): File {
        val dir = File(ui.activity.cacheDir, "qa-documents").apply { mkdirs() }
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun textPdf(name: String, text: String): File {
        val dir = File(ui.activity.cacheDir, "qa-documents").apply { mkdirs() }
        val file = File(dir, name)
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val content = "BT /F1 18 Tf 20 100 Td ($escaped) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val output = ByteArrayOutputStream()
        output.write("%PDF-1.4\n".toByteArray())
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, body ->
            offsets += output.size()
            output.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray())
        }
        val xref = output.size()
        output.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { offset ->
            output.write(String.format("%010d 00000 n \n", offset).toByteArray())
        }
        output.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        file.writeBytes(output.toByteArray())
        return file
    }

    @Test
    fun textPdfIsParsedLocallyInsteadOfRequiringVision() {
        val file = textPdf("任务通知.pdf", "PDF task text for the schedule")
        val uri = FileProvider.getUriForFile(ui.activity, ui.activity.packageName + ".qa-files", file)
        val document = DocumentReader.read(ui.activity, uri, "任务通知.pdf")
        assertTrue("PDF 文字应本地提取：${document.text}", document.text.contains("PDF task text"))
        assertTrue("文字版 PDF 不应退化成图片", document.images.isEmpty())
    }

    @Test
    fun picksDocxParsesLocallyAndImportsDraft() {
        val server = FakeOpenAiServer("""[{"title":"文档里的任务","date":"2026-12-01"}]""")
        try {
            val context = ui.activity
            context.getSharedPreferences("deskweek_ai", 0).edit()
                .putString("baseUrl", "http://127.0.0.1:${server.port}/v1")
                .putString("model", "test-model")
                .commit()
            ApiKeyStorage(context).write("test-only-key")
            val file = docx("计划.docx", "周五前提交课程报告")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".qa-files", file)
            Intents.init()
            try {
                Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                    .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
                ui.onNodeWithText("设置").performClick()
                ui.onNodeWithText("添加文件").performScrollTo().performClick()
                ui.waitUntil(10_000) {
                    ui.onAllNodesWithText("计划.docx", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                ui.onNodeWithText("AI 拆解").performScrollTo().performClick()
                ui.waitUntil(15_000) {
                    ui.onAllNodesWithText("文档里的任务").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue("请求体应包含 DOCX 提取出的文字", server.lastBody.contains("周五前提交课程报告"))
                ui.onNodeWithText("导入选中（1）").performScrollTo().performClick()
                ui.waitForIdle()
                val task = TaskRepository.state.value.items.firstOrNull { it.title == "文档里的任务" }
                assertTrue("导入后应有任务", task != null)
                assertTrue("日期应保留", task!!.date == "2026-12-01")
                val stored = TaskStore(TaskRepository.storeFile().parentFile!!).load().data.items
                assertTrue("任务应写入 data.json", stored.any { it.title == "文档里的任务" })
            } finally {
                Intents.release()
            }
        } finally {
            server.close()
        }
    }
}

/** 最小 OpenAI 兼容服务器；只接受一次请求，记录请求体，便于断言文件内容已发出。 */
private class FakeOpenAiServer(content: String) : Closeable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    @Volatile
    var lastBody: String = ""
        private set

    private val response = """{"choices":[{"message":{"role":"assistant","content":${escape(content)}}}]}""".toByteArray()

    private val worker = Thread {
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val header = readHeader(input)
                    val length = Regex("(?i)content-length:\\s*(\\d+)").find(header)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val body = readBytes(input, length)
                    lastBody = body.toString(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response)
                        flush()
                    }
                }
            } catch (_: Exception) {
                if (server.isClosed) return@Thread
            }
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        runCatching { server.close() }
    }

    private fun readHeader(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) break
            output.write(value)
            matched = when {
                (matched == 0 || matched == 2) && value == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && value == '\n'.code -> matched + 1
                else -> 0
            }
        }
        return output.toString("UTF-8")
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return bytes.copyOf(read)
    }

    private companion object {
        fun escape(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
