package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * P2 签名回归：与 JS 侧共用 `signed-op.json`。
 * 只要两端有一边的签名编码/规范化/曲线选择漂移，这里就会失败。
 */
class SyncSignatureTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("signed-op.json")
            ?: error("signed-op.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun fixtureIdentity(): SyncIdentity = SyncIdentity.import(
        actorId = "vector-actor",
        privateKeyB64 = fixture["privateKeyB64"]!!.jsonPrimitive.content,
        publicKeyB64 = fixture["publicKeyB64"]!!.jsonPrimitive.content,
    )

    @Test
    fun `shared vector verifies with the committed public key`() {
        val ok = SyncIdentity.verify(
            fixture["op"]!!.jsonObject,
            fixture["publicKeyB64"]!!.jsonPrimitive.content,
            fixture["signature"]!!.jsonPrimitive.content,
        )
        assertTrue("JS 生成的签名必须能被 Kotlin 验证", ok)
    }

    @Test
    fun `tampered operation fails verification`() {
        val op = fixture["op"]!!.jsonObject
        val publicKey = fixture["publicKeyB64"]!!.jsonPrimitive.content
        val signature = fixture["signature"]!!.jsonPrimitive.content

        val tamperedTitle = buildJsonObject {
            op.forEach { (k, v) -> if (k != "changes") put(k, v) }
            put("changes", buildJsonObject {
                put("title", "被篡改的标题")
                put("done", false)
                put("defer", "3d")
            })
        }
        assertFalse(SyncIdentity.verify(tamperedTitle, publicKey, signature))

        val tamperedSeq = buildJsonObject {
            op.forEach { (k, v) -> if (k != "seq") put(k, v) }
            put("seq", 2)
        }
        assertFalse(SyncIdentity.verify(tamperedSeq, publicKey, signature))
    }

    @Test
    fun `signature from another key fails verification`() {
        val other = SyncIdentity.generate()
        val signature = other.sign(fixture["op"]!!.jsonObject)
        assertFalse(
            SyncIdentity.verify(
                fixture["op"]!!.jsonObject,
                fixture["publicKeyB64"]!!.jsonPrimitive.content,
                signature,
            ),
        )
    }

    @Test
    fun `kotlin signs and verifies its own operation`() {
        val identity = SyncIdentity.generate()
        val op = buildJsonObject {
            put("protocolVersion", 1)
            put("groupId", "p0-group")
            put("opId", "${identity.actorId}:1")
            put("actorId", identity.actorId)
            put("seq", 1)
            put("context", buildJsonObject { })
            put("taskId", "t-1")
            put("kind", "create")
            put("changes", buildJsonObject { put("title", "Kotlin 生成的签名") })
            put("recordedAt", "2026-11-10T02:00:00Z")
        }
        val signed = identity.signed(op)
        val signature = signed["signature"]!!.jsonPrimitive.content
        assertTrue(SyncIdentity.verify(op, identity.publicKeyB64, signature))
        assertTrue(identity.selfCheck())
    }

    @Test
    fun `identity file round trips and rejects corrupted content`() {
        val dir = Files.createTempDirectory("sync-identity").toFile()
        val first = SyncIdentity.loadOrCreate(dir)
        val second = SyncIdentity.loadOrCreate(dir)
        assertEquals("同一目录必须复用同一身份", first.actorId, second.actorId)
        assertEquals(first.publicKeyB64, second.publicKeyB64)

        val file = java.io.File(dir, "sync-identity.json")
        file.writeText("""{"actorId":"x","privateKeyB64":"bad","publicKeyB64":"bad"}""")
        val regenerated = SyncIdentity.loadOrCreate(dir)
        assertNotEquals("损坏的身份文件必须重建", "x", regenerated.actorId)
    }
}
