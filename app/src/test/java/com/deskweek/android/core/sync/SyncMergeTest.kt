package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 合并回归：与 JS 侧共用 `merge-scenarios.json`。
 * 同一批操作必须产出逐字节相同的投影。
 */
class SyncMergeTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("merge-scenarios.json")
            ?: error("merge-scenarios.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun op(
        actorId: String,
        seq: Long,
        taskId: String,
        kind: String,
        changes: JsonObject = JsonObject(emptyMap()),
        context: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        put("groupId", "g-merge")
        put("opId", "$actorId:$seq")
        put("actorId", actorId)
        put("seq", seq)
        put("context", context)
        put("taskId", taskId)
        put("kind", kind)
        put("changes", changes)
        put("recordedAt", "2026-11-10T00:00:00Z")
    }

    private fun ctx(seq: Int) = JsonObject(mapOf("A" to kotlinx.serialization.json.JsonPrimitive(seq)))

    private fun changes(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(pairs.toMap())

    @Test
    fun `all scenarios reproduce the committed projection`() {
        val scenarios = fixture["vectors"] as JsonArray
        assertEquals(11, scenarios.size)

        for (element in scenarios) {
            val scenario = element.jsonObject
            val name = scenario["name"]!!.jsonPrimitive.content
            val log = OpLog(scenario["groupId"]!!.jsonPrimitive.content)
            log.receive((scenario["ops"] as JsonArray).map { it.jsonObject })

            assertEquals("canonical mismatch: $name", scenario["expectedCanonical"]!!.jsonPrimitive.content, log.projection.canonical())

            val expectedVectors = (scenario["vectors"] as JsonObject).mapValues { it.value.jsonPrimitive.long }
            assertEquals("vectors mismatch: $name", expectedVectors, log.vectors())
        }
    }

    @Test
    fun `duplicate operations are idempotent`() {
        val log = OpLog("g-merge")
        val create = op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("x")))
        assertEquals(1, log.receive(listOf(create)))
        assertEquals(0, log.receive(listOf(create)))
        assertEquals(1, log.projection.size)
    }

    @Test
    fun `gap blocks later operations until the missing one arrives`() {
        val log = OpLog("g-merge")
        log.receive(listOf(op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("one")))))
        log.receive(listOf(op("A", 3, "t1", "patch", changes("title" to kotlinx.serialization.json.JsonPrimitive("three")))))

        assertEquals(1L, log.vectors()["A"])
        assertEquals("one", firstTitle(log))
        assertEquals(OpLog.Gap(2, 3), log.gaps()["A"])

        log.receive(listOf(op("A", 2, "t1", "patch", changes("title" to kotlinx.serialization.json.JsonPrimitive("two")))))
        assertEquals(3L, log.vectors()["A"])
        assertEquals("three", firstTitle(log))
        assertTrue(log.gaps().isEmpty())
    }

    @Test
    fun `trash wins over concurrent restore and restore after trash works`() {
        val concurrent = OpLog("g-merge")
        concurrent.receive(
            listOf(
                op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("x"))),
                op("A", 2, "t1", "trash", changes("deletedAt" to kotlinx.serialization.json.JsonPrimitive("2026-11-10T01:00:00Z"))),
                op("B", 1, "t1", "restore"),
            ),
        )
        assertEquals(true, firstTask(concurrent)["deleted"]!!.jsonPrimitive.booleanOrNull)

        val ordered = OpLog("g-merge")
        ordered.receive(
            listOf(
                op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("x"))),
                op("A", 2, "t1", "trash", changes("deletedAt" to kotlinx.serialization.json.JsonPrimitive("2026-11-10T01:00:00Z"))),
                op(
                    "A", 3, "t1", "restore",
                    context = JsonObject(mapOf("A" to kotlinx.serialization.json.JsonPrimitive(2))),
                ),
            ),
        )
        assertEquals(false, firstTask(ordered)["deleted"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `purge is terminal`() {
        val log = OpLog("g-merge")
        log.receive(
            listOf(
                op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("x"))),
                op("A", 2, "t1", "purge"),
                op("A", 3, "t1", "patch", changes("title" to kotlinx.serialization.json.JsonPrimitive("复活"))),
                op("B", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("同 id 重建"))),
            ),
        )
        assertEquals(0, log.projection.size)
    }

    @Test
    fun `same field concurrency records a conflict deterministically`() {
        val log = OpLog("g-merge")
        log.receive(
            listOf(
                op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("base"))),
                op("A", 2, "t1", "patch", changes("title" to kotlinx.serialization.json.JsonPrimitive("from A")), ctx(1)),
                op("B", 1, "t1", "patch", changes("title" to kotlinx.serialization.json.JsonPrimitive("from B")), ctx(1)),
            ),
        )
        val snapshot = log.projection.snapshot()
        val task = (snapshot["tasks"] as JsonArray)[0].jsonObject
        assertEquals("from A", task["title"]!!.jsonPrimitive.content)
        val conflicts = snapshot["conflicts"] as JsonArray
        assertEquals(1, conflicts.size)
        val conflict = conflicts[0].jsonObject
        assertEquals("title", conflict["group"]!!.jsonPrimitive.content)
        assertEquals("A", conflict["winner"]!!.jsonPrimitive.content)
    }

    @Test
    fun `same day colors are deterministic by taskId`() {
        val log = OpLog("g-merge")
        log.receive(
            listOf(
                op("A", 1, "t-b", "create", changes(
                    "title" to kotlinx.serialization.json.JsonPrimitive("B"),
                    "date" to kotlinx.serialization.json.JsonPrimitive("2026-11-11"),
                )),
                op("A", 2, "t-a", "create", changes(
                    "title" to kotlinx.serialization.json.JsonPrimitive("A"),
                    "date" to kotlinx.serialization.json.JsonPrimitive("2026-11-11"),
                )),
            ),
        )
        val tasks = log.projection.snapshot()["tasks"] as JsonArray
        assertEquals("t-a", tasks[0].jsonObject["taskId"]!!.jsonPrimitive.content)
        assertEquals("#9bd3c7", tasks[0].jsonObject["color"]!!.jsonPrimitive.content)
        assertEquals("#e7bd82", tasks[1].jsonObject["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nextOp assigns the next contiguous seq`() {
        val log = OpLog("g-merge")
        log.receive(
            listOf(
                op("A", 1, "t1", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("x"))),
                op("B", 1, "t2", "create", changes("title" to kotlinx.serialization.json.JsonPrimitive("y"))),
            ),
        )
        val next = log.nextOp(
            actorId = "A",
            taskId = "t1",
            kind = "patch",
            changes = changes("done" to kotlinx.serialization.json.JsonPrimitive(true)),
            recordedAt = "2026-11-10T00:00:00Z",
        )
        assertEquals(2L, next["seq"]!!.jsonPrimitive.long)
        assertEquals("A:2", next["opId"]!!.jsonPrimitive.content)
    }

    private fun firstTitle(log: OpLog): String =
        firstTask(log)["title"]!!.jsonPrimitive.content

    private fun firstTask(log: OpLog): JsonObject =
        (log.projection.snapshot()["tasks"] as JsonArray)[0].jsonObject
}
