package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * P2 同步会话：签名 HELLO → HELLO/SUMMARY → 缺口 WANT/OPS → 推送本地 OPS → ACK。
 *
 * 证书按 SHA-256 固定；只信任指纹匹配的叶证书。
 * 不含配对（信任仍是 TOFU）与 mDNS 发现。
 */
class SyncSession(
    private val host: String,
    private val port: Int,
    private val expectedSha256: String,
    private val identity: SyncIdentity,
    private val store: SyncStore,
    private val trust: TrustStore,
    /** 已与用户核对过的短码；用于首次配对 */
    private val confirmedSas: String? = null,
) {

    /** 对端未在本机信任表里；UI 拿到短码让用户核对 */
    class UntrustedPeerException(
        val actorId: String,
        val publicKeyB64: String,
        val sas: String,
    ) : Exception("未配对设备，验证码 $sas")

    data class Report(
        val tlsVersion: String,
        val peerActor: String,
        val pulled: Int,
        val pushed: Int,
        val vectors: Map<String, Long>,
    )

    fun run(timeoutMs: Int = 8000): Report {
        val socket = connect(timeoutMs)
        try {
            socket.soTimeout = timeoutMs
            val output: OutputStream = socket.outputStream
            val input = DataInputStream(socket.inputStream)

            write(output, identity.signed(hello()))

            val reply = read(input) ?: fail("no HELLO reply")
            if (type(reply) != "HELLO") fail("expected HELLO, got ${type(reply)}")
            val peerActor = reply["actorId"]!!.jsonPrimitive.content

            // 对端身份必须可验证；验证通过后要么已配对，要么用户已核对短码
            val peerKey = reply["publicKey"]?.jsonPrimitive?.content
                ?: fail("对端没有提供身份公钥")
            val peerSig = reply["signature"]?.jsonPrimitive?.content
                ?: fail("对端没有提供身份签名")
            if (!SyncIdentity.verify(reply, peerKey, peerSig)) fail("对端身份签名无效")

            val sas = TrustStore.shortAuthString(identity.publicKeyB64, peerKey)
            if (!trust.isTrusted(peerActor, peerKey)) {
                if (confirmedSas == null) throw UntrustedPeerException(peerActor, peerKey, sas)
                if (confirmedSas != sas) throw UntrustedPeerException(peerActor, peerKey, sas)
                trust.remember(
                    TrustStore.Peer(
                        actorId = peerActor,
                        publicKeyB64 = peerKey,
                        groupId = store.groupId,
                        pairedAt = java.time.Instant.now().toString(),
                        sas = sas,
                    ),
                )
            }

            val summary = read(input) ?: fail("no SUMMARY")
            if (type(summary) != "SUMMARY") fail("expected SUMMARY, got ${type(summary)}")
            val remoteVectors = (summary["vectors"] as JsonObject)
                .mapValues { it.value.jsonPrimitive.long }

            // 1) 补齐本地缺的远端操作
            var pulled = 0
            for ((actorId, remoteSeq) in remoteVectors) {
                val localSeq = store.vectors()[actorId] ?: 0L
                if (remoteSeq <= localSeq) continue
                write(output, want(actorId, localSeq + 1, remoteSeq))
                val opsMessage = read(input) ?: continue
                if (type(opsMessage) == "OPS") {
                    val ops = (opsMessage["ops"] as JsonArray).map { it.jsonObject }
                    pulled += store.receive(ops)
                }
            }

            // 2) 把本地全部操作推给对端（对端按 (actor,seq) 幂等去重）
            val localOps = store.allOps()
            var pushed = 0
            if (localOps.isNotEmpty()) {
                localOps.chunked(SyncMessages.MAX_OPS).forEach { batch ->
                    write(output, opsMessage(batch))
                    val ack = read(input) ?: return@forEach
                    if (type(ack) == "ACK") pushed += batch.size
                }
            }

            return Report(
                tlsVersion = socket.session.protocol,
                peerActor = peerActor,
                pulled = pulled,
                pushed = pushed,
                vectors = store.vectors(),
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
        put("groupId", store.groupId)
        put("actorId", identity.actorId)
        put("nonce", "android-hello")
        put("capabilities", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("ops"))
            add(kotlinx.serialization.json.JsonPrimitive("tls"))
        })
        put("publicKey", identity.publicKeyB64)
    }

    private fun want(actorId: String, from: Long, to: Long): JsonObject = buildJsonObject {
        put("type", "WANT")
        put("protocolVersion", 1)
        put("actorId", actorId)
        put("from", from)
        put("to", to)
    }

    private fun opsMessage(ops: List<JsonObject>): JsonObject = buildJsonObject {
        put("type", "OPS")
        put("protocolVersion", 1)
        put("ops", buildJsonArray { ops.forEach { add(it) } })
    }

    private fun write(output: OutputStream, message: JsonObject) {
        SyncMessages.validate(message)
        output.write(FrameCodec.encode(message))
        output.flush()
    }

    /** 超时返回 null，让调用方跳过该轮补齐而不是卡死 */
    private fun read(input: DataInputStream): JsonObject? {
        return try {
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
            message
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    private fun type(message: JsonObject): String = message["type"]!!.jsonPrimitive.content

    private fun fail(message: String): Nothing = throw ProtocolException("EP2", message)

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
        const val EMULATOR_HOST = "10.0.2.2"
        const val P0_PORT = 47821
    }
}
