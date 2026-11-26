// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.libs.security.McpConnectContext
import com.openbank.libs.security.McpConnectContextV2
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Persistence only. Callers must authenticate the issuer, claimant and native proof separately. */
@ApplicationScoped
class McpOAuthHandoffStore(private val pool: Pool, private val requests: McpConnectRequestStore) {
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
        val expiresAtEpochSecond: Long,
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
            require(scopes.isNotEmpty() && scopes.none { it.isNullOrBlank() }) { "invalid handoff scopes" }
            require(expiresAtEpochSecond in 1..MAX_EXPIRES_AT) { "invalid handoff expiry" }
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

    data class SelectedBinding(
        val requestRef: String,
        val contextSha256: String,
        val clientId: String,
        val kind: BindingKind,
        val parJkt: String?,
        val expiresAtEpochSecond: Long,
    )

    /** Only the digest reaches this store. One guarded PAR request can issue at most one handoff. */
    suspend fun issue(tokenSha256: String, binding: Binding): Boolean {
        validateDigest(tokenSha256)
        return pool.preparedQuery(ISSUE).execute(binding.parameters(tokenSha256))
            .awaitSuspending().rowCount() == 1
    }

    /** A committed claim and its app-visible Connect record always appear together. */
    suspend fun claimAndRegister(tokenSha256: String, claim: Claim): String? {
        validateDigest(tokenSha256)
        return try {
            pool.withTransaction { connection ->
                connection.preparedQuery(CLAIM).execute(Tuple.of(tokenSha256, claim.partyId, claim.keycloakSubject))
                    .flatMap { rows ->
                        val row = rows.firstOrNull() ?: return@flatMap Uni.createFrom().nullItem<String>()
                        val request = row.connectRequest(claim.partyId)
                        requests.register(connection, request).map { registered ->
                            if (!registered) throw RegistrationConflict()
                            request.requestRef
                        }
                    }
            }.awaitSuspending()
        } catch (_: RegistrationConflict) {
            null
        }
    }

    /** The issuer gets only the provisional party and must prove the original browser binding. */
    suspend fun claimedParty(tokenSha256: String, binding: Binding): UUID? {
        validateDigest(tokenSha256)
        return pool.preparedQuery(CLAIMED_PARTY).execute(binding.parameters(tokenSha256))
            .awaitSuspending().firstOrNull()?.getUUID("party_id")
    }

    /** The already immutable account selection makes this attestation independent of SCA binding. */
    suspend fun attestSelectedContext(tokenSha256: String, binding: Binding, contextSha256: String): Boolean {
        validateDigest(tokenSha256)
        validateDigest(contextSha256)
        val parameters = binding.parameters(tokenSha256)
        return pool.withTransaction { connection ->
            connection.preparedQuery(SELECTED_ACCOUNTS).execute(parameters).flatMap { rows ->
                val first = rows.firstOrNull() ?: return@flatMap Uni.createFrom().item(false)
                val request = first.connectRequest(first.getUUID("party_id"))
                val accounts = rows.map { row ->
                    McpConnectContextV2.AccountScope(
                        row.getUUID("account_id").toString(),
                        row.getArrayOfStrings("capabilities").toList(),
                    )
                }
                if (request.selectedContextDigest(accounts) != contextSha256) {
                    return@flatMap Uni.createFrom().item(false)
                }
                connection.preparedQuery(ATTEST).execute(parameters.addString(contextSha256))
                    .map { it.rowCount() == 1 }
            }
        }.awaitSuspending()
    }

    suspend fun selectedBinding(requestRef: String, claim: Claim): SelectedBinding? {
        validateDigest(requestRef)
        val row = pool.preparedQuery(APP_BINDING).execute(Tuple.of(requestRef, claim.partyId, claim.keycloakSubject))
            .awaitSuspending().firstOrNull() ?: return null
        return SelectedBinding(
            row.getString("request_ref"),
            row.getString("selected_context_sha256"),
            row.getString("client_id"),
            BindingKind.valueOf(row.getString("binding_kind")),
            row.getString("par_jkt"),
            row.getOffsetDateTime("expires_at").toEpochSecond(),
        )
    }

