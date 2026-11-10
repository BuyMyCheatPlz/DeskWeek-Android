package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * P0 同步自检客户端：一条 TLS 连接上完成
 * HELLO → HELLO/SUMMARY → PING → PONG，用来验证
 * 「Android 与桌面端能用同一套帧与消息互通」。
 *
 * 只做握手与一条 PING，不做发现、配对、操作合并。
 * 证书按 SHA-256 固定（pin），不使用「接受所有证书」。
 */
class SyncClient(
    private val host: String,
    private val port: Int,
    private val expectedSha256: String,
) {

    data class Report(
        val tlsVersion: String,
        val peerActor: String,
        val summaryVectors: Map<String, Long>,
        val pongNonce: String,
    )

    fun handshake(timeoutMs: Int = 6000): Report {
        val socket = connect(timeoutMs)
        try {
            socket.soTimeout = timeoutMs
            val output: OutputStream = socket.outputStream
            val input = DataInputStream(socket.inputStream)

            write(output, hello())
            val reply = read(input)
            if (type(reply) != "HELLO") fail("expected HELLO, got ${type(reply)}")
            val peerActor = reply["actorId"]!!.jsonPrimitive.content

            val summary = read(input)
            if (type(summary) != "SUMMARY") fail("expected SUMMARY, got ${type(summary)}")
            val vectors = (summary["vectors"] as JsonObject).mapValues { it.value.jsonPrimitive.long }

            write(output, ping())
            val pong = read(input)
            if (type(pong) != "PONG") fail("expected PONG, got ${type(pong)}")

            return Report(
                tlsVersion = socket.session.protocol,
                peerActor = peerActor,
                summaryVectors = vectors,
                pongNonce = pong["nonce"]!!.jsonPrimitive.content,
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun connect(timeoutMs: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedTrust(expectedSha256)), null)
        val raw = context.socketFactory.createSocket() as SSLSocket
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.startHandshake()
        return raw
    }

    private fun hello(): JsonObject = Json.parseToJsonElement(
        """{"type":"HELLO","protocolVersion":1,"groupId":"p0-group","actorId":"android-p0",
            "nonce":"android-hello","capabilities":["ops","tls"]}""",
    ).jsonObject

    private fun ping(): JsonObject = Json.parseToJsonElement(
        """{"type":"PING","protocolVersion":1,"nonce":"android-ping"}""",
    ).jsonObject

    private fun write(output: OutputStream, message: JsonObject) {
        SyncMessages.validate(message)
        output.write(FrameCodec.encode(message))
        output.flush()
    }

    private fun read(input: DataInputStream): JsonObject {
        val header = ByteArray(4)
        input.readFully(header)
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 0 || length > FrameCodec.MAX_FRAME) fail("bad frame length $length")
        val body = ByteArray(length)
        input.readFully(body)
        val message = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        if (type(message) == "ERROR") {
            val code = message["code"]?.jsonPrimitive?.content
            val text = message["message"]?.jsonPrimitive?.content
            fail("peer error $code: $text")
        }
        return message
    }

    private fun type(message: JsonObject): String = message["type"]!!.jsonPrimitive.content

    private fun fail(message: String): Nothing = throw ProtocolException("EP0", message)

    /** 只信任指纹等于 pin 的叶证书 */
    private class PinnedTrust(private val expectedSha256: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val actual = digest.joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw CertificateException("certificate pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** P0 测试证书指纹，见 test/fixtures/certs/pin.txt（仅原型使用） */
        const val P0_PIN = "d54bd32e7ab8b3e23906698d4d8a9f0b24535482908612490667065ef162c76e"

        /** Android 模拟器访问宿主机 */
        const val EMULATOR_HOST = "10.0.2.2"
        const val P0_PORT = 47821
    }
}
