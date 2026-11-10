package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 配对：短认证码跨语言一致 + 信任表语义。
 */
class PairingTest {

    private val fixture: JsonArray by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("pairing.json")
            ?: error("pairing.json missing")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject["vectors"] as JsonArray
    }

    @Test
    fun `short auth string matches the js implementation`() {
        assertEquals(5, fixture.size)
        for (element in fixture) {
            val vector = element.jsonObject
            val a = vector["a"]!!.jsonPrimitive.content
            val b = vector["b"]!!.jsonPrimitive.content
            val expected = vector["sas"]!!.jsonPrimitive.content
            assertEquals("sas mismatch", expected, TrustStore.shortAuthString(a, b))
            // 顺序无关
            assertEquals("sas must be symmetric", expected, TrustStore.shortAuthString(b, a))
        }
    }

    @Test
    fun `short auth string is six digits`() {
        val sas = TrustStore.shortAuthString("AAA", "BBB")
        assertEquals(6, sas.length)
        assertTrue(sas.all { it.isDigit() })
    }

    @Test
    fun `trust store remembers and rejects changed keys`() {
        val dir = Files.createTempDirectory("trust").toFile()
        val store = TrustStore(dir)

        assertFalse(store.isTrusted("peer-1", "key-a"))

        store.remember(TrustStore.Peer("peer-1", "key-a", "g", "2026-11-10T00:00:00Z", "123456"))
        assertTrue(store.isTrusted("peer-1", "key-a"))
        assertFalse("同一 actor 换公钥必须拒绝", store.isTrusted("peer-1", "key-b"))
    }

    @Test
    fun `trust store persists across instances`() {
        val dir = Files.createTempDirectory("trust-persist").toFile()
        TrustStore(dir).remember(TrustStore.Peer("peer-2", "key-c", "g", "2026-11-10T00:00:00Z", "654321"))

        val reloaded = TrustStore(dir)
        assertEquals(1, reloaded.all().size)
        assertTrue(reloaded.isTrusted("peer-2", "key-c"))

        reloaded.forget("peer-2")
        assertTrue(TrustStore(dir).all().isEmpty())
    }

    @Test
    fun `trust store survives a corrupted file`() {
        val dir = Files.createTempDirectory("trust-bad").toFile()
        java.io.File(dir, "trusted-peers.json").writeText("{not json")
        val store = TrustStore(dir)
        assertTrue(store.all().isEmpty())
        store.remember(TrustStore.Peer("peer-3", "key-d", "g", "now", "000000"))
        assertNotEquals(0, TrustStore(dir).all().size)
    }
}
