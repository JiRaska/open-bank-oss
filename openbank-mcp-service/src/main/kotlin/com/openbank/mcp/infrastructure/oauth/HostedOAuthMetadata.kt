// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.TokenAuthentication
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor

/** The issuer attributed to the hosted transport, separate from the bank's PAR issuer. */
internal object HostedOAuthMetadata {
    fun document(enabled: Set<Vendor>): Map<String, Any>? {
        if (enabled.isEmpty()) return null
        val issuer = HostedOAuthComplete.ISSUER
        val authentication = enabled.flatMap { it.tokenAuthentication }.distinct().sorted()
        return mapOf(
            "issuer" to issuer,
            "authorization_endpoint" to "$issuer/authorize",
            "token_endpoint" to "$issuer/token",
            "response_types_supported" to listOf("code"),
            "grant_types_supported" to listOf("authorization_code", "refresh_token"),
            "code_challenge_methods_supported" to listOf("S256"),
            "scopes_supported" to listOf(HostedOAuthAdmission.OUTER_SCOPE),
            "token_endpoint_auth_methods_supported" to authentication.map(::metadataName),
            "client_id_metadata_document_supported" to (Vendor.CHATGPT in enabled),
            "require_pushed_authorization_requests" to false,
        )
    }

    private fun metadataName(authentication: TokenAuthentication): String = when (authentication) {
        TokenAuthentication.PRIVATE_KEY_JWT -> "private_key_jwt"
        TokenAuthentication.CLIENT_SECRET_BASIC -> "client_secret_basic"
        TokenAuthentication.CLIENT_SECRET_POST -> "client_secret_post"
    }
}
