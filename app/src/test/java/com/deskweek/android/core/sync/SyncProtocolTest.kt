package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * 协议 P0：与 JS 侧共用 `sync-vectors.json`。
 * 只要两端有一边的规范化实现漂移，这里就会失败。
 */
class SyncProtocolTest {

    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("sync-vectors.json")
            ?: error("sync-vectors.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `canonical json matches cross-language vectors`() {
        val list = vectors["vectors"] as kotlinx.serialization.json.JsonArray
        assertEquals(11, list.size)

        for (element in list) {
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            val canonical = CanonicalJson.canonicalize(vector["input"]!!)
            assertEquals("canonical mismatch: $name", vector["canonical"]!!.jsonPrimitive.content, canonical)
            assertEquals("sha256 mismatch: $name", vector["sha256"]!!.jsonPrimitive.content, sha256(canonical))
        }
    }

    @Test
    fun `canonical key order is utf16 code unit order`() {
        val input = Json.parseToJsonElement("""{"a":1,"Z":2,"0":3}""")
        assertEquals("""{"0":3,"Z":2,"a":1}""", CanonicalJson.canonicalize(input))
    }

    @Test
    fun `canonical rejects non integer and unpaired surrogate`() {
        val decimal = Json.parseToJsonElement("""{"n":1.5}""")
        try {
            CanonicalJson.canonicalize(decimal)
            fail("decimal must be rejected")
        } catch (e: ProtocolException) {
            assertEquals("ECONTRACT", e.code)
        }

        val lone = Json.parseToJsonElement("""{"s":"\ud800"}""")
        try {
            CanonicalJson.canonicalize(lone)
            fail("unpaired surrogate must be rejected")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("unpaired"))
        }
    }

    @Test
    fun `frame round trips multiple messages with partial tail`() {
        val ping = Json.parseToJsonElement("""{"type":"PING","protocolVersion":1,"nonce":"1"}""").jsonObject
        val pong = Json.parseToJsonElement("""{"type":"PONG","protocolVersion":1,"nonce":"2"}""").jsonObject

        val all = FrameCodec.encode(ping) + FrameCodec.encode(pong) + byteArrayOf(0, 0)
        val first = FrameCodec.decode(all)
        assertEquals(2, first.messages.size)
        assertEquals("PING", first.messages[0]["type"]!!.jsonPrimitive.content)
        assertEquals(2, first.rest.size)

        val headerOnly = FrameCodec.decode(byteArrayOf(0, 0, 0, 20))
        assertEquals(0, headerOnly.messages.size)
    }

    @Test
    fun `frame rejects empty and oversized frames`() {
        try {
            FrameCodec.decode(ByteArray(4))
            fail("zero length frame must be rejected")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("empty frame"))
        }

        val huge = byteArrayOf(0x00, 0x20, 0x00, 0x00) // 2 MiB
        try {
            FrameCodec.decode(huge)
            fail("oversized frame must be rejected")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `valid messages pass`() {
        SyncMessages.validate(
            obj("""{"type":"HELLO","protocolVersion":1,"groupId":"g","actorId":"a","nonce":"n","capabilities":[]}"""),
        )
        SyncMessages.validate(
            obj(
                """{"type":"OPS","protocolVersion":1,"ops":[{"protocolVersion":1,"groupId":"g","opId":"a:1",
                   "actorId":"a","seq":1,"context":{},"taskId":"t","kind":"create",
                   "changes":{"title":"x","done":false},"recordedAt":"2026-11-10T02:00:00Z","signature":"sig"}]}""",
            ),
        )
        SyncMessages.validate(obj("""{"type":"ACK","protocolVersion":1,"vectors":{"a":1}}"""))
    }

    @Test
    fun `invalid messages are rejected with protocol codes`() {
        val cases = listOf(
            """{"type":"NOPE","protocolVersion":1}""" to "ECONTRACT",
            """{"type":"HELLO","protocolVersion":2,"groupId":"g","actorId":"a","nonce":"n","capabilities":[]}""" to "EPROTO",
            """{"type":"HELLO","protocolVersion":1,"groupId":"","actorId":"a","nonce":"n","capabilities":[]}""" to "ECONTRACT",
            """{"type":"OPS","protocolVersion":1,"ops":[]}""" to "ECONTRACT",
            """{"type":"WANT","protocolVersion":1,"actorId":"a","from":5,"to":4}""" to "ECONTRACT",
            """{"type":"ACK","protocolVersion":1,"vectors":{"a":-1}}""" to "ECONTRACT",
        )
        for ((json, code) in cases) {
            try {
                SyncMessages.validate(obj(json))
                fail("expected failure for $json")
            } catch (e: ProtocolException) {
                assertEquals("wrong code for $json", code, e.code)
            }
        }
    }

    @Test
    fun `ops enforce opId equals actorId and seq`() {
        val op = obj(
            """{"type":"OPS","protocolVersion":1,"ops":[{"protocolVersion":1,"groupId":"g","opId":"a:9",
               "actorId":"a","seq":1,"context":{},"taskId":"t","kind":"patch",
               "changes":{"title":"x"},"recordedAt":"2026-11-10T02:00:00Z","signature":"sig"}]}""",
        )
        try {
            SyncMessages.validate(op)
            fail("mismatched opId must be rejected")
        } catch (e: ProtocolException) {
            assertEquals("ECONTRACT", e.code)
            assertTrue(e.message!!.contains("opId"))
        }
    }
}
