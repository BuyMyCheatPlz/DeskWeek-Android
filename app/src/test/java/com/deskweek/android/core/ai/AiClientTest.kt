package com.deskweek.android.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AI 客户端：不依赖真实网络，用假 transport 校验请求体与解析。
 * 端到端（真实 HTTP）由 mock server 现场验证，见 docs/verification-report.md。
 */
class AiClientTest {

    private class FakeTransport(private val response: String) : HttpTransport {
        var lastUrl: String = ""
        var lastBody: String = ""
        var lastHeaders: Map<String, String> = emptyMap()

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return response
        }
    }

    private fun completion(content: String): String =
        """{"choices":[{"message":{"role":"assistant","content":${quote(content)}}}]}"""

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `extracts drafts from a plain json array`() {
        val transport = FakeTransport(completion("""[{"title":"评审方案","date":"2026-11-12","time":"10:00"}]"""))
        val client = AiClient(transport)

        val drafts = client.extract(AiConfig("https://example.com/v1", "m", "k"), "资料正文")

        assertEquals(1, drafts.size)
        assertEquals("评审方案", drafts[0].title)
        assertEquals("2026-11-12", drafts[0].date)
        assertEquals("10:00", drafts[0].time)
        assertEquals("https://example.com/v1/chat/completions", transport.lastUrl)
        assertEquals("Bearer k", transport.lastHeaders["Authorization"])
        assertTrue(transport.lastBody.contains("\"model\":\"m\""))
        assertTrue(transport.lastBody.contains("资料正文"))
    }

    @Test
    fun `strips markdown fences`() {
        val transport = FakeTransport(completion("```json\n[{\"title\":\"带围栏\"}]\n```"))
        val drafts = AiClient(transport).extract(AiConfig("https://example.com/v1", "m", "k"), "x")
        assertEquals("带围栏", drafts[0].title)
    }

    @Test
    fun `drops entries without title and caps lengths`() {
        val long = "标".repeat(200)
        val transport = FakeTransport(completion("""[{"title":""},{"date":"2026-01-01"},{"title":"$long","detail":"x"}]"""))
        val drafts = AiClient(transport).extract(AiConfig("https://example.com/v1", "m", "k"), "x")
        assertEquals(1, drafts.size)
        assertEquals(100, drafts[0].title.length)
    }

    @Test
    fun `rejects non json content`() {
        val transport = FakeTransport(completion("好的，我帮你整理如下："))
        try {
            AiClient(transport).extract(AiConfig("https://example.com/v1", "m", "k"), "x")
            fail("must throw")
        } catch (e: IllegalStateException) {
            assertTrue("actual=" + e.message, e.message!!.contains("JSON"))
        }
    }

    @Test
    fun `requires configured api key`() {
        try {
            AiClient(FakeTransport("{}")).extract(AiConfig("https://example.com/v1", "m", ""), "x")
            fail("must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未配置"))
        }
    }
}
