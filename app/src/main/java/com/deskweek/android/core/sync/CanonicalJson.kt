package com.deskweek.android.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 同步协议的规范化 JSON（JCS 子集），必须与 JS 侧 `src/main/sync/canonical.cjs`
 * 逐字节一致；两边共用 `sync-vectors.json` 测试向量。
 *
 * 规则见 `docs/lan-sync-protocol.md` §4：
 * - 对象键按 UTF-16 码元升序，无空白
 * - 仅安全整数（拒绝小数/指数/超范围）
 * - 只转义 " \ 与 C0 控制字符，非 ASCII 原样输出
 * - 拒绝未配对代理项
 */
object CanonicalJson {

    private val SHORT = mapOf(
        '\u0008' to "\\b",
        '\u0009' to "\\t",
        '\u000a' to "\\n",
        '\u000c' to "\\f",
        '\u000d' to "\\r",
    )

    fun canonicalize(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonObject -> {
            val keys = element.keys.sorted()
            keys.joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                quote(key) + ":" + canonicalize(element.getValue(key))
            }
        }
        is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalize(it) }
        is JsonPrimitive -> when {
            element.isString -> quote(element.content)
            element.content == "true" || element.content == "false" -> element.content
            else -> canonicalNumber(element.content)
        }
        else -> throw ProtocolException("ECONTRACT", "unsupported json element")
    }

    private fun canonicalNumber(raw: String): String {
        if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
            throw ProtocolException("ECONTRACT", "only integers are canonical: $raw")
        }
        val value = raw.toLongOrNull()
            ?: throw ProtocolException("ECONTRACT", "unsupported number: $raw")
        requireSafeInteger(value)
        return value.toString()
    }

    private fun requireSafeInteger(value: Long) {
        if (value > MAX_SAFE || value < -MAX_SAFE) {
            throw ProtocolException("ECONTRACT", "unsafe integer: $value")
        }
    }

    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                SHORT.containsKey(c) -> out.append(SHORT.getValue(c))
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                c.isHighSurrogate() -> {
                    val next = if (i + 1 < text.length) text[i + 1] else null
                    if (next == null || !next.isLowSurrogate()) {
                        throw ProtocolException("ECONTRACT", "unpaired high surrogate at $i")
                    }
                    out.append(c).append(next)
                    i++
                }
                c.isLowSurrogate() -> throw ProtocolException("ECONTRACT", "unpaired low surrogate at $i")
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
        return out.toString()
    }

    private const val MAX_SAFE = 9007199254740991L
}
