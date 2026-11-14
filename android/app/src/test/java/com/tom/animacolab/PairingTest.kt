package com.tom.animacolab

import com.tom.animacolab.data.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {

    @Test
    fun parsesPairLine() {
        val p = Pairing.parse("PAIR: https://random-words.trycloudflare.com|tok_abc123")!!
        assertEquals("https://random-words.trycloudflare.com", p.baseUrl)
        assertEquals("tok_abc123", p.token)
    }

    @Test
    fun parsesUrlPipeToken() {
        val p = Pairing.parse("https://a.b.c|xyz")!!
        assertEquals("https://a.b.c", p.baseUrl)
        assertEquals("xyz", p.token)
    }

    @Test
    fun parsesTwoLines() {
        val p = Pairing.parse("https://tunnel.example.com\ntoken_here")!!
        assertEquals("https://tunnel.example.com", p.baseUrl)
        assertEquals("token_here", p.token)
    }

    @Test
    fun parsesJson() {
        val p = Pairing.parse("""{"url":"https://x.trycloudflare.com","token":"t1"}""")!!
        assertEquals("https://x.trycloudflare.com", p.baseUrl)
        assertEquals("t1", p.token)
    }

    @Test
    fun normalizesTrailingSlash() {
        assertEquals("https://x.y", Pairing.parse("PAIR: https://x.y/|t")!!.baseUrl)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Pairing.parse(""))
        assertNull(Pairing.parse("hello world"))
        assertNull(Pairing.parse("https://only-url.example.com"))
    }
}
