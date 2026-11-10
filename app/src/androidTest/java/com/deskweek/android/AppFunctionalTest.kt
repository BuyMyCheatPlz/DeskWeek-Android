package com.deskweek.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskweek.android.core.ai.ApiKeyStorage
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.data.TaskStore
import java.io.File
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction

@RunWith(AndroidJUnit4::class)
class AppFunctionalTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun resetTestRepository() {
        ui.runOnIdle {
            val dir = File(ui.activity.cacheDir, "functional-${System.nanoTime()}")
            TaskRepository.initWithStore(TaskStore(dir))
        }
    }
    private fun seed(title: String = "界面测试任务") = ui.runOnIdle { TaskRepository.add(title, LocalDate.now().toString()) }

    @Test fun createEditCompleteAndPersist() {
        ui.onNodeWithText("+").performClick()
        ui.onNodeWithText("标题").performTextInput("界面新增任务")
        ui.onNodeWithText("添加").performClick()
        ui.onNodeWithText("界面新增任务").assertIsDisplayed().performClick()
        ui.onNodeWithText("标题").performTextReplacement("界面修改任务")
        ui.onNodeWithText("保存").performClick()
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        ui.runOnIdle {
            val task = TaskRepository.state.value.items.single()
            assertEquals("界面修改任务", task.title); assertTrue(task.done)
            assertTrue(TaskStore(TaskRepository.storeFile().parentFile!!).load().data.items.single().done)
        }
    }
    @Test fun trashRestoreAndConfirmPermanentDelete() {
        seed()
        ui.onNodeWithText("清单").performClick()
        ui.onNodeWithText("删除").performClick()
        ui.onNodeWithText("垃圾箱").performClick()
        ui.onNodeWithText("恢复").performClick()
        ui.onNodeWithText("未完成").performClick()
        ui.onNodeWithText("删除").performClick()
        ui.onNodeWithText("垃圾箱").performClick()
        ui.onNodeWithText("彻底删除").performClick()
        ui.onNodeWithText("取消").performClick()
        ui.runOnIdle { assertEquals(1, TaskRepository.state.value.items.size) }
        ui.onNodeWithText("彻底删除").performClick()
        ui.onNodeWithText("确认删除").performClick()
        ui.runOnIdle { assertTrue(TaskRepository.state.value.items.isEmpty()) }
    }
    @Test fun invalidTimeDisablesSaveAndEmptyDateIsSupported() {
        seed()
        ui.onNodeWithText("界面测试任务").performClick()
        ui.onNodeWithText("时间").performTextReplacement("99:99")
        ui.onNodeWithText("保存").assertIsNotEnabled()
        ui.onNodeWithText("时间").performTextClearance()
        ui.onNodeWithText("日期").performTextClearance()
        ui.onNodeWithText("保存").performClick()
        ui.runOnIdle { assertEquals("", TaskRepository.state.value.items.single().date) }
    }
    @Test fun deferredTasksAreSeparateFromVisibleTodo() {
        ui.runOnIdle { TaskRepository.add("暂缓任务", LocalDate.now().plusMonths(2).toString(), defer = "3d") }
        ui.onNodeWithText("清单").performClick()
        ui.onNodeWithText("暂缓任务").assertDoesNotExist()
        ui.onNodeWithText("暂缓").performScrollTo().performClick()
        ui.onNodeWithText("暂缓任务").assertIsDisplayed()
    }
    @Test fun weekNavigationAndReturnToday() {
        seed()
        ui.onNodeWithText("下一周").performClick()
        ui.onNodeWithText("界面测试任务").assertDoesNotExist()
        ui.onNodeWithText("今天").performClick()
        ui.onNodeWithText("界面测试任务").assertIsDisplayed()
    }
    @Test fun recreationKeepsSelectedTab() {
        ui.onNodeWithText("清单").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("垃圾箱").assertIsDisplayed()
    }
    @Test fun androidKeystoreMigratesAndEncryptsApiKey() {
        val context = ui.activity
        val prefs = context.getSharedPreferences("qa-credentials", 0)
        prefs.edit().clear().putString("apiKey", "test-secret-only").commit()
        val storage = ApiKeyStorage(context, "qa-credentials")
        assertEquals("test-secret-only", storage.read())
        assertFalse(prefs.contains("apiKey"))
        assertFalse(prefs.all.toString().contains("test-secret-only"))
        assertEquals("test-secret-only", ApiKeyStorage(context, "qa-credentials").read())
        storage.write("")
        assertEquals("", storage.read())
    }

    private fun document(name: String, content: String): Pair<File, android.net.Uri> {
        val path = File(ui.activity.cacheDir, "qa-documents").apply { mkdirs() }
        val file = File(path, name).apply { writeText(content) }
        return file to FileProvider.getUriForFile(ui.activity, ui.activity.packageName + ".qa-files", file)
    }
    @Test fun exportDocumentUiWritesTheActualTasks() {
        seed("导出界面任务")
        val (file, uri) = document("output.json", "")
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            ui.onNodeWithText("设置").performClick()
            ui.onNodeWithText("导出 data.json").performScrollTo().performClick()
            ui.waitForIdle()
            assertEquals("导出界面任务", TaskStore(file.parentFile!!).parse(file.readText()).items.single().title)
        } finally { Intents.release() }
    }
    @Test fun importUiRequiresConfirmationAndMerges() {
        seed("原有任务")
        val (_, uri) = document("incoming.json", """{"items":[{"id":"new","title":"导入界面任务"}]}""")
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            ui.onNodeWithText("设置").performClick()
            ui.onNodeWithText("导入 data.json").performScrollTo().performClick()
            ui.onNodeWithText("导入 1 条任务").assertIsDisplayed()
            ui.runOnIdle { assertEquals(1, TaskRepository.state.value.items.size) }
            ui.onNodeWithText("合并").performClick()
            ui.runOnIdle { assertEquals(setOf("原有任务", "导入界面任务"), TaskRepository.state.value.items.map { it.title }.toSet()) }
        } finally { Intents.release() }
    }
    @Test fun invalidImportUiDoesNotReplaceExistingData() {
        seed("保持任务")
        val (_, uri) = document("invalid.json", "{}")
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            ui.onNodeWithText("设置").performClick()
            ui.onNodeWithText("导入 data.json").performScrollTo().performClick()
            ui.runOnIdle { assertEquals("保持任务", TaskRepository.state.value.items.single().title) }
            ui.onNodeWithText("导入失败", substring = true).assertExists()
        } finally { Intents.release() }
    }
    @Test fun moveDateDialogCanClearDate() {
        seed()
        ui.onNodeWithText("改期").performClick()
        ui.onNodeWithText("移到「无日期」").performClick()
        ui.runOnIdle { assertEquals("", TaskRepository.state.value.items.single().date) }
    }
    @Test fun dragReschedulesAndCancelledDragKeepsTheDate() {
        val first = seed("拖动任务")
        val today = LocalDate.now()
        // Choose another day in this displayed week, including when today is Sunday.
        val target = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) today.minusDays(1) else today.plusDays(1)
        val source = ui.onNodeWithText("拖动任务").fetchSemanticsNode().boundsInRoot.center
        val destination = ui.onNodeWithTag("day-$target").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput { down(source); advanceEventTime(700); moveTo(destination, delayMillis = 600); up() }
        ui.runOnIdle { assertEquals(target.toString(), TaskRepository.state.value.items.first { it.id == first.id }.date) }
        val second = seed("取消拖动任务")
        val nextSource = ui.onNodeWithText("取消拖动任务").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput { down(nextSource); advanceEventTime(700); moveTo(destination, delayMillis = 600); cancel() }
        ui.runOnIdle { assertEquals(today.toString(), TaskRepository.state.value.items.first { it.id == second.id }.date) }
        ui.onNodeWithText("取消拖动任务").performTouchInput { longClick(durationMillis = 700) }
        ui.runOnIdle { assertEquals(today.toString(), TaskRepository.state.value.items.first { it.id == second.id }.date) }
    }
}
