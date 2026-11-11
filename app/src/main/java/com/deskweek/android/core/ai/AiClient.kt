package com.deskweek.android.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.deskweek.android.core.model.TaskRules

/** AI 配置，与桌面端「管理中心 → 设置 → AI 总结」字段对应 */
data class AiConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
) {
    val configured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

/** 解析出来的待办草稿，进建议列表由用户勾选后再入库 */
data class TaskDraft(
    val title: String,
    val date: String = "",
    val time: String = "",
    val detail: String = "",
)

/** 便于在单测里替换掉真实网络 */
interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): String
}

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): String {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${text.take(200)}")
            }
            return text
        }
    }
}

/**
 * OpenAI 兼容的「把一段资料拆成待办」客户端。
 *
 * 不依赖任何厂商 SDK，只要接口支持 `/chat/completions` 即可。
 * 解析失败、返回空数组、模型返回非 JSON 都会抛带原因的异常，由 UI 展示。
 */
class AiClient(private val transport: HttpTransport = OkHttpTransport()) {

    fun extract(
        config: AiConfig,
        text: String,
        attachments: List<ExtractedDocument> = emptyList(),
    ): List<TaskDraft> {
        require(config.configured) { "AI 未配置" }
        val fileText = attachments
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n---\n\n") { "文件：${it.name}\n${it.text.take(DocumentTextExtractor.MAX_CHARS)}" }
        val material = buildList {
            if (text.isNotBlank()) add(text.trim())
            if (fileText.isNotBlank()) add("以下是用户添加的文件内容，请结合这些内容提取待办：\n$fileText")
        }.joinToString("\n\n").take(SOURCE_MAX_CHARS)
        val images = attachments.flatMap { it.images }.take(MAX_IMAGES)
        require(material.isNotBlank() || images.isNotEmpty()) { "内容为空" }
        val contentText = material.ifBlank { "请整理图片或 PDF 页面中的任务信息。" }

        val content = if (images.isEmpty()) {
            JsonPrimitive(contentText)
        } else {
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", contentText)
                })
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", image) })
                    })
                }
            }
        }

        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", 0)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
        }.toString()

        val raw = transport.post(
            url = config.baseUrl.trimEnd('/') + "/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json",
            ),
            body = body,
        )

        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val response = choice?.get("message") as? JsonObject
        val responseContent = when (val value = response?.get("content")) {
            is JsonPrimitive -> value.takeIf { it.isString }?.content
            is JsonArray -> value.joinToString("") { part ->
                ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            }
            else -> null
        }?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("接口未返回可用的待办内容")

        return parseDrafts(responseContent)
    }

    internal fun parseDrafts(content: String): List<TaskDraft> {
        val json = stripFences(content)
        // kotlinx 解析器会把裸文本当成 JSON 字符串，这里显式挡掉非 JSON 回复
        if (!json.startsWith("[") && !json.startsWith("{")) {
            throw IllegalStateException("模型没有返回合法 JSON")
        }
        val element = runCatching { Json.parseToJsonElement(json) }
            .getOrElse { throw IllegalStateException("模型没有返回合法 JSON") }
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["items"] as? JsonArray)
                ?: (element["tasks"] as? JsonArray)
                ?: throw IllegalStateException("模型返回的不是数组")
            else -> throw IllegalStateException("模型返回的不是数组")
        }
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val title = text("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            TaskDraft(
                title = title.take(100),
                date = text("date").takeIf { TaskRules.isValidDateKey(it) }.orEmpty(),
                time = text("time").takeIf { TaskRules.isValidTime(it) }.orEmpty(),
                detail = text("detail").take(4000),
            )
        }
    }

    /** 兼容 ```json ... ``` 包裹 */
    private fun stripFences(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    companion object {
        const val MAX_IMAGES = 4
        const val SOURCE_MAX_CHARS = 24_000
        private const val SYSTEM_PROMPT =
            "你是待办提取器。把用户给的文字、聊天记录，以及用户添加的文档或 PDF 页面图片，整理成待办事项。" +
                "只输出 JSON 数组，每项包含 title（必填，中文，不超过 100 字）、date（YYYY-MM-DD，可空）、" +
                "time（HH:mm，可空）、detail（可空）。不要输出解释或 Markdown。"
    }
}