    suspend fun markProven(requestRef: String, claim: Claim, contextSha256: String): Boolean {
        validateDigest(requestRef)
        validateDigest(contextSha256)
        return pool.preparedQuery(PROVE)
            .execute(Tuple.of(requestRef, claim.partyId, claim.keycloakSubject, contextSha256))
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

    private class RegistrationConflict : RuntimeException()

    private fun Row.connectRequest(party: UUID): McpConnectRequestStore.Request = McpConnectRequestStore.Request(
        requestRef = getString("request_ref"),
        partyId = party,
        clientId = getString("client_id"),
        parJkt = getString("par_jkt"),
        redirectHost = URI.create(getString("redirect_uri")).host.lowercase(),
        resource = getString("resource"),
        scopes = getArrayOfStrings("scopes").toSet(),
        expiresAt = getOffsetDateTime("expires_at"),
    )

    private fun McpConnectRequestStore.Request.selectedContextDigest(
        accounts: List<McpConnectContextV2.AccountScope>,
    ): String {
        val base = McpConnectContext(
            clientId,
            redirectHost,
            resource,
            scopes.sorted().joinToString(" "),
            requestRef,
            expiresAt.toEpochSecond(),
        )
        return McpConnectContextV2.of(base, partyId.toString(), parJkt, accounts).payloadSha256()
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
        .addOffsetDateTime(OffsetDateTime.ofInstant(Instant.ofEpochSecond(expiresAtEpochSecond), ZoneOffset.UTC))

    private companion object {
        const val MAX_CLIENT_LENGTH = 255
        const val MAX_URI_LENGTH = 2048
        const val MAX_EXPIRES_AT = 253_402_300_799L // Last epoch second in ISO year 9999.
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
            SET party_id = $2, keycloak_subject = $3, claimed_at = coalesce(claimed_at, clock_timestamp())
            WHERE token_sha256 = $1 AND expires_at > clock_timestamp() AND consumed_at IS NULL
                AND (claimed_at IS NULL OR (party_id = $2 AND keycloak_subject = $3))
            RETURNING request_ref, client_id, par_jkt, redirect_uri, resource, scopes, expires_at
        """
        const val EXACT_BINDING = """
            h.token_sha256 = $1 AND h.request_ref = $2 AND h.issuer = $3 AND h.client_id = $4 AND h.resource = $5
                AND h.redirect_uri = $6 AND h.session_ref = $7 AND h.binding_kind = $8
                AND h.par_jkt IS NOT DISTINCT FROM $9 AND h.scopes = $10 AND h.expires_at = $11
                AND h.expires_at > clock_timestamp() AND h.consumed_at IS NULL
        """
        const val CONNECT_MATCH = """
            r.request_ref = h.request_ref AND r.party_id = h.party_id AND r.client_id = h.client_id
                AND r.par_jkt IS NOT DISTINCT FROM h.par_jkt AND r.resource = h.resource
                AND r.scopes = h.scopes AND r.expires_at = h.expires_at
        """
        const val CLAIMED_PARTY = """
            SELECT h.party_id FROM mcp_oauth_handoffs AS h
            JOIN mcp_connect_requests AS r ON $CONNECT_MATCH
            WHERE $EXACT_BINDING
        """
        const val SELECTED_ACCOUNTS = """
            SELECT h.request_ref, h.party_id, h.client_id, h.par_jkt, h.redirect_uri, h.resource,
                h.scopes, h.expires_at, a.account_id, a.capabilities
            FROM mcp_oauth_handoffs AS h
            JOIN mcp_connect_requests AS r ON $CONNECT_MATCH
            JOIN mcp_connect_selected_accounts AS a ON a.request_ref = r.request_ref
            WHERE $EXACT_BINDING
        """
        const val ATTEST = """
            UPDATE mcp_oauth_handoffs AS h
            SET selected_context_sha256 = $12
            WHERE $EXACT_BINDING AND claimed_at IS NOT NULL
                AND (selected_context_sha256 IS NULL OR selected_context_sha256 = $12)
        """
        const val APP_BINDING = """
            SELECT h.request_ref, h.selected_context_sha256, h.client_id, h.binding_kind, h.par_jkt, h.expires_at
            FROM mcp_oauth_handoffs AS h
            JOIN mcp_connect_requests AS r ON $CONNECT_MATCH
            WHERE h.request_ref = $1 AND h.party_id = $2 AND h.keycloak_subject = $3
                AND h.expires_at > clock_timestamp() AND h.consumed_at IS NULL
                AND h.selected_context_sha256 IS NOT NULL
        """
        const val PROVE = """
            UPDATE mcp_oauth_handoffs AS h
            SET context_sha256 = $4, proved_at = clock_timestamp()
            WHERE h.request_ref = $1 AND h.party_id = $2 AND h.keycloak_subject = $3
                AND h.expires_at > clock_timestamp() AND h.claimed_at IS NOT NULL
                AND h.proved_at IS NULL AND h.consumed_at IS NULL AND h.selected_context_sha256 = $4
                AND EXISTS (SELECT 1 FROM mcp_connect_requests AS r WHERE $CONNECT_MATCH)
        """
        const val CONSUME = """
            UPDATE mcp_oauth_handoffs AS h
            SET consumed_at = clock_timestamp()
            WHERE $EXACT_BINDING AND h.proved_at IS NOT NULL
            RETURNING party_id, keycloak_subject, context_sha256
        """
    }
}
