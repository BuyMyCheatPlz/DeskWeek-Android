package com.deskweek.android.transfer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.deskweek.android.core.data.TaskStore
import com.deskweek.android.core.transfer.JsonQr
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*
import java.io.File

/** Continuous camera collection, entirely on-device. No task is written until the caller confirms. */
class QrScanActivity : ComponentActivity() {
    companion object { const val RESULT_FILE = "qr-import.json" }
    private lateinit var camera: DecoratedBarcodeView
    private lateinit var status: TextView
    private val collector = JsonQr.Collector()
    private var complete = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestCamera() else status.text = "未获得相机权限。可点重试授权，或返回后通过 JSON 文件导入；如已禁止询问，请在系统设置中授权。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        File(cacheDir, RESULT_FILE).delete()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "清空进度"; setOnClickListener { collector.reset(); status.text = "进度已清空，请扫描同一份数据" } }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "重试相机"; setOnClickListener { requestCamera() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        status = TextView(this).apply { text = "对准电脑的任务 JSON 二维码。多张时保持相机打开，顺序不限。"; setTextColor(Color.WHITE); setPadding(24, 16, 24, 16); textSize = 17f }
        root.addView(status)
        camera = DecoratedBarcodeView(this)
        camera.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        camera.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() {}
            override fun previewStarted() {}
            override fun previewStopped() {}
            override fun cameraClosed() {}
            override fun cameraError(error: Exception) { status.text = "摄像头不可用：${error.message}。请重试或返回后使用 JSON 文件导入。" }
        })
        camera.setStatusText("")
        root.addView(camera, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        camera.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (complete) return
                runCatching {
                    val collected = collector.accept(result.text)
                    status.text = "已收集 ${collected.received} / ${collected.total} 张；收齐后还需确认导入"
                    collected.text?.let { text ->
                        TaskStore(File(filesDir, "data")).parse(text)
                        File(cacheDir, RESULT_FILE).writeText(text, Charsets.UTF_8)
                        complete = true; camera.pause(); setResult(Activity.RESULT_OK); finish()
                    }
                }.onFailure { status.text = "${it.message}（已收集 ${collector.progress.received} / ${collector.progress.total}）" }
            }
        })
    }
    @Suppress("DEPRECATION")
    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
            val count = android.hardware.Camera.getNumberOfCameras()
            if (count == 0) { status.text = "没有可用摄像头，请返回后使用 JSON 文件导入"; return }
            val selected = (0 until count).firstOrNull { index ->
                val info = android.hardware.Camera.CameraInfo(); android.hardware.Camera.getCameraInfo(index, info)
                info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
            } ?: 0
            camera.cameraSettings.requestedCameraId = selected
            camera.resume()
            }.onFailure { status.text = "摄像头不可用：${it.message}。请重试或使用 JSON 文件导入。" }
        }
        else permission.launch(Manifest.permission.CAMERA)
    }
    override fun onResume() { super.onResume(); if (!complete) requestCamera() }
    override fun onPause() { camera.pause(); super.onPause() }
}
