package com.deskweek.android

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.data.TaskStore
import com.deskweek.android.widget.DeskWeekWidget
import com.deskweek.android.widget.DeskWeekWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/** Exercise the actual Glance session -> system RemoteViews -> widget host, not a snapshot mock. */
@RunWith(AndroidJUnit4::class)
class WidgetRefreshTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }
    private fun awaitText(view: AppWidgetHostView, expected: String, timeout: Long = 15000, alternative: String? = null) {
        val deadline = System.currentTimeMillis() + timeout
        var actual = ""
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { actual = text(view) }
            if (expected in actual || alternative != null && alternative in actual) return
            Thread.sleep(100)
        }
        fail("Widget did not show '$expected'. Actual RemoteViews text: $actual")
    }

    @Test fun emptyWidgetObservesAddEditCompleteDeleteWithoutSessionRestart() = withWidget { view ->
        val context = ui.activity.applicationContext
        val task = TaskRepository.add("组件新增回归", LocalDate.now().toString())
        // Explicit update also failed on the old code: this locks down the cached session bug.
        runBlocking { DeskWeekWidget().updateAll(context) }
        awaitText(view, "组件新增回归", 8000)
        TaskRepository.update(task.copy(title = "组件修改回归"))
        awaitText(view, "组件修改回归", 8000)
        TaskRepository.toggleDone(task.id)
        awaitText(view, "☑ 组件修改回归", 8000)
        TaskRepository.softDelete(task.id)
        awaitText(view, "今天空着", 8000)
        TaskRepository.restore(task.id)
        awaitText(view, "组件修改回归", 8000)
        TaskRepository.purge(task.id)
        awaitText(view, "今天空着", 8000)
        val incoming = File(context.cacheDir, "widget-import.json").apply {
            writeText("""{"items":[{"id":"widget-import","title":"组件导入回归","date":"${LocalDate.now()}"}]}""")
        }
        TaskRepository.importFrom(incoming)
        awaitText(view, "组件导入回归", 8000)
    }

    @Test fun undatedTaskAddedFromListAppearsInSmallWidget() = withWidget { view ->
        ui.onNodeWithText("清单").performClick()
        ui.onNodeWithText("+").performClick()
        ui.onNodeWithText("标题").performTextInput("清单未排期任务")
        ui.onNodeWithText("日期 YYYY-MM-DD").performTextClearance()
        ui.onNodeWithText("添加").performClick()
        awaitText(view, "清单未排期任务", 8000)
        awaitText(view, "未排期", 8000)
        assertEquals("", TaskRepository.state.value.items.single().date)
    }

    @Test fun mediumWidgetIncludesUndatedTasks() = withWidget(320) { view ->
        TaskRepository.add("中号未排期任务", "")
        awaitText(view, "中号未排期任务", 8000)
        awaitText(view, "未排期", 8000)
    }

    @Test fun largeWidgetIncludesUndatedTasks() = withWidget(width = 600, height = 240) { view ->
        TaskRepository.add("大号未排期任务", "")
        awaitText(view, "大号未排期任务", 8000)
        awaitText(view, "未排期", 8000)
    }

    @Test fun mediumWidgetShowsTasksOnOtherDaysOfThisWeek() = withWidget(320) { view ->
        val today = LocalDate.now()
        val other = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) today.minusDays(1) else today.plusDays(1)
        TaskRepository.add("本周其他日期任务", other.toString())
        awaitText(view, "本周其他日期任务", 8000)
        awaitText(view, other.toString().substring(5).replace('-', '/'), 8000)
    }

    @Test fun shortWideWidgetActuallyShowsTaskTextInsideItsBounds() = withWidget(width = 320, height = 60, visible = true) { view ->
        TaskRepository.add("矮组件任务文字", LocalDate.now().toString())
        awaitText(view, "矮组件任务文字", 8000)
        var visible = false
        var diagnostic = ""
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !visible) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val title = textViews(view).first { "矮组件任务文字" in it.text }
                val bounds = android.graphics.Rect()
                visible = title.getGlobalVisibleRect(bounds) && bounds.height() >= title.lineHeight
                diagnostic = "host=${view.width}x${view.height}, padding=${view.paddingTop}/${view.paddingBottom}, title=${title.height}, line=${title.lineHeight}, visible=$bounds"
            }
            if (!visible) Thread.sleep(100)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(ui.activity.cacheDir, "qa-short-widget.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("Task exists in RemoteViews but its text is clipped outside the actual widget bounds: $diagnostic", visible)
    }

    @Test fun widgetTextColorChangesWhileTheTaskDataIsUnchanged() = withWidget { view ->
        val context = ui.activity.applicationContext
        val prefs = context.getSharedPreferences("deskweek_widget", 0)
        val oldTransparent = prefs.getBoolean("transparent", false)
        val oldLight = prefs.getBoolean("lightText", true)
        try {
            prefs.edit().putBoolean("transparent", false).putBoolean("lightText", false).commit()
            TaskRepository.add("组件文字配色", LocalDate.now().toString())
            awaitText(view, "组件文字配色", 8000)
            prefs.edit().putBoolean("transparent", true).putBoolean("lightText", true).commit()
            runBlocking { DeskWeekWidget().updateAll(context) }
            val end = System.currentTimeMillis() + 8000
            var color = 0
            while (System.currentTimeMillis() < end) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { color = textViews(view).first { "组件文字配色" in it.text }.currentTextColor }
                if (color == android.graphics.Color.WHITE) break
                Thread.sleep(100)
            }
            assertEquals("Settings update must invalidate an active widget composition", android.graphics.Color.WHITE, color)
        } finally {
            prefs.edit().putBoolean("transparent", oldTransparent).putBoolean("lightText", oldLight).commit()
        }
    }

    @Test fun taskTitlesAreVisibleAcrossWidgetSizes() {
        val context = ui.activity.applicationContext
        val availableWidth = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        for ((width, height) in listOf(180 to 60, 320 to 40, 320 to 110, 600 to 110, 600 to 240).filter { it.first <= availableWidth }) {
            withWidget(width, height, visible = true) { view ->
                TaskRepository.add("可见任务", LocalDate.now().toString())
                awaitText(view, "可见任务")
                val end = System.currentTimeMillis() + 5000
                var visible = false
                var diagnostic = ""
                while (!visible && System.currentTimeMillis() < end) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val title = textViews(view).first { "可见任务" in it.text }
                        val rect = android.graphics.Rect()
                        visible = title.getGlobalVisibleRect(rect) && rect.height() >= title.lineHeight
                        diagnostic = "title height=${title.height}, line=${title.lineHeight}, visible=$rect"
                    }
                    if (!visible) Thread.sleep(100)
                }
                assertTrue("$width x $height widget clipped its task: $diagnostic", visible)
            }
        }
    }

    @Test fun taskChangesWakeWidgetAfterBackgroundSessionExpires() = withWidget { view ->
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ui.activity.moveTaskToBack(true) }
        try {
            // Longer than Glance's active composition lifetime. This must wake a new
            // session through the production repository refresh, not an explicit test update.
            Thread.sleep(65000)
            TaskRepository.add("后台会话后新增", LocalDate.now().toString())
            awaitText(view, "后台会话后新增", 30000)
            val task = TaskRepository.state.value.items.single()
            TaskRepository.update(task.copy(title = "后台会话后修改"))
            awaitText(view, "后台会话后修改", 10000)
        } finally {
            val context = ui.activity.applicationContext
            context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun withWidget(width: Int = 180, height: Int = 110, visible: Boolean = false, body: (AppWidgetHostView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ui.activity.applicationContext
        ui.runOnIdle { TaskRepository.initWithStore(TaskStore(File(context.cacheDir, "widget-${System.nanoTime()}"))) }
        val host = AppWidgetHost(context, 47115)
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        var view: AppWidgetHostView? = null
        try {
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
            }
            assertTrue("Test host could not bind widget", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, DeskWeekWidgetReceiver::class.java), options))
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(context, id, manager.getAppWidgetInfo(id))
                val density = context.resources.displayMetrics.density
                ui.activity.addContentView(view, ViewGroup.LayoutParams(if (visible) (width * density).roundToInt() else 450, if (visible) (height * density).roundToInt() else 300))
                if (!visible) view!!.translationX = -4000f
                // Like a real launcher, report outer size through the host API;
                // it subtracts the system widget padding before notifying Glance.
                if (visible) view!!.updateAppWidgetSize(Bundle(), listOf(SizeF(width.toFloat(), height.toFloat())))
            }
            runBlocking { DeskWeekWidget().updateAll(context) }
            awaitText(view!!, "今天空着", alternative = "DeskWeek")
            body(view!!)
        } finally {
            instrumentation.runOnMainSync { (view?.parent as? ViewGroup)?.removeView(view); host.stopListening() }
            host.deleteAppWidgetId(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
