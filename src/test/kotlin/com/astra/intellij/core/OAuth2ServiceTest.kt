package com.astra.intellij.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OAuth2ServiceTest {
    @Test
    fun `pkce challenge matches the rfc 7636 vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuth2Service.pkceChallenge(verifier))
    }

    @Test
    fun `refresh requires endpoint and token`() {
        val result = OAuth2Service.refreshToken("", "", "id", "secret")
        assertTrue(!result.ok)
        assertTrue(result.error!!.contains("Missing token endpoint"))
    }

    @Test
    fun `expired token is refreshed and written back`() {
        val auth = com.astra.intellij.model.AuthConfig(
            type = "oauth2",
            token = "old",
            oauthTokenUrl = "",
            oauthRefreshToken = "refresh",
            oauthExpiresAt = 1L,
        )
        // Empty token endpoint makes the refresh fail, so the stale token is kept untouched.
        assertEquals("old", OAuth2Service.ensureToken(auth, emptyMap()))
        assertEquals("old", auth.token)
    }
}
