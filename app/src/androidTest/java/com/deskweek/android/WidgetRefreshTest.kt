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

/** Exercise the actual Glance session -> system RemoteViews -> widget host, not a snapshot mock. */
@RunWith(AndroidJUnit4::class)
class WidgetRefreshTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }
    private fun awaitText(view: AppWidgetHostView, expected: String, timeout: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeout
        var actual = ""
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { actual = text(view) }
            if (expected in actual) return
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

    @Test fun largeWidgetIncludesUndatedTasks() = withWidget(600) { view ->
        TaskRepository.add("大号未排期任务", "")
        awaitText(view, "大号未排期任务", 8000)
        awaitText(view, "未排期", 8000)
    }

    private fun withWidget(width: Int = 180, body: (AppWidgetHostView) -> Unit) {
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
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), 110f)))
            }
            assertTrue("Test host could not bind widget", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, DeskWeekWidgetReceiver::class.java), options))
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(context, id, manager.getAppWidgetInfo(id))
                ui.activity.addContentView(view, ViewGroup.LayoutParams(450, 300))
                view!!.translationX = -4000f
            }
            runBlocking { DeskWeekWidget().updateAll(context) }
            awaitText(view!!, if (width < 240) "今天空着" else "DeskWeek")
            body(view!!)
        } finally {
            instrumentation.runOnMainSync { (view?.parent as? ViewGroup)?.removeView(view); host.stopListening() }
            host.deleteAppWidgetId(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
