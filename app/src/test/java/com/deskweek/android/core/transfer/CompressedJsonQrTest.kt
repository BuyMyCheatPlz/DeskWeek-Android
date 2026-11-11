package com.deskweek.android.core.transfer

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Test
import org.junit.Assert.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

class CompressedJsonQrTest {
    private val fixture get() = Json.parseToJsonElement(javaClass.getResource("/json-qr-v2.json")!!.readText()).jsonObject
    private val snapshot get() = fixture.getValue("snapshot").jsonObject
    private val desktopFrames get() = fixture.getValue("desktopFrames").jsonArray.map { it.jsonPrimitive.content }
    @Test fun twentyTasksCompressWithoutDroppingFields() {
        val frames = JsonQr.frames(snapshot.toString())
        assertTrue(frames.size <= 3)
        assertTrue(frames.size * 5 < JsonQr.frames(snapshot.toString(), legacy = true).size)
        assertTrue(frames.all { it.toByteArray().size <= 1100 })
        val c = JsonQr.Collector(); var result: JsonQr.Result? = null
        frames.reversed().forEach { result = c.accept(it) }
        assertEquals(snapshot, Json.parseToJsonElement(result!!.text!!))
        // Optional verification artifact, emitted only when the calling test runner requests it.
        System.getenv("DESKWEEK_QR_FIXTURE_OUT")?.let { path ->
            File(path).apply { parentFile.mkdirs(); writeText(JsonArray(frames.map { JsonPrimitive(it) }).toString()) }
        }
    }
    @Test fun readsDesktopCompressedFixtureAndDuplicates() {
        val c = JsonQr.Collector()
        assertEquals(1, c.accept(desktopFrames[0]).received)
        assertEquals(1, c.accept(desktopFrames[0]).received)
        var result: JsonQr.Result? = null
        desktopFrames.reversed().forEach { result = c.accept(it) }
        assertEquals(snapshot, Json.parseToJsonElement(result!!.text!!))
    }
    @Test fun compressedImagesDecodeWithAndroidScanner() {
        val c = JsonQr.Collector(); var result: JsonQr.Result? = null
        desktopFrames.forEach { frame ->
            val m = QRCodeWriter().encode(frame, BarcodeFormat.QR_CODE, 960, 960)
            val pixels = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) -0x1000000 else -1 }
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, pixels))))
            result = c.accept(decoded.text)
        }
        assertEquals(snapshot, Json.parseToJsonElement(result!!.text!!))
    }
    @Test fun rejectsMixedFormatsAndUnsupportedVersion() {
        val c = JsonQr.Collector(); c.accept(desktopFrames[0])
        assertThrows(IllegalStateException::class.java) { c.accept(JsonQr.frames(snapshot.toString(), legacy = true)[0]) }
        val frame = Json.parseToJsonElement(desktopFrames[0]).jsonObject
        assertThrows(IllegalArgumentException::class.java) { JsonQr.Collector().accept(JsonObject(frame + ("v" to JsonPrimitive(99))).toString()) }
    }
    private fun envelope(bytes: ByteArray, size: Int): String = buildJsonObject {
        put("f", "DWQ"); put("v", 2); put("p", 1); put("n", 1); put("b", bytes.size); put("u", size)
        put("h", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        put("d", Base64.getEncoder().encodeToString(bytes))
    }.toString()
    @Test fun rejectsInflateOverflowTruncationAndTrailingData() {
        val raw = """{"items":[],"padding":"${"x".repeat(10000)}"}""".toByteArray()
        val deflater = Deflater(); val buffer = ByteArray(1024)
        val zip = try { deflater.setInput(raw); deflater.finish(); buffer.copyOf(deflater.deflate(buffer)) } finally { deflater.end() }
        listOf(envelope(zip, 100), envelope(zip, raw.size + 1), envelope(zip.copyOf(zip.size - 2), raw.size), envelope(zip + byteArrayOf(0), raw.size)).forEach {
            assertThrows(Exception::class.java) { JsonQr.Collector().accept(it) }
        }
    }
}
