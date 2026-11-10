package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * P0/P2 同步自检客户端：一条 TLS 连接上完成
 * 签名 HELLO → HELLO/SUMMARY → PING/PONG → 签名 OPS → ACK。
 *
 * 证书按 SHA-256 固定（pin），不使用「接受所有证书」。
 * 不含发现、配对与操作合并。
 */
class SyncClient(
    private val host: String,
    private val port: Int,
    private val expectedSha256: String,
    private val identity: SyncIdentity,
) {

    data class Report(
        val tlsVersion: String,
        val peerActor: String,
        val pongNonce: String,
        val ackVectors: Map<String, Long>,
        val opId: String,
    )

    fun handshake(timeoutMs: Int = 8000): Report {
        val socket = connect(timeoutMs)
        try {
            socket.soTimeout = timeoutMs
            val output: OutputStream = socket.outputStream
            val input = DataInputStream(socket.inputStream)

            write(output, identity.signed(hello()))

            val reply = read(input)
            if (type(reply) != "HELLO") fail("expected HELLO, got ${type(reply)}")
            val peerActor = reply["actorId"]!!.jsonPrimitive.content

            val summary = read(input)
            if (type(summary) != "SUMMARY") fail("expected SUMMARY, got ${type(summary)}")

            write(output, ping())
            val pong = read(input)
            if (type(pong) != "PONG") fail("expected PONG, got ${type(pong)}")

            val op = operation()
            write(output, op)
            val ack = read(input)
            if (type(ack) != "ACK") fail("expected ACK, got ${type(ack)}")
            val vectors = (ack["vectors"] as JsonObject).mapValues { it.value.jsonPrimitive.long }

            return Report(
                tlsVersion = socket.session.protocol,
                peerActor = peerActor,
                pongNonce = pong["nonce"]!!.jsonPrimitive.content,
                ackVectors = vectors,
                opId = (op["ops"] as kotlinx.serialization.json.JsonArray)[0]
                    .jsonObject["opId"]!!.jsonPrimitive.content,
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

    private fun hello(): JsonObject = buildJsonObject {
        put("type", "HELLO")
        put("protocolVersion", 1)
        put("groupId", P0_GROUP)
        put("actorId", identity.actorId)
        put("nonce", "android-hello")
        put("capabilities", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("ops"))
            add(kotlinx.serialization.json.JsonPrimitive("tls"))
        })
        put("publicKey", identity.publicKeyB64)
    }

    private fun ping(): JsonObject = buildJsonObject {
        put("type", "PING")
        put("protocolVersion", 1)
        put("nonce", "android-ping")
    }

    /** 一条真实业务形态的 create 操作，带 ECDSA 签名 */
    private fun operation(): JsonObject {
        val op = buildJsonObject {
            put("protocolVersion", 1)
            put("groupId", P0_GROUP)
            put("opId", "${identity.actorId}:1")
            put("actorId", identity.actorId)
            put("seq", 1)
            put("context", buildJsonObject { })
            put("taskId", UUID.randomUUID().toString())
            put("kind", "create")
            put("changes", buildJsonObject {
                put("title", "P0 签名互通验证")
                put("done", false)
            })
            put("recordedAt", Instant.now().toString())
        }
        return buildJsonObject {
            put("type", "OPS")
            put("protocolVersion", 1)
            put("ops", kotlinx.serialization.json.buildJsonArray {
                add(identity.signed(op))
            })
        }
    }

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
            fail("peer error ${message["code"]?.jsonPrimitive?.content}: ${message["message"]?.jsonPrimitive?.content}")
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
        const val P0_GROUP = "p0-group"
    }
}
