package com.deskweek.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.updateAll
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.ui.DeskWeekApp
import com.deskweek.android.widget.DeskWeekWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        TaskRepository.init(applicationContext)
        // Offline QR/file transfers supersede the old experimental LAN worker.
        androidx.work.WorkManager.getInstance(applicationContext).apply {
            cancelUniqueWork("deskweek-sync-periodic")
            cancelUniqueWork("deskweek-sync-now")
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeskWeekApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 小组件不会自动跟着本地文件变化，回到前台时刷新一次
        lifecycleScope.launch { runCatching { DeskWeekWidget().updateAll(applicationContext) } }
    }
}
