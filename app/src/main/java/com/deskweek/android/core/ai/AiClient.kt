package com.deskweek.android.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
            val text = response.body?.string() ?: ""
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

    fun extract(config: AiConfig, text: String): List<TaskDraft> {
        require(config.configured) { "AI 未配置" }
        require(text.isNotBlank()) { "内容为空" }

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
                    put("content", text.take(12000))
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

        val content = Json.parseToJsonElement(raw).jsonObject["choices"]!!
            .jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!
            .jsonPrimitive.content

        return parseDrafts(content)
    }

    internal fun parseDrafts(content: String): List<TaskDraft> {
        val json = stripFences(content)
        // kotlinx 解析器会把裸文本当成 JSON 字符串，这里显式挡掉非 JSON 回复
        if (!json.startsWith("[") && !json.startsWith("{")) {
            throw IllegalStateException("模型没有返回合法 JSON")
        }
        val element = runCatching { Json.parseToJsonElement(json) }
            .getOrElse { throw IllegalStateException("模型没有返回合法 JSON") }
        val array = element as? JsonArray ?: throw IllegalStateException("模型返回的不是数组")
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            TaskDraft(
                title = title.take(100),
                date = obj["date"]?.jsonPrimitive?.content.orEmpty().takeIf { it.length == 10 }.orEmpty(),
                time = obj["time"]?.jsonPrimitive?.content.orEmpty().takeIf { it.length == 5 }.orEmpty(),
                detail = obj["detail"]?.jsonPrimitive?.content.orEmpty().take(4000),
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
        private const val SYSTEM_PROMPT =
            "你是待办提取器。把用户给的资料整理成待办事项，只输出 JSON 数组，" +
                "每项包含 title（必填，中文，不超过 100 字）、date（YYYY-MM-DD，可空）、" +
                "time（HH:mm，可空）、detail（可空）。不要输出解释或 Markdown。"
    }
}


