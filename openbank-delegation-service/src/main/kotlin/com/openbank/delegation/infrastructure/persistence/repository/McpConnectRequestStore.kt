// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.PaymentProposalRepository
import com.openbank.delegation.domain.model.DelegationGrant
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

/** A PAR reference identifies one connect request for its entire lifetime. */
@ApplicationScoped
@Suppress("TooManyFunctions") // All stages serialize on the same persisted connect request.
class McpConnectRequestStore(private val pool: Pool, private val proposals: PaymentProposalRepository) {
    data class Request(
        val requestRef: String,
        val partyId: UUID,
        val clientId: String,
        val parJkt: String?,
        val redirectHost: String,
        val resource: String,
        val scopes: Set<String>,
        val expiresAt: OffsetDateTime,
    )

    data class Binding(val partyId: UUID, val clientId: String, val parJkt: String?)

    data class Challenge(val challengeId: UUID, val contextSha256: String)

    suspend fun bindChallenge(requestRef: String, binding: Binding, challenge: Challenge): Boolean =
        pool.preparedQuery(BIND_CHALLENGE).execute(
            Tuple.of(
                requestRef,
                binding.partyId,
                binding.clientId,
                binding.parJkt,
                challenge.challengeId,
                challenge.contextSha256,
            ),
        ).awaitSuspending().rowCount() == 1

    data class StageBinding(
        val requestRef: String,
        val binding: Binding,
        val challenge: Challenge,
        val codeBindingRef: String,
    ) {
        fun parameters(): Tuple = Tuple.of(
            requestRef,
            binding.partyId,
            binding.clientId,
            binding.parJkt,
            challenge.challengeId,
            challenge.contextSha256,
        ).addString(codeBindingRef)
    }

    suspend fun stage(stage: StageBinding): Boolean = pool.preparedQuery(STAGE).execute(stage.parameters())
        .awaitSuspending().rowCount() == 1

    suspend fun findStaged(binding: Binding, codeBindingRef: String): Review? = pool.preparedQuery(FIND_STAGED)
        .execute(Tuple.of(binding.partyId, binding.clientId, binding.parJkt, codeBindingRef))
        .map { rows -> rows.firstOrNull()?.review(rows.mapNotNull { it.accountSelection() }) }
        .awaitSuspending()

    /**
     * The activation receipt, account grants and their connection links commit together. The
     * existing party-grant outbox is deliberately not used for this distinct grantee class.
     */
    suspend fun activate(stage: StageBinding, refreshFamilyRef: String): UUID? = pool.withTransaction { connection ->
        val args = stage.parameters().addString(refreshFamilyRef)
        val grantee = DelegationGrant.mcpClientGranteeId(stage.binding.clientId)
        connection.preparedQuery(ACTIVATE).execute(args.addUUID(grantee)).flatMap {
            connection.preparedQuery(ACTIVATION_RECEIPT)
                .execute(stage.parameters().addString(refreshFamilyRef))
        }.map { rows -> rows.firstOrNull()?.getUUID("connection_id") }
    }.awaitSuspending()

    suspend fun activeFamily(binding: Binding, refreshFamilyRef: String): UUID? = pool.preparedQuery(ACTIVE_FAMILY)
        .execute(Tuple.of(binding.partyId, binding.clientId, binding.parJkt, refreshFamilyRef))
        .map { rows -> rows.firstOrNull()?.getUUID("connection_id") }
        .awaitSuspending()

