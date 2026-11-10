package com.deskweek.android.core.data

import com.deskweek.android.core.model.StoreData
import com.deskweek.android.core.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TaskStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("deskweek-test").toFile()

    private fun sample(title: String = "买菜") = Task(
        id = "id-1",
        title = title,
        date = "2026-11-10",
        time = "08:30",
        color = "#e7bd82",
        createdAt = "2026-11-01T00:00:00Z",
    )

    @Test
    fun `save then load round trips all fields`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample())))

        val loaded = store.load()
        assertFalse(loaded.recoveredFromBackup)
        assertEquals(1, loaded.data.items.size)
        assertEquals("买菜", loaded.data.items[0].title)
        assertEquals("08:30", loaded.data.items[0].time)
        assertEquals("#e7bd82", loaded.data.items[0].color)
    }

    @Test
    fun `revision increments on every save`() {
        val store = TaskStore(tempDir())
        val first = store.save(StoreData())
        val second = store.save(first)
        assertEquals(1, first.revision)
        assertEquals(2, second.revision)
        assertTrue(second.updatedAt.isNotBlank())
    }

    @Test
    fun `second save keeps previous version as backup`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample("第一版"))))
        store.save(StoreData(items = listOf(sample("第二版"))))

        assertTrue(store.backupFile.exists())
        val backupText = store.backupFile.readText()
        assertTrue(backupText.contains("第一版"))
    }

    @Test
    fun `corrupted main file recovers from backup`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample("可恢复"))))
        store.save(StoreData(items = listOf(sample("最新"))))

        // 模拟断电写坏主文件
        store.file.writeText("{ this is not json")

        val loaded = store.load()
        assertTrue(loaded.recoveredFromBackup)
        assertEquals("可恢复", loaded.data.items[0].title)
        // 主文件已被修复
        assertTrue(store.file.readText().contains("可恢复"))
        // 损坏文件被另存
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("data.json.corrupt-") })
    }

    @Test
    fun `missing file yields empty uninitialized store`() {
        val store = TaskStore(tempDir())
        val loaded = store.load()
        assertFalse(loaded.data.initialized)
        assertTrue(loaded.data.items.isEmpty())
    }

    @Test
    fun `unparseable file and backup report load error without throwing`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.file.writeText("<<<broken>>>")
        store.backupFile.writeText("<<<also broken>>>")

        val loaded = store.load()
        assertTrue(loaded.data.items.isEmpty())
        assertEquals("unreadable-or-invalid", loaded.loadError)
    }

    @Test
    fun `unknown prefs keys survive a round trip`() {
        val dir = tempDir()
        val prefs = Json.parseToJsonElement(
            """{"theme":"dark","taskPanelHeight":320,"v":3,"futureKey":"keep-me"}""",
        ) as JsonObject

        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample()), prefs = prefs))

        val reloaded = store.load().data.prefs
        assertEquals("dark", reloaded["theme"]!!.jsonPrimitive.content)
        assertEquals("keep-me", reloaded["futureKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `desktop format file with extra fields parses`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.file.writeText(
            """
            {
              "initialized": true,
              "items": [
                {"id":"a","title":"桌面版任务","detail":"备注","date":"2026-11-09","time":"","done":false,
                 "completedAt":"","deleted":false,"deletedAt":"","color":"#f39aa4","defer":"1m",
                 "createdAt":"2026-10-01T00:00:00.000Z","unknownDesktopField":123}
              ],
              "prefs": {"theme":"light","widgetBackground":"transparent","calendarMode":"week"},
              "revision": 7,
              "updatedAt": "2026-11-09T00:00:00.000Z"
            }
            """.trimIndent(),
        )

        val loaded = store.load().data
        assertEquals("桌面版任务", loaded.items[0].title)
        assertEquals("1m", loaded.items[0].defer)
        assertEquals(7, loaded.revision)
        assertEquals("transparent", loaded.prefs["widgetBackground"]!!.jsonPrimitive.content)
    }

    @Test
    fun `export then import round trips data`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample("导出"))))

        val exported = store.exportTo(File(tempDir(), "out.json"))
        assertTrue(exported.exists())

        val other = TaskStore(tempDir())
        other.importFrom(exported)
        assertEquals("导出", other.load().data.items[0].title)
    }

    @Test
    fun `merge import keeps existing items`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample("原有").copy(id = "keep"))))

        val srcDir = tempDir()
        val src = TaskStore(srcDir)
        src.save(StoreData(items = listOf(sample("新增").copy(id = "new"))))

        store.importFrom(src.file, merge = true)
        val ids = store.load().data.items.map { it.id }.toSet()
        assertEquals(setOf("keep", "new"), ids)
    }

    @Test
    fun `duplicate ids reject the file instead of changing task identities`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.file.writeText(
            """{"initialized":true,"items":[
               {"id":"dup","title":"A","createdAt":"2026-11-01T00:00:00Z"},
               {"id":"dup","title":"B","createdAt":"2026-11-01T00:00:00Z"}],
               "prefs":{},"revision":1,"updatedAt":""}""",
        )
        assertEquals("unreadable-or-invalid", store.load().loadError)
    }

    @Test
    fun `atomic write leaves no tmp file behind`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample())))
        assertFalse(File(dir, "data.json.tmp").exists())
    }

    @Test fun `unrelated JSON import cannot erase tasks`() {
        val store = TaskStore(tempDir())
        store.save(StoreData(items = listOf(sample())))
        val incoming = File(tempDir(), "not-tasks.json").apply { writeText("{}") }
        assertThrows(IllegalArgumentException::class.java) { store.importFrom(incoming) }
        assertEquals("买菜", store.load().data.items.single().title)
    }

    @Test fun `unrecoverable data cannot be overwritten or exported as empty`() {
        val store = TaskStore(tempDir())
        store.file.writeText("broken-primary")
        store.backupFile.writeText("broken-backup")
        assertThrows(IllegalStateException::class.java) { store.save(StoreData()) }
        assertThrows(IllegalStateException::class.java) { store.exportTo(File(tempDir(), "out.json")) }
        assertEquals("broken-primary", store.file.readText())
    }

    @Test fun `unknown task and root fields survive editing and export`() {
        val store = TaskStore(tempDir())
        val input = """{"items":[{"id":"a","title":"old","futureTask":{"x":1}}],"futureRoot":"keep"}"""
        val data = store.parse(input)
        store.save(data.copy(items = data.items.map { it.copy(title = "new") }))
        val exported = Json.parseToJsonElement(store.exportTo(File(tempDir(), "out.json")).readText()).jsonObject
        assertEquals("keep", exported["futureRoot"]!!.jsonPrimitive.content)
        assertEquals("1", exported["items"]!!.jsonArray.single().jsonObject["futureTask"]!!.jsonObject["x"]!!.jsonPrimitive.content)
    }

    @Test fun `import uses local monotonically increasing revision`() {
        val store = TaskStore(tempDir())
        repeat(3) { store.save(store.load().data) }
        val incoming = File(tempDir(), "in.json").apply { writeText("""{"items":[],"revision":0}""") }
        assertEquals(4, store.importFrom(incoming).revision)
    }
    @Test fun `explicit valid replacement recovers damaged data without losing evidence`() {
        val store = TaskStore(tempDir())
        store.file.writeText("damaged"); store.backupFile.writeText("damaged-backup")
        val source = File(tempDir(), "restore.json").apply { writeText("""{"items":[{"id":"restore","title":"recovered"}]}""") }
        store.importFrom(source)
        assertEquals("recovered", store.load().data.items.single().title)
        assertTrue(store.file.parentFile!!.listFiles()!!.any { it.name.startsWith("data.json.corrupt-") })
    }
}
