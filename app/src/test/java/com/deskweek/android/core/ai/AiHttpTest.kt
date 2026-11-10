package com.deskweek.android.core.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AiHttpTest {
    @Test fun `real HTTP transport posts credentials and parses task response`() {
        val authorization = AtomicReference<String>()
        val payload = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            payload.set(exchange.requestBody.bufferedReader().readText())
            val bytes = """{"choices":[{"message":{"content":"[{\"title\":\"HTTP task\"}]"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val drafts = AiClient().extract(AiConfig("http://127.0.0.1:${server.address.port}/v1", "test-model", "test-only-key"), "test-only-input")
            assertEquals("HTTP task", drafts.single().title)
            assertEquals("Bearer test-only-key", authorization.get())
            assertTrue(payload.get().contains("test-only-input"))
        } finally { server.stop(0) }
    }
    @Test fun `HTTP failure is reported instead of creating tasks`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange -> exchange.sendResponseHeaders(429, -1); exchange.close() }
        server.start()
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                AiClient().extract(AiConfig("http://127.0.0.1:${server.address.port}", "test", "test-only"), "sample")
            }
            assertTrue(error.message!!.contains("429"))
        } finally { server.stop(0) }
    }
}
