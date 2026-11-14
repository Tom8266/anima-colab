package com.tom.animacolab

import com.tom.animacolab.colab.GoogleAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthTest {

    @Test
    fun codeChallengeMatchesPkceS256() {
        // RFC 7636 附录 B 同款算法：verifier "abc" 的 S256 challenge
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", GoogleAuth.codeChallenge("abc"))
    }

    @Test
    fun extractCodeHandlesRawCode() {
        assertEquals("4/0AbCdEf_123-xyz", GoogleAuth.extractCode("4/0AbCdEf_123-xyz"))
        assertEquals(" 4/0AbCdEf_123-xyz".trim(), GoogleAuth.extractCode("PAIR: 4/0AbCdEf_123-xyz"))
    }

    @Test
    fun extractCodeHandlesRedirectUrl() {
        assertEquals(
            "4/0AbCdEf_123-xyz",
            GoogleAuth.extractCode("https://sdk.cloud.google.com/applicationdefaultauthcode.html?code=4%2F0AbCdEf_123-xyz&scope=abc"),
        )
    }

    @Test
    fun extractCodeRejectsEmpty() {
        assertEquals("", GoogleAuth.extractCode(""))
        assertEquals("", GoogleAuth.extractCode("   "))
    }

    @Test
    fun verifierAndChallengeArePkceSafe() {
        val verifier = GoogleAuth.randomVerifier()
        assertTrue("verifier 长度应在 RFC 允许范围", verifier.length in 43..128)
        assertFalse("verifier 不能有非法字符", verifier.any { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })
        val challenge = GoogleAuth.codeChallenge(verifier)
        assertTrue("challenge 应为 43 字符 base64url", challenge.length == 43)
        assertFalse(challenge.contains("="))
    }
}
