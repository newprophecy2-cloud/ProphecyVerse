package com.example

import com.example.player.PlayerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRedirectHeaderPolicyTest {
    @Test
    fun credentialLikeHeadersAreNotForwardedAcrossUncontrolledRedirects() {
        listOf(
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-API-Key",
            "X-Playback-Token",
            "X-Amz-Signature",
            "X-Client-Secret"
        ).forEach { assertTrue("$it must be treated as sensitive", PlayerManager.isRedirectSensitiveHeader(it)) }
    }

    @Test
    fun supportedNonSecretHeadersRemainForwardable() {
        listOf("Referer", "Origin", "User-Agent", "X-Requested-With").forEach {
            assertFalse("$it should remain supported", PlayerManager.isRedirectSensitiveHeader(it))
        }
    }
}
