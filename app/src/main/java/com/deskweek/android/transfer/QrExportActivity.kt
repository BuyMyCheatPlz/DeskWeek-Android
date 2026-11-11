package com.deskweek.android.transfer

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import com.deskweek.android.core.data.TaskRepository
import com.deskweek.android.core.transfer.JsonQr
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

class QrExportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        TaskRepository.init(applicationContext)
        setContent {
            MaterialTheme {
                var source by remember { mutableStateOf<String?>(null) }
                var legacy by remember { mutableStateOf(false) }
                var frames by remember { mutableStateOf<List<String>>(emptyList()) }
                var page by remember { mutableIntStateOf(0) }
                var failure by remember { mutableStateOf<String?>(null) }
                var image by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(Unit) {
                    runCatching { withContext(Dispatchers.IO) {
                        val file = File(cacheDir, "qr-export.json")
                        try { TaskRepository.exportTo(file); file.readText() } finally { file.delete() }
                    } }.onSuccess { source = it }.onFailure { if (it is CancellationException) throw it; failure = it.message }
                }
                LaunchedEffect(source, legacy) {
                    val text = source ?: return@LaunchedEffect
                    failure = null
                    runCatching { withContext(Dispatchers.Default) { JsonQr.frames(text, legacy) } }
                        .onSuccess { page = 0; frames = it }
                        .onFailure { if (it is CancellationException) throw it; failure = it.message }
                }
                Surface(Modifier.fillMaxSize()) {
                  BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                    val qrSide = minOf(maxWidth - 32.dp, (maxHeight - 340.dp).coerceAtLeast(120.dp), 540.dp)
                    val pixelSide = with(LocalDensity.current) { qrSide.toPx().roundToInt() }
                    LaunchedEffect(page, frames, pixelSide) {
                        image = null
                        if (frames.isNotEmpty()) runCatching { withContext(Dispatchers.Default) {
                            val matrix = QRCodeWriter().encode(frames[page], BarcodeFormat.QR_CODE, pixelSide, pixelSide,
                                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
                            val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                        } }.onSuccess { image = it }.onFailure { if (it is CancellationException) throw it; failure = it.message }
                    }
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("JSON 二维码", style = MaterialTheme.typography.headlineSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = legacy, onCheckedChange = { legacy = it })
                            Text("兼容旧版（张数更多）")
                        }
                        Text(if (legacy) "旧版兼容模式" else "高效无损压缩，接收端需升级到新版", style = MaterialTheme.typography.bodySmall)
                        Text("可由电脑摄像头扫描，也可继续使用设置中的「导出 data.json」传回电脑。")
                        if (frames.isNotEmpty()) Text("${page + 1} / ${frames.size} 张${if (frames.size > 20) "；张数较多，建议使用 JSON 文件" else ""}")
                        image?.let { Image(it.asImageBitmap(), "任务 JSON 二维码，第 ${page + 1} 张", Modifier.size(qrSide).align(Alignment.CenterHorizontally).background(Color.White), filterQuality = FilterQuality.None) }
                        if (image == null && failure == null) Text("正在生成…")
                        failure?.let { Text("生成失败：$it", color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { page = (page - 1 + frames.size) % frames.size }, enabled = frames.size > 1) { Text("上一张") }
                            OutlinedButton(onClick = { page = (page + 1) % frames.size }, enabled = frames.size > 1) { Text("下一张") }
                            TextButton(onClick = { finish() }) { Text("关闭") }
                        }
                        Text("二维码包含任务明文。多张需全部扫描，收齐前不会导入。生成的是打开时的数据快照。", style = MaterialTheme.typography.bodySmall)
                    }
                  }
                }
            }
        }
    }
}