    suspend fun revoke(partyId: UUID, connectionId: UUID): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any?>>(REVOKE)
                .setParameter("party", partyId)
                .setParameter("connection", connectionId)
                .resultList
                .flatMap { rows -> rejectPending(rows) }
        }
    }.awaitSuspending()

    private fun rejectPending(rows: List<Array<Any?>>): Uni<Boolean> {
        if (rows.isEmpty()) return Uni.createFrom().item(false)
        var closed = Uni.createFrom().voidItem()
        rows.forEach { row ->
            val grantId = row[0]?.let { it as UUID }
            val revokedAt = OffsetDateTime.parse(row[1] as String)
            if (grantId != null) {
                closed = closed.flatMap { proposals.rejectPendingForGrant(grantId, revokedAt) }
            }
        }
        return closed.replaceWith(true)
    }

    data class AccountSelection(val accountId: UUID, val capabilities: Set<String>)

    data class Review(
        val request: Request,
        val accounts: List<AccountSelection>,
        val challengeId: UUID?,
        val contextSha256: String?,
        val connectionId: UUID?,
    )

    enum class McpConnectionStatus {
        ACTIVE,
        REVOKED,
        EXPIRED,
    }

    data class Connection(
        val connectionId: UUID,
        val partyId: UUID,
        val clientId: String,
        val resource: String,
        val expiresAtEpochSecond: Long,
        val status: McpConnectionStatus,
        val accounts: List<AccountSelection>,
    )

    suspend fun listConnections(partyId: UUID): List<Connection> = pool.preparedQuery(LIST_CONNECTIONS)
        .execute(Tuple.of(partyId))
        .map { rows ->
            rows.groupBy { it.getUUID("connection_id") }.map { (connectionId, group) ->
                val row = group.first()
                Connection(
                    connectionId,
                    partyId,
                    row.getString("client_id"),
                    row.getString("resource"),
                    row.getOffsetDateTime("grant_valid_until").toEpochSecond(),
                    McpConnectionStatus.valueOf(row.getString("connection_status")),
                    group.mapNotNull { it.accountSelection() },
                )
            }
        }.awaitSuspending()

    suspend fun listForParty(partyId: UUID): List<Review> = pool.preparedQuery(LIST_REVIEWS)
        .execute(Tuple.of(partyId))
        .map { rows ->
            rows.groupBy { it.getString("request_ref") }.values.map { group ->
                group.first().review(group.mapNotNull { it.accountSelection() })
            }
        }.awaitSuspending()

    suspend fun findForParty(partyId: UUID, requestRef: String): Review? =
        pool.preparedQuery(FIND_REVIEW).execute(Tuple.of(partyId, requestRef))
            .map { rows -> rows.firstOrNull()?.review(rows.mapNotNull { it.accountSelection() }) }
            .awaitSuspending()

    /**
     * The application validates current ownership before passing the selection. The request lock
     * serializes selection with challenge binding and activation for this authenticated party.
     */
    suspend fun select(partyId: UUID, requestRef: String, accounts: List<AccountSelection>): Boolean {
        require(accounts.isNotEmpty() && accounts.distinctBy { it.accountId }.size == accounts.size)
        return pool.withTransaction { connection ->
            connection.preparedQuery(LOCK_REVIEW).execute(Tuple.of(partyId, requestRef)).flatMap { rows ->
                val review = rows.firstOrNull() ?: return@flatMap Uni.createFrom().item(false)
                selectLocked(connection, requestRef, accounts, review.getUUID("challenge_id") == null)
            }
        }.awaitSuspending()
    }

    private fun selectLocked(
        connection: SqlConnection,
        requestRef: String,
        accounts: List<AccountSelection>,
        canSelect: Boolean,
    ): Uni<Boolean> = connection.preparedQuery(EXISTING_SELECTION).execute(Tuple.of(requestRef)).flatMap { rows ->
        val prior = rows.mapNotNull { it.accountSelection() }
        when {
            prior.isNotEmpty() -> Uni.createFrom().item(prior.toSet() == accounts.toSet())

            !canSelect -> Uni.createFrom().item(false)

            else -> {
                val selection = accounts.map { account ->
                    Tuple.of(requestRef, account.accountId, account.capabilities.sorted().toTypedArray())
                }
                connection.preparedQuery(INSERT_SELECTION).executeBatch(selection).replaceWith(true)
            }
        }
    }

    private fun Row.accountSelection(): AccountSelection? = getUUID("account_id")?.let {
        AccountSelection(it, getArrayOfStrings("capabilities").toSet())
    }

    private fun Row.review(accounts: List<AccountSelection>): Review = Review(
        request = Request(
            requestRef = getString("request_ref"),
            partyId = getUUID("party_id"),
            clientId = getString("client_id"),
            parJkt = getString("par_jkt"),
            redirectHost = getString("redirect_host"),
            resource = getString("resource"),
            scopes = getArrayOfStrings("scopes").toSet(),
            expiresAt = getOffsetDateTime("expires_at"),
        ),
        accounts = accounts,
        challengeId = getUUID("challenge_id"),
        contextSha256 = getString("context_sha256"),
        connectionId = getUUID("connection_id"),
    )

    /**
     * Register the verified browser session. A retry can read back the original binding.
     * No caller can overwrite it or extend its authorization window.
     */
    suspend fun register(request: Request): Boolean = pool.withTransaction { connection ->
        register(connection, request)
    }.awaitSuspending()

    internal fun register(connection: SqlConnection, request: Request): Uni<Boolean> {
        val binding = Tuple.tuple()
            .addString(request.requestRef)
            .addUUID(request.partyId)
            .addString(request.clientId)
            .addString(request.parJkt)
            .addString(request.redirectHost)
            .addString(request.resource)
            .addArrayOfString(request.scopes.sorted().toTypedArray())
            .addOffsetDateTime(request.expiresAt)
        return connection.preparedQuery(REGISTER).execute(binding)
            .flatMap { connection.preparedQuery(READ).execute(Tuple.of(request.requestRef)) }
            .map { rows -> rows.firstOrNull()?.matches(request) == true }
    }

    private fun Row.matches(expected: Request): Boolean = getUUID("party_id") == expected.partyId &&
        getString("client_id") == expected.clientId &&
        getString("par_jkt") == expected.parJkt &&
        getString("redirect_host") == expected.redirectHost &&
        getString("resource") == expected.resource &&
        getArrayOfStrings("scopes").toSet() == expected.scopes &&
        getOffsetDateTime("expires_at").isEqual(expected.expiresAt)

    private companion object {
        const val BIND_CHALLENGE = """
            UPDATE mcp_connect_requests AS r
            SET challenge_id = $5, context_sha256 = $6
            WHERE r.request_ref = $1 AND r.party_id = $2 AND r.client_id = $3 AND r.par_jkt IS NOT DISTINCT FROM $4
              AND r.expires_at > statement_timestamp()
              AND (r.challenge_id IS NULL OR (r.challenge_id = $5 AND r.context_sha256 = $6))
              AND EXISTS (SELECT 1 FROM mcp_connect_selected_accounts AS a WHERE a.request_ref = r.request_ref)
        """
        const val STAGE = """
            UPDATE mcp_connect_requests
            SET code_binding_ref = $7, staged_at = coalesce(staged_at, statement_timestamp())
            WHERE request_ref = $1 AND party_id = $2 AND client_id = $3 AND par_jkt IS NOT DISTINCT FROM $4
              AND challenge_id = $5 AND context_sha256 = $6
              AND expires_at > statement_timestamp() AND connection_id IS NULL
              AND (code_binding_ref IS NULL OR code_binding_ref = $7)
        """
        const val FIND_STAGED = """
            SELECT r.*, a.account_id, a.capabilities
            FROM mcp_connect_requests AS r
            JOIN mcp_connect_selected_accounts AS a USING (request_ref)
            WHERE r.party_id = $1 AND r.client_id = $2 AND r.par_jkt IS NOT DISTINCT FROM $3 AND r.code_binding_ref = $4
              AND r.expires_at > statement_timestamp() AND r.connection_id IS NULL
            ORDER BY a.account_id
        """
        const val ACTIVATE = """
            WITH activated AS (
                UPDATE mcp_connect_requests
                SET refresh_family_ref = $8, connection_id = gen_random_uuid(),
                    activated_at = statement_timestamp(), grant_valid_until = statement_timestamp() + interval '180 days'
                WHERE request_ref = $1 AND party_id = $2 AND client_id = $3 AND par_jkt IS NOT DISTINCT FROM $4
                  AND challenge_id = $5 AND context_sha256 = $6 AND code_binding_ref = $7
                  AND expires_at > statement_timestamp() AND connection_id IS NULL
                RETURNING *
            ), selection AS MATERIALIZED (
                SELECT r.*, a.account_id, a.capabilities, gen_random_uuid() AS grant_id
                FROM activated AS r JOIN mcp_connect_selected_accounts AS a USING (request_ref)
            ), grants AS (
                INSERT INTO delegation_grants (
                    id, grantor_party_id, grantee_party_id, grantee_type, grantee_client_id, grantee_key_thumbprint,
                    resource_type, resource_id, valid_from, valid_to, status, lifecycle_revision, grant_sca_session_id,
                    mcp_connection_id
                )
                SELECT grant_id, party_id, $9, 'MCP_CLIENT', client_id, par_jkt, 'ACCOUNT', account_id,
                       activated_at, grant_valid_until, 'ACTIVE', 1, challenge_id, connection_id
                FROM selection
                RETURNING id
            ), capabilities AS (
                INSERT INTO delegation_capabilities (grant_id, capability)
                SELECT g.id, unnest(s.capabilities) FROM grants AS g JOIN selection AS s ON s.grant_id = g.id
            )
            UPDATE mcp_connect_selected_accounts AS a SET grant_id = g.id
            FROM grants AS g JOIN selection AS s ON s.grant_id = g.id
            WHERE a.request_ref = s.request_ref AND a.account_id = s.account_id
        """
        const val ACTIVATION_RECEIPT = """
            SELECT r.connection_id FROM mcp_connect_requests AS r
            WHERE r.request_ref = $1 AND r.party_id = $2 AND r.client_id = $3 AND r.par_jkt IS NOT DISTINCT FROM $4
              AND r.challenge_id = $5 AND r.context_sha256 = $6 AND r.code_binding_ref = $7
              AND r.refresh_family_ref = $8 AND r.revoked_at IS NULL AND r.grant_valid_until > statement_timestamp()
              AND EXISTS (SELECT 1 FROM mcp_connect_selected_accounts AS a
                          WHERE a.request_ref = r.request_ref AND a.grant_id IS NOT NULL)
        """
        const val ACTIVE_FAMILY = """
            SELECT connection_id FROM mcp_connect_requests
            WHERE party_id = $1 AND client_id = $2 AND par_jkt IS NOT DISTINCT FROM $3 AND refresh_family_ref = $4
              AND revoked_at IS NULL AND grant_valid_until > statement_timestamp()
        """
        const val REVOKE = """
            WITH revoked AS (
                UPDATE mcp_connect_requests
                SET revoked_at = coalesce(revoked_at, statement_timestamp())
                WHERE party_id = :party AND connection_id = :connection
                RETURNING connection_id, revoked_at
            ), grants AS (
                UPDATE delegation_grants AS g
                SET status = 'REVOKED', closed_at = r.revoked_at, updated_at = r.revoked_at,
                    closed_by = :party, closed_reason = 'MCP connection revoked'
                FROM revoked AS r
                WHERE g.mcp_connection_id = r.connection_id AND g.status IN ('ACTIVE', 'SUSPENDED')
            )
            SELECT g.id, to_char(r.revoked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
            FROM revoked AS r
            LEFT JOIN delegation_grants AS g ON g.mcp_connection_id = r.connection_id
        """
        const val LIST_CONNECTIONS = """
            SELECT r.connection_id, r.client_id, r.resource, r.grant_valid_until, a.account_id, a.capabilities,
                CASE
                    WHEN r.revoked_at IS NOT NULL THEN 'REVOKED'
                    WHEN r.grant_valid_until <= statement_timestamp() THEN 'EXPIRED'
                    ELSE 'ACTIVE'
                END AS connection_status
            FROM (
                SELECT * FROM mcp_connect_requests WHERE party_id = $1 AND connection_id IS NOT NULL
                ORDER BY activated_at DESC, connection_id LIMIT 50
            ) AS r
            JOIN mcp_connect_selected_accounts AS a USING (request_ref)
            ORDER BY r.activated_at DESC, r.connection_id, a.account_id
        """
        const val LIST_REVIEWS = """
            SELECT r.*, a.account_id, a.capabilities
            FROM (
                SELECT * FROM mcp_connect_requests
                WHERE party_id = $1 AND expires_at > statement_timestamp() AND connection_id IS NULL
                ORDER BY expires_at, request_ref
                LIMIT 20
            ) AS r
            LEFT JOIN mcp_connect_selected_accounts AS a USING (request_ref)
            ORDER BY r.expires_at, r.request_ref, a.account_id
        """
        const val FIND_REVIEW = """
            SELECT r.*, a.account_id, a.capabilities
            FROM mcp_connect_requests AS r
            LEFT JOIN mcp_connect_selected_accounts AS a USING (request_ref)
            WHERE r.party_id = $1 AND r.request_ref = $2 AND r.expires_at > statement_timestamp()
            ORDER BY a.account_id
        """
        const val LOCK_REVIEW = """
            SELECT challenge_id FROM mcp_connect_requests
            WHERE party_id = $1 AND request_ref = $2 AND expires_at > statement_timestamp()
            FOR UPDATE
        """
        const val EXISTING_SELECTION = """
            SELECT account_id, capabilities FROM mcp_connect_selected_accounts WHERE request_ref = $1
        """
        const val INSERT_SELECTION = """
            INSERT INTO mcp_connect_selected_accounts (request_ref, account_id, capabilities)
            VALUES ($1, $2, $3)
        """
        const val REGISTER = """
            INSERT INTO mcp_connect_requests
                (request_ref, party_id, client_id, par_jkt, redirect_host, resource, scopes, expires_at)
            SELECT $1, $2, $3, $4, $5, $6, $7, $8
            WHERE $8 > statement_timestamp()
            ON CONFLICT (request_ref) DO NOTHING
        """
        const val READ = """
            SELECT party_id, client_id, par_jkt, redirect_host, resource, scopes, expires_at
            FROM mcp_connect_requests
            WHERE request_ref = $1 AND expires_at > statement_timestamp()
        """
    }
}
