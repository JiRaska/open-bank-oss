// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class LendingAssignedCandidates(val ids: List<UUID>, val truncated: Boolean)

class LendingCandidatesUnavailable(cause: Throwable) : RuntimeException("Lending assignment lookup unavailable", cause)

@ApplicationScoped
class LendingAssignedCandidatesRepository(
    private val sessions: Mutiny.SessionFactory,
    @ConfigProperty(name = "openbank.context.bank-scope") private val bankScope: String,
    @ConfigProperty(name = "openbank.context.query-timeout-ms") private val timeoutMs: Int,
) {
    /** Return only current, exact-root, canonical UUID assignments for this human principal. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun assignedCandidates(root: UUID, principalId: String, at: Instant): LendingAssignedCandidates {
        val ids = try {
            transaction { session ->
                session.createNativeQuery(
                    """SELECT DISTINCT assignment.case_id
                       FROM context_case_assignments assignment
                       WHERE assignment.bank_scope = :bank
                         AND assignment.principal_id = :principal
                         AND assignment.purpose = 'LENDING_EXPOSURE_REVIEW'
                         AND assignment.case_id <> :root
                         AND assignment.case_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                         AND assignment.root_ref = 'lending-loan:' || assignment.case_id
                         AND assignment.valid_from <= :now AND assignment.valid_to > :now
                       ORDER BY assignment.case_id
                    """.trimIndent(),
                    String::class.java,
                ).setParameter("bank", bankScope).setParameter("principal", principalId)
                    .setParameter("root", root.toString()).setParameter("now", at)
                    .setMaxResults(MAX_CANDIDATES + 1).resultList
            }.ifNoItem().after(Duration.ofMillis(timeoutMs.toLong())).fail().awaitSuspending()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw LendingCandidatesUnavailable(exception)
        }
        return LendingAssignedCandidates(ids.take(MAX_CANDIDATES).map(UUID::fromString), ids.size > MAX_CANDIDATES)
    }

    private fun <T> transaction(block: (Mutiny.Session) -> Uni<T>): Uni<T> = sessions.withTransaction { session, _ ->
        session.createNativeQuery("select set_config('openbank.bank_scope', :bank, true)", String::class.java)
            .setParameter("bank", bankScope).singleResult.flatMap {
                session.createNativeQuery("select set_config('statement_timeout', :timeout, true)", String::class.java)
                    .setParameter("timeout", "${timeoutMs}ms").singleResult
            }.flatMap { block(session) }
    }

    private companion object {
        const val MAX_CANDIDATES = 256
    }
}
