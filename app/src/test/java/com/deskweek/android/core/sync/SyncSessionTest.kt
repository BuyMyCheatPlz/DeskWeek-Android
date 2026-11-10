package com.deskweek.android.core.sync

import java.io.DataInputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real TLS sockets with a test-only certificate; no production API or user data is accessed. */
class SyncSessionTest {
    private fun message(type: String, block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type); put("protocolVersion", 1); block()
    }
    private fun read(input: DataInputStream): JsonObject {
        val size = input.readInt()
        require(size in 1..FrameCodec.MAX_FRAME)
        val bytes = ByteArray(size); input.readFully(bytes)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun write(out: OutputStream, obj: JsonObject) { out.write(FrameCodec.encode(obj)); out.flush() }

    private fun fixture(
        group: String = "g", remote: (SyncIdentity) -> List<JsonObject> = { emptyList() },
        omitAck: Boolean = false,
        check: (SyncSession, SyncStore) -> Unit,
    ) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            SyncSessionTest::class.java.getResourceAsStream("/sync-test.p12")!!.use { load(it, "test-only-password".toCharArray()) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "test-only-password".toCharArray())
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val server = ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.soTimeout = 5000
        val pin = MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate("sync-test").encoded).joinToString("") { "%02x".format(it) }
        val peer = SyncIdentity.generate()
        val identity = SyncIdentity.generate()
        val dir = Files.createTempDirectory("session-test").toFile()
        val store = SyncStore(dir, "g")
        val trust = TrustStore(dir).apply { remember(TrustStore.Peer(peer.actorId, peer.publicKeyB64, "g", "now", "123456")) }
        val initial = store.log.nextOp(identity.actorId, "local", "create", buildJsonObject { put("title", "local") }, recordedAt = "now")
        store.receive(listOf(identity.signed(initial)))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                read(input)
                write(output, peer.signed(message("HELLO") {
                    put("groupId", group); put("actorId", peer.actorId); put("publicKey", peer.publicKeyB64)
                    put("nonce", "server-nonce"); put("capabilities", JsonArray(emptyList()))
                }))
                val ops = remote(peer)
                val remoteSeq = ops.maxOfOrNull { it["seq"]!!.jsonPrimitive.long } ?: 0
                write(output, message("SUMMARY") { put("vectors", buildJsonObject { if (remoteSeq > 0) put(peer.actorId, remoteSeq) }) })
                while (true) {
                    val request = try { read(input) } catch (_: java.io.EOFException) { break }
                    when (request["type"]!!.jsonPrimitive.content) {
                        "WANT" -> {
                            val from = request["from"]!!.jsonPrimitive.long; val to = request["to"]!!.jsonPrimitive.long
                            require(to - from < SyncMessages.MAX_OPS) { "unbounded WANT range" }
                            write(output, message("OPS") { put("ops", JsonArray(ops.filter { it["seq"]!!.jsonPrimitive.long in from..to })) })
                        }
                        "OPS" -> {
                            if (omitAck) { Thread.sleep(900); break }
                            write(output, message("ACK") { put("vectors", buildJsonObject { put(identity.actorId, 1); if (remoteSeq > 0) put(peer.actorId, remoteSeq) }) })
                        }
                    }
                }
            }
        }
        try {
            check(SyncSession("127.0.0.1", server.localPort, pin, identity, store, trust), store)
            future.get(7, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow(); dir.deleteRecursively() }
    }
    private fun operations(peer: SyncIdentity, count: Int): List<JsonObject> = (1..count).map { seq -> peer.signed(buildJsonObject {
        put("protocolVersion", 1); put("groupId", "g"); put("actorId", peer.actorId); put("seq", seq)
        put("opId", "${peer.actorId}:$seq"); put("taskId", "remote"); put("kind", if (seq == 1) "create" else "patch")
        put("changes", buildJsonObject { put("title", "remote-$seq") }); put("context", buildJsonObject { if (seq > 1) put(peer.actorId, seq - 1) })
        put("recordedAt", "now")
    }) }
    @Test fun `wrong group HELLO is rejected before data exchange`() = fixture(group = "other") { session, _ ->
        assertThrows(ProtocolException::class.java) { session.run(500) }
    }
    @Test fun `tampered remote signature is rejected before persistence`() = fixture(remote = { peer ->
        operations(peer, 1).map { JsonObject(it + ("changes" to buildJsonObject { put("title", "tampered") })) }
    }) { session, store ->
        assertThrows(ProtocolException::class.java) { session.run(500) }
        assertEquals(1, store.allOps().size)
    }
    @Test fun `missing ACK is a failure instead of successful sync`() = fixture(omitAck = true) { session, _ ->
        assertThrows(SocketTimeoutException::class.java) { session.run(300) }
    }
    @Test fun `more than one hundred remote operations are paginated and verified`() = fixture(remote = { operations(it, 101) }) { session, store ->
        assertEquals(101, session.run(1500).pulled)
        assertEquals(102, store.allOps().size)
    }
}
