// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthStart.Companion.MAX_VENDOR_STATE_LENGTH
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Fail-closed public OAuth request policy for the bank facade. Bank-side admission is separate
 * and still requires authenticated PAR, S256 and DPoP. Transport must reject duplicate form
 * parameters before constructing an authorization request.
 */
internal object HostedOAuthAdmission {
    const val RESOURCE = "https://mcp.open-bank.tech/customer/hosted-mcp"
    const val BANK_RESOURCE = "https://mcp.open-bank.tech/customer/mcp"
    const val RESOURCE_PATH = "/customer/hosted-mcp"
    const val METADATA_PATH = "/.well-known/oauth-protected-resource/customer/hosted-mcp"
    const val METADATA_URL = "https://mcp.open-bank.tech$METADATA_PATH"
    const val BANK_CALLBACK = "https://mcp.open-bank.tech/oauth/hosted/callback"
    const val OUTER_SCOPE = "mcp:balance:read"
    const val BANK_SCOPE = "openid"

    private val s256Challenge = Regex("[A-Za-z0-9_-]{43}")
    private val pkceVerifier = Regex("[A-Za-z0-9._~-]{43,128}")

    enum class TokenAuthentication {
        PRIVATE_KEY_JWT,
        CLIENT_SECRET_BASIC,
        CLIENT_SECRET_POST,
    }

    enum class Vendor(
        val clientId: String,
        val callback: String,
        val bankClientId: String,
        val tokenAuthentication: Set<TokenAuthentication>,
    ) {
        CHATGPT(
            "https://chatgpt.com/oauth/client.json",
            "https://chatgpt.com/connector_platform_oauth_redirect",
            "mcp-bank-hosted-chatgpt",
            setOf(TokenAuthentication.PRIVATE_KEY_JWT),
        ),
        CLAUDE(
            "mcp-claude-sandbox",
            "https://claude.ai/api/mcp/auth_callback",
            "mcp-bank-hosted-claude",
            setOf(TokenAuthentication.CLIENT_SECRET_BASIC, TokenAuthentication.CLIENT_SECRET_POST),
        ),
    }

    // Use ordinary classes so accidental string interpolation cannot print state or PKCE material.
    class AuthorizationRequest(
        val clientId: String?,
        val redirectUri: String?,
        val responseType: String?,
        val codeChallenge: String?,
        val codeChallengeMethod: String?,
        val state: String?,
        val scope: String?,
        val resource: String?,
    )

    class Authorization internal constructor(val vendor: Vendor, val state: String, val challenge: String)

    fun admit(request: AuthorizationRequest, enabled: Set<Vendor> = emptySet()): Authorization? {
        val vendor = enabled.singleOrNull { it.clientId == request.clientId && it.callback == request.redirectUri }
            ?: return null
        val state = request.state?.takeIf { it.length in 1..MAX_VENDOR_STATE_LENGTH && it.all { c -> c in '!'..'~' } }
            ?: return null
        val challenge = request.codeChallenge?.takeIf(s256Challenge::matches) ?: return null
        val challengeBytes = Base64.getUrlDecoder().decode(challenge)
        val canonicalChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)
        if (canonicalChallenge != challenge) return null
        if (request.responseType != "code" || request.codeChallengeMethod != "S256") return null
        if (request.resource != RESOURCE || request.scope != OUTER_SCOPE) return null
        return Authorization(vendor, state, challenge)
    }

    /**
     * [authenticatedVendor] must be derived by the token endpoint's verified client authenticator.
     * A caller-supplied client_id is not an authenticated identity.
     */
    fun matchesExchange(
        authorization: Authorization,
        authenticatedVendor: Vendor,
        redirectUri: String?,
        resource: String?,
        verifier: String?,
    ): Boolean {
        if (authorization.vendor != authenticatedVendor || redirectUri != authenticatedVendor.callback) return false
        if (resource != RESOURCE || verifier == null || !pkceVerifier.matches(verifier)) return false
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return MessageDigest.isEqual(Base64.getUrlDecoder().decode(authorization.challenge), hash)
    }
}
