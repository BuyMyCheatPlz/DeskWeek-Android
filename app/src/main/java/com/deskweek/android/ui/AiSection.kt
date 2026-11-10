package com.deskweek.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deskweek.android.core.ai.AiClient
import com.deskweek.android.core.ai.AiConfig
import com.deskweek.android.core.ai.TaskDraft
import com.deskweek.android.core.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 总结：把一段资料拆成待办草稿，用户勾选后入库。
 *
 * 与桌面版一样只依赖 OpenAI 兼容接口；不配置时整段功能不可用，
 * 不影响本地任务管理。
 */
@Composable
fun AiSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("deskweek_ai", android.content.Context.MODE_PRIVATE) }
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("apiKey", "") ?: "") }
    var source by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf<List<TaskDraft>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun save() {
        prefs.edit().putString("baseUrl", baseUrl).putString("model", model).putString("apiKey", apiKey).apply()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI 总结", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; save() },
            label = { Text("接口地址（OpenAI 兼容）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; save() },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; save() },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("粘贴待整理的资料") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = source.isNotBlank() && apiKey.isNotBlank(),
                onClick = {
                    status = "整理中…"
                    drafts = emptyList()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                AiClient().extract(AiConfig(baseUrl, model, apiKey), source)
                            }
                        }
                        result.onSuccess {
                            drafts = it
                            status = if (it.isEmpty()) "没有提取到待办" else "提取到 ${it.size} 条，点「全部添加」入库"
                        }.onFailure {
                            status = "失败：${it.message}"
                        }
                    }
                },
            ) { Text("AI 拆解") }

            OutlinedButton(
                enabled = drafts.isNotEmpty(),
                onClick = {
                    drafts.forEach { draft ->
                        TaskRepository.add(
                            title = draft.title,
                            date = draft.date,
                            time = draft.time,
                            detail = draft.detail,
                        )
                    }
                    status = "已添加 ${drafts.size} 条"
                    drafts = emptyList()
                },
            ) { Text("全部添加") }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        drafts.forEach { draft ->
            Text(
                "· ${draft.title}${if (draft.date.isNotBlank()) "  " + draft.date else ""}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            "与桌面版相同：只调用你配置的接口；不配置时本地功能不受影响。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
