package com.deskweek.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deskweek.android.core.ai.AiClient
import com.deskweek.android.core.ai.AiConfig
import com.deskweek.android.core.ai.ApiKeyStorage
import com.deskweek.android.core.ai.DocumentReader
import com.deskweek.android.core.ai.ExtractedDocument
import com.deskweek.android.core.ai.TaskDraft
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 总结：把文字和本地文件拆成待办草稿，用户勾选后入库。
 *
 * 文件在手机本地解析：文本/Office 文档提取文字，PDF 渲染成页面图片，
 * 图片直接作为视觉输入；只把提取结果发给你配置的 OpenAI 兼容接口。
 */
@Composable
fun AiSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("deskweek_ai", android.content.Context.MODE_PRIVATE) }
    val keyStorage = remember { ApiKeyStorage(context) }
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "") }
    var apiKey by remember { mutableStateOf(runCatching { keyStorage.read() }.getOrDefault("")) }
    var source by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ExtractedDocument>>(emptyList()) }
    var drafts by remember { mutableStateOf<List<TaskDraft>>(emptyList()) }
    var selectedIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var readingFiles by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun saveConfig() {
        prefs.edit().putString("baseUrl", baseUrl).putString("model", model).apply()
        runCatching { keyStorage.write(apiKey) }.onFailure { status = "密钥保存失败，请重试" }
    }

    val configured = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
    val hasMaterial = source.isNotBlank() || attachments.isNotEmpty()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                readingFiles = true
                val next = attachments.toMutableList()
                val errors = mutableListOf<String>()
                for (uri in uris) {
                    if (next.size >= MAX_FILES) {
                        errors += "最多添加 $MAX_FILES 个文件"
                        break
                    }
                    val name = DocumentReader.displayName(context, uri) ?: "未命名文件"
                    runCatching { withContext(Dispatchers.IO) { DocumentReader.read(context, uri, name) } }
                        .onSuccess { document ->
                            val imageCount = next.sumOf { it.images.size }
                            if (document.images.isNotEmpty() && imageCount + document.images.size > AiClient.MAX_IMAGES) {
                                errors += "$name：图片和 PDF 页面合计最多 ${AiClient.MAX_IMAGES} 张"
                            } else {
                                next += document
                            }
                        }
                        .onFailure { errors += "$name：${it.message ?: "解析失败"}" }
                }
                attachments = next
                readingFiles = false
                status = errors.joinToString("；")
            }
        }
    }

    fun removeAttachment(target: ExtractedDocument) {
        attachments = attachments.filterNot { it === target }
    }

    fun analyze() {
        if (busy) return
        status = "整理中…"
        busy = true
        drafts = emptyList()
        selectedIndexes = emptySet()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiClient().extract(AiConfig(baseUrl.trim(), model.trim(), apiKey), source, attachments) }
            }
            result.onSuccess { list ->
                drafts = list
                selectedIndexes = list.indices.toSet()
                status = if (list.isEmpty()) "没有提取到待办" else "提取到 ${list.size} 条，勾选后导入"
            }.onFailure { status = "失败：${it.message}" }
            busy = false
        }
    }

    fun importSelected() {
        val chosen = selectedIndexes.sorted().mapNotNull { drafts.getOrNull(it) }
        if (chosen.isEmpty()) return
        runCatching {
            TaskRepository.addAll(chosen.map { draft ->
                Task(id = "", title = draft.title, date = draft.date, time = draft.time, detail = draft.detail)
            })
        }.onSuccess {
            drafts = drafts.filterIndexed { index, _ -> index !in selectedIndexes }
            selectedIndexes = emptySet()
            status = "已导入 ${chosen.size} 条"
        }.onFailure { status = "导入失败，草稿已保留：${it.message}" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI 总结", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; saveConfig() },
            label = { Text("接口地址（OpenAI 兼容）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saveConfig() },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saveConfig() },
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("粘贴待整理的资料（可选）") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy && !readingFiles, onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Text(if (readingFiles) "读取中…" else "添加文件")
            }
            Text(
                "支持 PDF、Word、Excel、PPT、文本、图片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        attachments.forEach { document ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val meta = when {
                    document.text.isNotBlank() -> "· ${document.name} · ${document.text.length} 字"
                    document.images.isNotEmpty() -> "· ${document.name} · ${document.images.size} 张图片/页面"
                    else -> "· ${document.name}"
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { removeAttachment(document) }) { Text("移除") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && !readingFiles && configured && hasMaterial,
                onClick = { analyze() },
            ) { Text(if (busy) "整理中…" else "AI 拆解") }
            if (drafts.isNotEmpty()) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        selectedIndexes = if (selectedIndexes.size == drafts.size) emptySet() else drafts.indices.toSet()
                    },
                ) { Text(if (selectedIndexes.size == drafts.size) "取消全选" else "全选") }
                Button(enabled = !busy && selectedIndexes.isNotEmpty(), onClick = { importSelected() }) {
                    Text("导入选中（${selectedIndexes.size}）")
                }
            }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        drafts.forEachIndexed { index, draft ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = index in selectedIndexes,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        selectedIndexes = if (checked) selectedIndexes + index else selectedIndexes - index
                    },
                )
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(draft.title, style = MaterialTheme.typography.bodyMedium)
                    val meta = listOf(draft.date, draft.time).filter { it.isNotBlank() }.joinToString("  ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (draft.detail.isNotBlank()) {
                        Text(
                            draft.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 3,
                        )
                    }
                }
            }
        }
        Text(
            "与桌面版相同：文件在本机解析，只调用你配置的接口；不配置时本地功能不受影响。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private const val MAX_FILES = 5
