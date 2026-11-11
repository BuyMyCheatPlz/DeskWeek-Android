package com.deskweek.android.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android 文件读取：文本/Office 文档交给 [DocumentTextExtractor] 在本地解析；
 * PDF 用系统 PdfRenderer 渲染成页面图片，和用户添加的截图一样交给支持视觉的
 * OpenAI 兼容模型。所有解析都在本机完成，只有提取后的文字和页面图片会发出去。
 */
object DocumentReader {

    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    const val MAX_PDF_PAGES = 4
    private const val PDF_TEXT_MIN_CHARS = 5

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif")

    fun displayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    fun read(context: Context, uri: Uri, fallbackName: String = ""): ExtractedDocument {
        val name = displayName(context, uri) ?: fallbackName.ifBlank { "未命名文件" }
        val extension = DocumentTextExtractor.extensionOf(name)
        return when {
            extension == "pdf" -> readPdf(context, uri, name)
            extension in imageExtensions -> readImage(context, uri, name, extension)
            DocumentTextExtractor.isTextDocument(name) -> {
                val bytes = readBytes(context, uri, MAX_FILE_BYTES)
                val text = DocumentTextExtractor.extract(name, bytes)
                    ?: throw IllegalArgumentException("暂不支持 .$extension 文件，请另存为 DOCX 或 PDF")
                ExtractedDocument(name = name, text = text, truncated = text.length >= DocumentTextExtractor.MAX_CHARS)
            }
            else -> throw IllegalArgumentException("暂不支持 .${extension.ifBlank { "未知" }} 文件；支持 PDF、Word、Excel、PPT、文本和图片")
        }
    }

    private fun readImage(context: Context, uri: Uri, name: String, extension: String): ExtractedDocument {
        val bytes = readBytes(context, uri, MAX_IMAGE_BYTES)
        val mime = when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        return ExtractedDocument(name = name, images = listOf(dataUrl))
    }

    private fun readPdf(context: Context, uri: Uri, name: String): ExtractedDocument {
        val temp = File.createTempFile("deskweek-ai-", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取 PDF")
            input.use { stream ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) throw IllegalArgumentException("文件不能超过 20MB")
                        output.write(buffer, 0, read)
                    }
                }
            }
            // 文字版 PDF 优先本地抽取文字，这样纯文本模型（如 DeepSeek）也能用；
            // 扫描版或抽不出文字时才渲染页面图片，交给支持视觉的模型。
            val text = extractPdfText(context, temp)
            if (text.length >= PDF_TEXT_MIN_CHARS) {
                return ExtractedDocument(name = name, text = text.take(DocumentTextExtractor.MAX_CHARS))
            }
            val images = renderPdfPages(temp)
            if (images.isEmpty()) throw IllegalStateException("没有从 PDF 中提取到文字或页面")
            return ExtractedDocument(name = name, images = images)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun extractPdfText(context: Context, file: File): String = runCatching {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { document ->
            if (document.isEncrypted) return@use ""
            com.tom_roush.pdfbox.text.PDFTextStripper().getText(document)
        }.let(DocumentTextExtractor::normalize)
    }.getOrElse { error ->
        android.util.Log.e("DeskWeekAI", "PDF text extraction failed", error)
        ""
    }

    private fun renderPdfPages(file: File): List<String> {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return emptyList()
                val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                return (0 until pages).mapNotNull { index ->
                    renderer.openPage(index).use { page ->
                        val width = 1400
                        val height = (page.height * (width.toFloat() / page.width.toFloat())).toInt().coerceIn(400, 2400)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val output = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("PDF 解析失败：${error.message ?: "文件可能已加密或损坏"}")
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private fun readBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取文件")
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IllegalArgumentException("文件不能超过 ${maxBytes / (1024 * 1024)}MB")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
