package com.deskweek.android.core.data

import com.deskweek.android.core.model.StoreData
import com.deskweek.android.core.model.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `duplicate ids are regenerated on load`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.file.writeText(
            """{"initialized":true,"items":[
               {"id":"dup","title":"A","createdAt":"2026-11-01T00:00:00Z"},
               {"id":"dup","title":"B","createdAt":"2026-11-01T00:00:00Z"}],
               "prefs":{},"revision":1,"updatedAt":""}""",
        )
        val items = store.load().data.items
        assertEquals(2, items.size)
        assertEquals(2, items.map { it.id }.toSet().size)
    }

    @Test
    fun `atomic write leaves no tmp file behind`() {
        val dir = tempDir()
        val store = TaskStore(dir)
        store.save(StoreData(items = listOf(sample())))
        assertFalse(File(dir, "data.json.tmp").exists())
    }
}
