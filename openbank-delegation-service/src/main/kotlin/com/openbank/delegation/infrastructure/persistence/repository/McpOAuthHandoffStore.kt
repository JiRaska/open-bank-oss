// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

/** Persistence only. Callers must authenticate the issuer, claimant and native proof separately. */
@ApplicationScoped
class McpOAuthHandoffStore(private val pool: Pool) {
    enum class BindingKind { HOSTED, DPOP }

    data class Binding(
        val requestRef: String,
        val issuer: String,
        val clientId: String,
        val resource: String,
        val redirectUri: String,
        val sessionRef: String,
        val kind: BindingKind,
        val parJkt: String?,
        val scopes: Set<String>,
    ) {
        init {
            require(SHA256.matches(requestRef) && SHA256.matches(sessionRef)) {
                "invalid handoff request or session reference"
            }
            require(issuer.isAbsoluteUri() && resource.isAbsoluteUri()) { "invalid handoff issuer or resource" }
            require(clientId.isNotBlank() && clientId.length <= MAX_CLIENT_LENGTH) { "invalid handoff client" }
            require(redirectUri.isAbsoluteUri(allowLoopback = true)) { "invalid handoff redirect URI" }
            require(
                when (kind) {
                    BindingKind.HOSTED -> parJkt == null
                    BindingKind.DPOP -> parJkt != null && JKT.matches(parJkt)
                },
            ) { "handoff client binding does not match its kind" }
            require(scopes.isNotEmpty() && scopes.none { it.isBlank() }) { "invalid handoff scopes" }
        }
    }

    data class Claim(val partyId: UUID, val keycloakSubject: String) {
        init {
            require(keycloakSubject.isNotBlank() && keycloakSubject.length <= MAX_CLIENT_LENGTH) {
                "invalid handoff subject"
            }
        }
    }

    data class Receipt(val identity: Claim, val contextSha256: String)

    /** Only the digest reaches this store. One guarded PAR request can issue at most one handoff. */
    suspend fun issue(tokenSha256: String, binding: Binding, expiresAt: OffsetDateTime): Boolean {
        validateDigest(tokenSha256)
        return pool.preparedQuery(ISSUE).execute(binding.parameters(tokenSha256).addOffsetDateTime(expiresAt))
            .awaitSuspending().rowCount() == 1
    }

    suspend fun claim(tokenSha256: String, claim: Claim): Boolean {
        validateDigest(tokenSha256)
        return pool.preparedQuery(CLAIM).execute(Tuple.of(tokenSha256, claim.partyId, claim.keycloakSubject))
            .awaitSuspending().rowCount() == 1
    }

    suspend fun markProven(tokenSha256: String, claim: Claim, contextSha256: String): Boolean {
        validateDigest(tokenSha256)
        validateDigest(contextSha256)
        return pool.preparedQuery(PROVE)
            .execute(Tuple.of(tokenSha256, claim.partyId, claim.keycloakSubject, contextSha256))
            .awaitSuspending().rowCount() == 1
    }

    /** Compare the complete issuing context and release the exact proved identity at most once. */
    suspend fun consume(tokenSha256: String, binding: Binding): Receipt? {
        validateDigest(tokenSha256)
        val row = pool.preparedQuery(CONSUME).execute(binding.parameters(tokenSha256))
            .awaitSuspending().firstOrNull() ?: return null
        val identity = Claim(row.getUUID("party_id"), row.getString("keycloak_subject"))
        return Receipt(identity, row.getString("context_sha256"))
    }

    private fun Binding.parameters(tokenSha256: String): Tuple = Tuple.tuple()
        .addString(tokenSha256)
        .addString(requestRef)
        .addString(issuer)
        .addString(clientId)
        .addString(resource)
        .addString(redirectUri)
        .addString(sessionRef)
        .addString(kind.name)
        .addString(parJkt)
        .addArrayOfString(scopes.sorted().toTypedArray())

    private companion object {
        const val MAX_CLIENT_LENGTH = 255
        const val MAX_URI_LENGTH = 2048
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val JKT = Regex("^[A-Za-z0-9_-]{43}$")
        val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]")

        fun validateDigest(value: String) = require(SHA256.matches(value)) { "invalid handoff digest" }

        fun String.isAbsoluteUri(allowLoopback: Boolean = false): Boolean {
            if (length > MAX_URI_LENGTH) return false
            val uri = runCatching { URI(this) }.getOrNull() ?: return false
            val loopbackRedirect = allowLoopback && uri.scheme == "http" && uri.host in LOOPBACK
            val allowedScheme = uri.scheme == "https" || loopbackRedirect
            return allowedScheme && uri.host != null && uri.userInfo == null && uri.fragment == null
        }

        const val ISSUE = """
            INSERT INTO mcp_oauth_handoffs
                (token_sha256, request_ref, issuer, client_id, resource, redirect_uri, session_ref, binding_kind,
                 par_jkt, scopes, expires_at)
            SELECT $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11::timestamptz
            WHERE $11::timestamptz > statement_timestamp()
                AND $11::timestamptz <= statement_timestamp() + INTERVAL '5 minutes'
            ON CONFLICT DO NOTHING
        """
        const val CLAIM = """
            UPDATE mcp_oauth_handoffs
            SET party_id = $2, keycloak_subject = $3, claimed_at = clock_timestamp()
            WHERE token_sha256 = $1 AND expires_at > clock_timestamp()
                AND claimed_at IS NULL AND consumed_at IS NULL
        """
        const val PROVE = """
            UPDATE mcp_oauth_handoffs
            SET context_sha256 = $4, proved_at = clock_timestamp()
            WHERE token_sha256 = $1 AND party_id = $2 AND keycloak_subject = $3
                AND expires_at > clock_timestamp() AND claimed_at IS NOT NULL
                AND proved_at IS NULL AND consumed_at IS NULL
        """
        const val CONSUME = """
            UPDATE mcp_oauth_handoffs
            SET consumed_at = clock_timestamp()
            WHERE token_sha256 = $1 AND request_ref = $2 AND issuer = $3 AND client_id = $4 AND resource = $5
                AND redirect_uri = $6 AND session_ref = $7 AND binding_kind = $8 AND par_jkt IS NOT DISTINCT FROM $9
                AND scopes = $10 AND expires_at > clock_timestamp()
                AND proved_at IS NOT NULL AND consumed_at IS NULL
            RETURNING party_id, keycloak_subject, context_sha256
        """
    }
}
