package com.deskweek.android.core.sync

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.*
import kotlinx.serialization.json.*

/** Experimental protocol client. This exchanges operation history, not the task repository. */
class SyncSession(
    private val host: String,
    private val port: Int,
    private val expectedSha256: String,
    private val identity: SyncIdentity,
    private val store: SyncStore,
    private val trust: TrustStore,
    private val confirmedSas: String? = null,
) {
    class UntrustedPeerException(val actorId: String, val publicKeyB64: String, val sas: String) : Exception("未配对设备，验证码 $sas")
    data class Report(val tlsVersion: String, val peerActor: String, val pulled: Int, val pushed: Int, val vectors: Map<String, Long>)

    fun run(timeoutMs: Int = 8000): Report {
        require(host.isNotBlank() && port in 1..65535) { "请输入有效的主机和端口" }
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "证书指纹必须为 64 位十六进制 SHA-256" }
        val socket = connect(timeoutMs)
        socket.use {
            val input = DataInputStream(it.inputStream)
            val output = it.outputStream
            write(output, identity.signed(message("HELLO") {
                put("groupId", store.groupId); put("actorId", identity.actorId)
                put("publicKey", identity.publicKeyB64); put("nonce", UUID.randomUUID().toString())
                put("capabilities", buildJsonArray { add("ops"); add("tls") })
            }))
            val hello = read(input, "HELLO")
            if (hello["groupId"]!!.jsonPrimitive.content != store.groupId) fail("对端数据集不匹配")
            val peerActor = hello["actorId"]!!.jsonPrimitive.content
            if (peerActor == identity.actorId) fail("不能与克隆的本机身份同步")
            val peerKey = hello["publicKey"]?.jsonPrimitive?.content ?: fail("缺少对端公钥")
            val signature = hello["signature"]?.jsonPrimitive?.content ?: fail("缺少对端身份签名")
            if (!SyncIdentity.verify(hello, peerKey, signature)) fail("对端身份签名无效")
            val known = trust.get(peerActor)
            if (known != null && (known.publicKeyB64 != peerKey || known.groupId != store.groupId)) fail("对端身份或数据集已变化，请移除旧配对后重试")
            val sas = TrustStore.shortAuthString(identity.publicKeyB64, peerKey)
            if (known == null) {
                if (confirmedSas != sas) throw UntrustedPeerException(peerActor, peerKey, sas)
                trust.remember(TrustStore.Peer(peerActor, peerKey, store.groupId, java.time.Instant.now().toString(), sas))
            }
            val remoteVectors = read(input, "SUMMARY")["vectors"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }
            var pulled = 0
            remoteVectors.forEach { (actor, remoteSeq) ->
                var from = (store.vectors()[actor] ?: 0L) + 1
                while (from <= remoteSeq) {
                    val to = minOf(remoteSeq, from + SyncMessages.MAX_OPS - 1)
                    write(output, message("WANT") { put("actorId", actor); put("from", from); put("to", to) })
                    val batch = read(input, "OPS")["ops"]!!.jsonArray.map { op -> op.jsonObject }
                    batch.forEach { op ->
                        if (op["actorId"]!!.jsonPrimitive.content != actor || op["seq"]!!.jsonPrimitive.long !in from..to) fail("对端返回了请求范围之外的操作")
                        val author = trust.get(actor) ?: fail("操作原作者尚未配对：$actor")
                        if (author.groupId != store.groupId || !SyncIdentity.verify(op, author.publicKeyB64, op["signature"]!!.jsonPrimitive.content)) fail("远端操作签名无效")
                    }
                    val sequences = batch.map { op -> op["seq"]!!.jsonPrimitive.long }.toSet()
                    if ((from..to).any { seq -> seq !in sequences }) fail("对端未返回完整操作区间")
                    pulled += store.receive(batch)
                    from = to + 1
                }
            }
            if (remoteVectors.any { (actor, seq) -> (store.vectors()[actor] ?: 0L) < seq }) fail("仍有未补齐的因果依赖")
            var pushed = 0
            val missing = store.log.allOps().filter { op ->
                op["seq"]!!.jsonPrimitive.long > (remoteVectors[op["actorId"]!!.jsonPrimitive.content] ?: 0L)
            }
            missing.chunked(SyncMessages.MAX_OPS).forEach { batch ->
                write(output, message("OPS") { put("ops", JsonArray(batch)) })
                val vectors = read(input, "ACK")["vectors"]!!.jsonObject
                if (batch.any { op -> (vectors[op["actorId"]!!.jsonPrimitive.content]?.jsonPrimitive?.long ?: 0L) < op["seq"]!!.jsonPrimitive.long }) fail("对端尚未持久化发送的操作")
                pushed += batch.size
            }
            return Report(it.session.protocol, peerActor, pulled, pushed, store.vectors())
        }
    }
    private fun connect(timeoutMs: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedTrust(expectedSha256)), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.startHandshake()
            return socket
        } catch (e: Exception) { socket.close(); throw e }
    }
    private fun message(type: String, content: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", type); put("protocolVersion", SyncMessages.VERSION); content()
    }
    private fun write(output: OutputStream, message: JsonObject) {
        SyncMessages.validate(message)
        output.write(FrameCodec.encode(message)); output.flush()
    }
    private fun read(input: DataInputStream, expected: String): JsonObject {
        // Timeout/partial frame ends the session. Continuing would lose framing and fake success.
        val size = input.readInt()
        if (size !in 1..FrameCodec.MAX_FRAME) fail("invalid frame length")
        val bytes = ByteArray(size); input.readFully(bytes)
        val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
        val reply = FrameCodec.decode(header + bytes).messages.single()
        SyncMessages.validate(reply)
        if (reply["type"]!!.jsonPrimitive.content == "ERROR") fail("对端拒绝：${reply["code"]!!.jsonPrimitive.content}")
        if (reply["type"]!!.jsonPrimitive.content != expected) fail("expected $expected")
        return reply
    }
    private fun fail(message: String): Nothing = throw ProtocolException("EP2", message)
    private class PinnedTrust(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("client authentication not supported here") }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            leaf.checkValidity()
            val pin = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            if (!pin.equals(expected, ignoreCase = true)) throw CertificateException("certificate pin mismatch")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    companion object {
        const val P0_PIN = "d54bd32e7ab8b3e23906698d4d8a9f0b24535482908612490667065ef162c76e"
        const val EMULATOR_HOST = "10.0.2.2"
        const val P0_PORT = 47821
    }
}
