package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebTest {
    private fun ip(vararg b: Int) = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })

    @Test
    fun privateAddressesAreBlocked() {
        assertTrue(isPrivateAddress(ip(127, 0, 0, 1)))
        assertTrue(isPrivateAddress(ip(10, 1, 2, 3)))
        assertTrue(isPrivateAddress(ip(192, 168, 1, 5)))
        assertTrue(isPrivateAddress(ip(172, 16, 0, 1)))
        assertTrue(isPrivateAddress(ip(169, 254, 169, 254)))
        assertTrue(isPrivateAddress(ip(100, 64, 0, 1)))
        assertTrue(isPrivateAddress(ip(0, 0, 0, 0)))
        assertTrue(isPrivateAddress(ip(198, 18, 0, 1)))
        assertTrue(isPrivateAddress(ip(255, 255, 255, 255)))
        // 6to4 (2002:7f00:0001::1 -> 127.0.0.1) dan NAT64 (64:ff9b::a00:1 -> 10.0.0.1)
        assertTrue(isPrivateAddress(ip(0x20, 0x02, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(isPrivateAddress(ip(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)))
        assertFalse(isPrivateAddress(ip(8, 8, 8, 8)))
        assertFalse(isPrivateAddress(ip(140, 82, 112, 3)))
    }

    @Test
    fun localHostNamesAreBlocked() {
        assertTrue(isBlockedHostName("localhost"))
        assertTrue(isBlockedHostName("nas.local"))
        assertTrue(isBlockedHostName("intranet"))
        assertTrue(isBlockedHostName("2130706433"))
        assertFalse(isBlockedHostName("example.com"))
    }

    @Test
    fun rejectsNonHttpAndCredentials() {
        listOf("file:///etc/passwd", "ftp://example.com/x", "https://user:pw@example.com/", "http://127.0.0.1:8080/").forEach {
            try {
                checkPublicUrl(it)
                throw AssertionError("harus ditolak: $it")
            } catch (e: ArgError) {
                // diharapkan
            }
        }
    }

    @Test
    fun htmlToTextKeepsContentAndDropsScripts() {
        val html = """<html><head><title>Judul &amp; Uji</title><style>p{color:red}</style></head>
            <body><script>var x = 1;</script><h1>Halo</h1><p>Isi <b>penting</b> &lt;ok&gt;&nbsp;di sini.</p>
            <ul><li>satu</li><li>dua</li></ul><a href="/doc">Dokumen</a><a href="#top">atas</a><a href="javascript:void(0)">js</a></body></html>"""
        val p = htmlToText(html, "https://example.com/a/b")
        assertEquals("Judul & Uji", p.title)
        assertTrue(p.text, p.text.contains("# Halo"))
        assertTrue(p.text, p.text.contains("Isi penting <ok> di sini."))
        assertTrue(p.text, p.text.contains("- satu") && p.text.contains("- dua"))
        assertFalse(p.text, p.text.contains("var x"))
        assertFalse(p.text, p.text.contains("color:red"))
        assertEquals(listOf("Dokumen" to "https://example.com/doc"), p.links)
    }

    @Test
    fun decodesNumericEntities() {
        assertEquals("A–B ©", decodeEntities("A&#8211;B &#xA9;"))
    }

    @Test
    fun parsesDuckDuckGoResults() {
        val html = """
            <div class="result"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F&amp;rut=abc">Kotlin &amp; Docs</a></h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F">Dokumentasi <b>resmi</b> Kotlin</a></div>
            <div class="result"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">Iklan</a></div>
            <div class="result"><a class="result__a" href='https://example.org/x'>Contoh</a>
            <a class="result__snippet" href='https://example.org/x'>Ringkasan contoh</a></div>"""
        val hits = parseSearchResults(html, 5)
        assertEquals(2, hits.size)
        assertEquals("Kotlin & Docs", hits[0].title)
        assertEquals("https://kotlinlang.org/docs/", hits[0].url)
        assertEquals("Dokumentasi resmi Kotlin", hits[0].snippet)
        assertEquals("https://example.org/x", hits[1].url)
        assertEquals("Ringkasan contoh", hits[1].snippet)
    }

    @Test
    fun registryHasWebToolsAndFetchNeedsNoApproval() {
        val reg = ToolRegistry.default(false)
        assertEquals(Risk.SAFE, reg.get("WebSearch")?.risk)
        assertEquals(Risk.SAFE, reg.get("WebFetch")?.risk)
        assertNotNull(reg.get("webfetch"))
        assertTrue(ToolRegistry.default(false, withWeb = false).get("WebSearch") == null)
    }
}

class SearchFallbackTest {
    private fun resp(body: String, code: Int = 200, ct: String = "text/html") = WebResponse("https://x", code, ct, body.toByteArray(), false)

    @Test
    fun parsesDuckDuckGoLite() {
        val html = """
            <table><tr><td>1.&nbsp;</td><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=1" class='result-link'>Kotlin &amp; Co</a></td></tr>
            <tr><td>&nbsp;</td><td class='result-snippet'>Bahasa <b>Kotlin</b></td></tr>
            <tr><td>2.&nbsp;</td><td><a rel="nofollow" href="https://example.org/a" class='result-link'>Contoh</a></td></tr></table>"""
        val hits = parseLiteResults(html, 5)
        assertEquals(2, hits.size)
        assertEquals("https://kotlinlang.org/", hits[0].url)
        assertEquals("Kotlin & Co", hits[0].title)
        assertEquals("Bahasa Kotlin", hits[0].snippet)
        assertEquals("https://example.org/a", hits[1].url)
    }

    @Test
    fun parsesBingAndUnwrapsTrackingUrl() {
        val target = "https://kotlinlang.org/docs/home.html"
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        val html = """
            <ol><li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=zz&amp;u=a1$b64&amp;ntb=1">Kotlin Docs</a></h2>
            <div class="b_caption"><p>Dokumentasi <strong>resmi</strong></p></div></li>
            <li class="b_algo"><h2><a href="https://example.com/b">Contoh B</a></h2><p>Ringkasan B</p></li></ol>"""
        val hits = parseBingResults(html, 5)
        assertEquals(2, hits.size)
        assertEquals(target, hits[0].url)
        assertEquals("Dokumentasi resmi", hits[0].snippet)
        assertEquals("https://example.com/b", hits[1].url)
    }

    @Test
    fun parsesWikipediaJson() {
        val json = """{"query":{"search":[{"title":"Pulau Jawa","snippet":"<span>Pulau</span> terpadat"}]}}"""
        val hits = parseWikipediaResults(json, "id", 5)
        assertEquals("https://id.wikipedia.org/wiki/Pulau_Jawa", hits[0].url)
        assertEquals("Pulau terpadat", hits[0].snippet)
        assertTrue(parseWikipediaResults("bukan json", "id", 5).isEmpty())
    }

    @Test
    fun fallsThroughEnginesUntilOneAnswers() {
        val calls = ArrayList<String>()
        val (out, problems) = kotlinx.coroutines.runBlocking {
            searchWithFallback("kotlin", 5) { req ->
                val url = req.url
                calls.add(url)
                when {
                    "html.duckduckgo.com" in url -> resp("<html>captcha anomaly</html>")
                    "lite.duckduckgo.com" in url -> throw LlmException("Jaringan: gagal", true)
                    "bing.com" in url -> resp("""<li class="b_algo"><h2><a href="https://example.com/ok">Ok</a></h2><p>isi</p></li>""")
                    else -> resp("{}")
                }
            }
        }
        assertEquals("Bing", out?.engine)
        assertEquals("https://example.com/ok", out?.hits?.first()?.url)
        assertEquals(3, calls.size)
        assertEquals(2, problems.size)
        assertTrue(problems[0].contains("captcha"))
    }

    @Test
    fun reportsEveryProblemWhenAllEnginesFail() {
        val (out, problems) = kotlinx.coroutines.runBlocking {
            searchWithFallback("kotlin", 5) { _ -> throw LlmException(explainTlsFailure(javax.net.ssl.SSLException("Chain validation failed")), false) }
        }
        assertEquals(null, out)
        assertEquals(5, problems.size)
        assertTrue(problems.all { it.contains("HTTPS") })
    }
}

class KeyedSearchTest {
    private fun resp(body: String, code: Int = 200) = WebResponse("https://x", code, "application/json", body.toByteArray(), false)

    @Test
    fun parsesBraveAndTavily() {
        val brave = """{"web":{"results":[{"title":"<b>Kotlin</b>","url":"https://kotlinlang.org","description":"Bahasa <strong>modern</strong>"}]}}"""
        assertEquals("Kotlin", parseBraveResults(brave, 5)[0].title)
        assertEquals("Bahasa modern", parseBraveResults(brave, 5)[0].snippet)
        val tavily = """{"results":[{"title":"Docs","url":"https://example.com/d","content":"isi panjang"}]}"""
        assertEquals("https://example.com/d", parseTavilyResults(tavily, 5)[0].url)
        assertTrue(parseBraveResults("x", 5).isEmpty() && parseTavilyResults("x", 5).isEmpty())
    }

    @Test
    fun keyedEnginesGoFirstAndSendCredentialsInHeaders() {
        val seen = ArrayList<SearchRequest>()
        val (out, _) = kotlinx.coroutines.runBlocking {
            searchWithFallback("kotlin", 5, SearchKeys(brave = "BKEY", tavily = "TKEY")) { req ->
                seen.add(req)
                if ("tavily" in req.url) resp("""{"results":[{"title":"T","url":"https://t.example/x","content":"c"}]}""") else resp("{}", 429)
            }
        }
        assertEquals("Tavily", out?.engine)
        assertEquals(2, seen.size)
        assertEquals("BKEY", seen[0].headers["X-Subscription-Token"])
        assertEquals("Bearer TKEY", seen[1].headers["Authorization"])
        assertTrue(seen[1].body!!.contains("kotlin"))
        assertTrue(seen.none { it.url.contains("KEY") })   // kunci tidak pernah masuk URL
    }

    @Test
    fun problemsNeverContainKeys() {
        val (out, problems) = kotlinx.coroutines.runBlocking {
            searchWithFallback("kotlin", 5, SearchKeys(brave = "BKEY")) { resp("{}", 401) }
        }
        assertEquals(null, out)
        assertTrue(problems[0].contains("periksa kunci API"))
        assertTrue(problems.none { it.contains("BKEY") })
    }
}
