// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.openbank.delegation.application.port.out.McpIssuerProofClaim
import com.openbank.delegation.application.port.out.McpIssuerProofReplayStore
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

@ApplicationScoped
class McpIssuerProofReplayStoreImpl(private val pool: Pool) : McpIssuerProofReplayStore {
    override suspend fun claim(proofKey: String): McpIssuerProofClaim {
        require(PROOF_KEY.matches(proofKey)) { "expected a canonical HMAC-SHA-256 proof reference" }

        // Use a separate transaction so SUCCESS cannot escape before its commit. A timeout during
        // COMMIT is unavailable even if PostgreSQL retained the claim. Do not retry or remove it.
        // 'on' waits for local WAL and for synchronous replicas when the topology configures them.
        return pool.withTransaction { connection ->
            connection.query(SYNC_COMMIT).execute()
                .flatMap { connection.preparedQuery(CLAIM).execute(Tuple.of(proofKey)) }
                .map { rows ->
                    when (rows.rowCount()) {
                        1 -> McpIssuerProofClaim.FIRST_USE
                        0 -> McpIssuerProofClaim.REPLAYED
                        else -> McpIssuerProofClaim.UNAVAILABLE
                    }
                }
        }.ifNoItem().after(CLAIM_TIMEOUT).fail()
            .onFailure().recoverWithItem(McpIssuerProofClaim.UNAVAILABLE)
            .awaitSuspending()
    }

    override suspend fun purgeExpired(limit: Int): Int {
        require(limit in 1..MAX_PURGE_ROWS) { "purge limit must be between 1 and $MAX_PURGE_ROWS" }
        return pool.preparedQuery(PURGE).execute(Tuple.of(limit)).awaitSuspending().rowCount()
    }

    private companion object {
        val PROOF_KEY = Regex("^[0-9a-f]{64}$")
        val CLAIM_TIMEOUT: Duration = Duration.ofSeconds(2)
        const val MAX_PURGE_ROWS = 1_000
        const val SYNC_COMMIT = "SET LOCAL synchronous_commit = 'on'"
        const val CLAIM = """
            INSERT INTO mcp_issuer_proof_claims (proof_key)
            VALUES ($1)
            ON CONFLICT (proof_key) DO NOTHING
        """
        const val PURGE = """
            WITH expired AS (
                SELECT proof_key
                FROM mcp_issuer_proof_claims
                WHERE retain_until <= statement_timestamp()
                ORDER BY retain_until, proof_key
                LIMIT $1
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM mcp_issuer_proof_claims AS claims
            USING expired
            WHERE claims.proof_key = expired.proof_key
        """
    }
}
