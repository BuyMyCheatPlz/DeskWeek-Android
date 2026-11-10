package com.deskweek.android.core.sync

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncStoreTest {
    private fun dir() = Files.createTempDirectory("deskweek-sync-test").toFile()
    private fun op(group: String = "g", seq: Int = 1) = buildJsonObject {
        put("protocolVersion", 1); put("groupId", group); put("actorId", "A")
        put("seq", seq); put("opId", "A:$seq"); put("taskId", "t")
        put("kind", if (seq == 1) "create" else "patch")
        put("changes", buildJsonObject { put("title", "task-$seq") })
        put("context", buildJsonObject { if (seq > 1) put("A", seq - 1) })
        put("recordedAt", "2026-11-10T00:00:00Z"); put("signature", "test")
    }
    @Test fun `wrong group is rejected without poisoning persistent history`() {
        val store = SyncStore(dir(), "g")
        assertThrows(ProtocolException::class.java) { store.receive(listOf(op("wrong"))) }
        assertTrue(store.allOps().isEmpty())
    }
    @Test fun `same identity with different content is rejected`() {
        val store = SyncStore(dir(), "g")
        store.receive(listOf(op()))
        val changed = JsonObject(op() + ("changes" to buildJsonObject { put("title", "tampered") }))
        assertThrows(ProtocolException::class.java) { store.receive(listOf(changed)) }
    }
    @Test fun `corrupt committed history fails closed and is not overwritten`() {
        val path = dir()
        java.io.File(path, "sync-ops.json").writeText("broken")
        assertThrows(IllegalStateException::class.java) { SyncStore(path, "g") }
        assertEquals("broken", java.io.File(path, "sync-ops.json").readText())
    }
    @Test fun `persistence failure leaves vectors and memory unchanged`() {
        val path = dir()
        val store = SyncStore(path, "g")
        java.io.File(path, "sync-ops.json.tmp").mkdir()
        assertThrows(Exception::class.java) { store.receive(listOf(op())) }
        assertTrue(store.vectors().isEmpty())
        assertTrue(store.allOps().isEmpty())
    }
    @Test fun `persisted history is scoped to its group`() {
        val path = dir()
        SyncStore(path, "g").receive(listOf(op()))
        assertThrows(IllegalStateException::class.java) { SyncStore(path, "other") }
    }
    @Test fun `multiple instances merge durable histories instead of overwriting one another`() {
        val path = dir()
        val first = SyncStore(path, "g")
        val second = SyncStore(path, "g")
        first.receive(listOf(op()))
        second.receive(listOf(op(seq = 2)))
        assertEquals(2, SyncStore(path, "g").allOps().size)
        assertEquals(2L, first.vectors()["A"])
    }
}
