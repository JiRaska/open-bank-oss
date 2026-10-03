// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.util.UUID

enum class ContextCommitmentKind { AUDIT, DISCLOSURE }
enum class ContextCommitmentOutcomeStatus { SENT, FAILED }
data class ContextCommitmentOutcome(
    val id: UUID,
    val claimToken: UUID,
    val status: ContextCommitmentOutcomeStatus,
    val at: Instant,
)

/** Finalizes only acknowledged publication outcomes still owned by their claim. */
@ApplicationScoped
class ContextCommitmentOutcomeWriter(
    private val sessions: Mutiny.SessionFactory,
    private val settings: ContextCommitmentDispatchSettings,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    suspend fun persist(kind: ContextCommitmentKind, outcomes: List<ContextCommitmentOutcome>): Int {
        require(outcomes.size <= MAX_BATCH_SIZE) { "commitment outcome batch must contain at most 25 rows" }
        require(outcomes.map { it.id }.distinct().size == outcomes.size) { "duplicate commitment outcome" }
        if (outcomes.isEmpty()) return 0
        val prefix = when (kind) {
            ContextCommitmentKind.AUDIT -> "audit"
            ContextCommitmentKind.DISCLOSURE -> "disclosure"
        }
        val values = outcomes.indices.joinToString(",") { index ->
            "(CAST(:id$index AS uuid), CAST(:token$index AS uuid), " +
                "CAST(:status$index AS varchar), CAST(:at$index AS timestamptz))"
        }
        val sql = "UPDATE context_${prefix}_commitment_outbox AS target " +
            "SET status = outcome.status, attempt_count = target.attempt_count + 1, " +
            "updated_at = outcome.at, sent_at = CASE WHEN outcome.status = 'SENT' " +
            "THEN outcome.at ELSE target.sent_at END, claim_token = NULL " +
            "FROM (VALUES $values) AS outcome(id, token, status, at) " +
            "WHERE target.${prefix}_id = outcome.id AND target.bank_scope = :bank " +
            "AND target.status = 'DISPATCHING' AND target.claim_token = outcome.token"
        return ContextSqlOperation.execute(sessions, timeoutMs) { operation ->
            operation.sql { session ->
                session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
                    .setParameter("bank", settings.bankScope).singleResult
            }.flatMap {
                operation.sql { session ->
                    val query = session.createNativeMutationQuery(sql).setParameter("bank", settings.bankScope)
                    outcomes.forEachIndexed { index, outcome ->
                        query.setParameter("id$index", outcome.id).setParameter("token$index", outcome.claimToken)
                            .setParameter("status$index", outcome.status.name).setParameter("at$index", outcome.at)
                    }
                    query.executeUpdate()
                }
            }
        }.awaitSuspending()
    }

    companion object {
        const val MAX_BATCH_SIZE = 25
    }
}
