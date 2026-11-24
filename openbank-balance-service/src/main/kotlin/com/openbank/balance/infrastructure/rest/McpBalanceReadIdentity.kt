// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.rest

import io.vertx.core.http.HttpServerRequest
import jakarta.json.JsonString
import org.eclipse.microprofile.jwt.JsonWebToken
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/** Checks the authorization carried by an OIDC-verified JWT at the balance-service TLS boundary. */
internal object McpBalanceReadIdentity {
    private const val AUDIENCE = "openbank-balance-service"
    private const val SCOPE = "mcp-customer-balance-read"
    private const val ACTOR = "openbank-mcp-read-delegation"
    private const val MAX_CLIENT_ID_LENGTH = 255
    private const val CONNECTION_CLAIM = "openbank_mcp_connection_id"

    data class Binding(val partyId: UUID, val grantId: UUID, val clientId: String, val connectionId: UUID? = null)

    fun resolve(
        jwt: JsonWebToken,
        accountId: UUID,
        issuer: String,
        request: HttpServerRequest,
        connectionBindingEnabled: Boolean = false,
    ): Binding? = runCatching { checkedBinding(jwt, accountId, issuer, request, connectionBindingEnabled) }.getOrNull()

    private fun checkedBinding(
        jwt: JsonWebToken,
        accountId: UUID,
        issuer: String,
        request: HttpServerRequest,
        connectionBindingEnabled: Boolean,
    ): Binding? {
        if (!matchesTokenIdentity(jwt, issuer)) return null
        if (uuid(jwt.getClaim<Any?>("account_id")) != accountId || !matchesCertificate(jwt, request)) return null
        val party = uuid(jwt.getClaim<Any?>("party_id")) ?: return null
        val grant = uuid(jwt.getClaim<Any?>("grant_id")) ?: return null
        val actors = jwt.getClaim<Map<*, *>>("act") ?: return null
        if (claimText(actors["sub"]) != ACTOR) return null
        val client = claimText((actors["act"] as? Map<*, *>)?.get("sub"))
            ?.takeIf { it.isNotBlank() && it.length <= MAX_CLIENT_ID_LENGTH } ?: return null
        val connection = when {
            connectionBindingEnabled -> uuid(jwt.getClaim<Any?>(CONNECTION_CLAIM)) ?: return null
            CONNECTION_CLAIM in jwt.claimNames -> return null
            else -> null
        }
        return Binding(party, grant, client, connection)
    }

    private fun matchesTokenIdentity(jwt: JsonWebToken, issuer: String): Boolean = issuer.isNotBlank() &&
        jwt.issuer == issuer &&
        jwt.audience == setOf(AUDIENCE) &&
        !jwt.subject.isNullOrBlank() &&
        jwt.getClaim<String>("scope") == SCOPE &&
        jwt.getClaim<String>("azp") == ACTOR

    private fun matchesCertificate(jwt: JsonWebToken, request: HttpServerRequest): Boolean {
        if (!request.isSSL) return false
        val leaf = request.connection()?.peerCertificates()?.firstOrNull() as? X509Certificate ?: return false
        val expected = claimText(jwt.getClaim<Map<*, *>>("cnf")?.get("x5t#S256")) ?: return false
        val actual = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(leaf.encoded),
        )
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))
    }

    private fun uuid(value: Any?): UUID? = claimText(value)?.let { raw ->
        UUID.fromString(raw).takeIf { it.toString() == raw }
    }

    private fun claimText(value: Any?): String? = when (value) {
        is JsonString -> value.string
        is String -> value
        else -> null
    }
}
