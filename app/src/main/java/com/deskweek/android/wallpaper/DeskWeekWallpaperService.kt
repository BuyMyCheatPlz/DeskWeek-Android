package com.deskweek.android.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.deskweek.android.core.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 动态壁纸：把周历画在壁纸层，桌面图标压在它上面。
 *
 * 这是 DeskWeek Android 的主形态 —— 与桌面版"浮窗贴在壁纸层、图标在上"一一对应。
 *
 * 设计约束（实测得来）：
 * - 只有可见时才订阅数据并绘制，不可见时零 CPU（避免耗电）
 * - 静态画面不重复重绘，只在数据/日期/尺寸变化时 draw 一次
 * - 只有一张壁纸位，用户原有的动态壁纸需要停用；因此 App 内另有小组件作为保底形态
 */
class DeskWeekWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "DeskWeekWallpaper"
        const val PREFS = "deskweek_wallpaper"
        const val KEY_DARK = "dark"

        /** 供 App 内部触发跳转壁纸选择页 */
        fun changeWallpaperIntent(context: Context): Intent =
            Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, DeskWeekWallpaperService::class.java),
                )
            }
    }

    override fun onCreate() {
        super.onCreate()
        TaskRepository.init(applicationContext)
    }

    override fun onCreateEngine(): Engine = DeskEngine()

    private inner class DeskEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val renderer = WeekWallpaperRenderer()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val gestureDetector = GestureDetector(
            this@DeskWeekWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    openApp()
                    return true
                }
            },
        )

        private var visible = false
        private var width = 0
        private var height = 0
        private var lastDrawnDate: LocalDate? = null
        private var collectJob: Job? = null

        /** 跨天时重绘（壁纸可能连续挂机好几天） */
        private val midnightTick = object : Runnable {
            override fun run() {
                if (LocalDate.now() != lastDrawnDate) drawFrame()
                scheduleMidnightTick()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            collectJob?.cancel()
            scope.cancel()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(midnightTick)
            if (visible) {
                drawFrame()
                scheduleMidnightTick()
                startCollecting()
            } else {
                lastDrawnDate = null
                collectJob?.cancel()
                collectJob = null
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            width = w
            height = h
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(midnightTick)
            collectJob?.cancel()
            collectJob = null
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
        }

        /** 数据一变就重绘；壁纸和 App 同进程，直接吃同一个 StateFlow */
        private fun startCollecting() {
            if (collectJob != null) return
            collectJob = scope.launch {
                TaskRepository.state.collectLatest { drawFrame() }
            }
        }

        private fun scheduleMidnightTick() {
            val now = java.time.LocalDateTime.now()
            val next = now.toLocalDate().plusDays(1).atStartOfDay().plusSeconds(1)
            val delay = java.time.Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
            handler.postDelayed(midnightTick, delay)
        }

        private fun drawFrame() {
            if (!visible || width <= 0 || height <= 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "surface not ready: ${e.message}")
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "unlock failed: ${e.message}")
                    }
                }
            }
        }

        private fun render(canvas: Canvas) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val dark = prefs.getBoolean(KEY_DARK, false)

            drawBackground(canvas, dark)
            renderer.draw(
                canvas = canvas,
                width = width,
                height = height,
                today = LocalDate.now(),
                tasks = TaskRepository.state.value.items
                    .filter { !it.deleted && it.date.isNotBlank() }
                    .map { it.toWallTask() },
                style = WeekWallpaperRenderer.Style(dark = dark),
            )
            lastDrawnDate = LocalDate.now()
        }

        private fun drawBackground(canvas: Canvas, dark: Boolean) {
            bgPaint.shader = if (dark) {
                LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFF14161A.toInt(), 0xFF1E2127.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFFF6F5F2.toInt(), 0xFFE9E6E0.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            bgPaint.shader = null
        }

        private fun openApp() {
            val intent = Intent(this@DeskWeekWallpaperService, com.deskweek.android.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "cannot open app: ${e.message}")
            }
        }
    }
}
