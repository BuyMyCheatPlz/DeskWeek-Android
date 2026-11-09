package com.deskweek.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deskweek.android.wallpaper.DeskWeekWallpaperService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Home()
                }
            }
        }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(DeskWeekWallpaperService.PREFS, android.content.Context.MODE_PRIVATE)
    }
    var dark by remember { mutableStateOf(prefs.getBoolean(DeskWeekWallpaperService.KEY_DARK, false)) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "DeskWeek", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "周历画在壁纸上，图标压在上面。双击壁纸可回到这里。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { context.startActivity(DeskWeekWallpaperService.changeWallpaperIntent(context)) },
        ) {
            Text("设为动态壁纸")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("深色壁纸", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = dark,
                onCheckedChange = {
                    dark = it
                    prefs.edit().putBoolean(DeskWeekWallpaperService.KEY_DARK, it).apply()
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "注意：系统同一时间只能运行一个动态壁纸。若你原本在用视频壁纸，",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "启用 DeskWeek 会顶替它；关闭后可在系统里换回。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
