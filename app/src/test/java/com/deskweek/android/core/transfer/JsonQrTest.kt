package com.deskweek.android.core.transfer

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

class JsonQrTest {
    private val fixture get() = Json.parseToJsonElement(javaClass.getResource("/json-qr-v1.json")!!.readText()).jsonObject
    private val snapshot get() = fixture.getValue("snapshot").jsonObject
    private val frames get() = fixture.getValue("frames").jsonArray.map { it.jsonPrimitive.content }
    @Test fun singleFrameIsPlainJson() {
        val text = """{"items":[{"id":"raw","title":"中文任务😀"}],"prefs":{}}"""
        assertEquals(1, JsonQr.frames(text).size)
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(JsonQr.Collector().accept(JsonQr.frames(text).single()).text!!))
    }
    @Test fun sharesExactWireFramesWithDesktop() {
        assertEquals(frames, JsonQr.frames(snapshot.toString(), legacy = true))
        assertTrue(frames.all { it.toByteArray().size <= JsonQr.QR_BYTES })
        val collector = JsonQr.Collector()
        assertNull(collector.accept(frames.last()).text)
        assertEquals(1, collector.accept(frames.last()).received)
        var result: JsonQr.Result? = null
        frames.reversed().forEach { result = collector.accept(it) }
        assertEquals(snapshot, Json.parseToJsonElement(result!!.text!!))
    }
    @Test fun zxingImagesDecodeAllDesktopFrames() {
        val collector = JsonQr.Collector(); var result: JsonQr.Result? = null
        frames.forEach { frame ->
            val m = QRCodeWriter().encode(frame, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
            val pixels = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) -0x1000000 else -1 }
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, pixels))))
            assertEquals(frame, decoded.text); result = collector.accept(decoded.text)
        }
        assertEquals(snapshot, Json.parseToJsonElement(result!!.text!!))
    }
    @Test fun rejectsMixedSessionsDuplicateConflictAndSupportsReset() {
        val collector = JsonQr.Collector(); collector.accept(frames[0])
        val p = Json.parseToJsonElement(frames[0]).jsonObject
        val other = JsonObject(p + mapOf("sha256" to JsonPrimitive("f".repeat(64)), "id" to JsonPrimitive("f".repeat(16))))
        assertThrows(IllegalStateException::class.java) { collector.accept(other.toString()) }
        val duplicate = JsonObject(p + ("data" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(ByteArray(JsonQr.CHUNK_BYTES)))))
        assertThrows(IllegalStateException::class.java) { collector.accept(duplicate.toString()) }
        assertEquals(1, collector.progress.received); collector.reset(); assertEquals(0, collector.progress.received)
    }
    @Test fun checksumStopsTamperedLastStep() {
        val collector = JsonQr.Collector(); val p = Json.parseToJsonElement(frames[0]).jsonObject
        val bytes = java.util.Base64.getDecoder().decode(p.getValue("data").jsonPrimitive.content); bytes[8] = (bytes[8].toInt() xor 1).toByte()
        collector.accept(JsonObject(p + ("data" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes)))).toString())
        frames.drop(1).dropLast(1).forEach { assertNull(collector.accept(it).text) }
        assertThrows(IllegalArgumentException::class.java) { collector.accept(frames.last()) }
    }
    @Test fun rejectsInvalidSchemaAndResourceBounds() {
        listOf("{}", """{"items":[{"id":"a","title":"a"},{"id":"a","title":"b"}]}""", """{"items":[{"id":"a","title":"a","done":"false"}]}""", """{"items":[{"id":"a","title":"a","date":"2026-02-30"}]}""", """{"items":[{"id":"a","title":"a","time":"25:00"}]}""").forEach { bad ->
            assertThrows(RuntimeException::class.java) { JsonQr.validate(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { JsonQr.frames("""{"items":[],"padding":"${"x".repeat(JsonQr.MAX_BYTES)}"}""") }
        val p = Json.parseToJsonElement(frames[0]).jsonObject
        listOf("version" to 2, "total" to 999999, "part" to 0, "bytes" to 1).forEach { (key, value) ->
            val c = JsonQr.Collector()
            assertThrows(IllegalArgumentException::class.java) { c.accept(JsonObject(p + (key to JsonPrimitive(value))).toString()) }
            assertEquals(0, c.progress.received)
        }
    }
}
