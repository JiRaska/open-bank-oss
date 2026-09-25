// SPDX-License-Identifier: Apache-2.0
package com.openbank.sepa.infrastructure.persistence.repository

import com.openbank.sepa.infrastructure.persistence.entity.SepaWorkflowObservationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

enum class WorkflowHistoryCoverage { COMPLETE, PARTIAL, UNKNOWN }

data class WorkflowObservation(
    val eventId: UUID,
    val revision: Long,
    val eventType: String,
    val status: String,
    val contentDigest: String,
    val observedAt: Instant,
    val synthetic: Boolean,
)

data class WorkflowObservationHistory(
    val paymentId: UUID,
    val sourceRevision: Long,
    val observations: List<WorkflowObservation>,
    val coverage: WorkflowHistoryCoverage,
    val truncated: Boolean,
)

/** Bounded internal read; no endpoint grants case access until incident assignment policy exists. */
@ApplicationScoped
class SepaWorkflowObservationSource {
    suspend fun history(paymentId: UUID): WorkflowObservationHistory? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select p.revision from SepaPaymentEntity p where p.paymentId = :paymentId",
                Long::class.javaObjectType,
            ).setParameter("paymentId", paymentId).singleResultOrNull.flatMap { sourceRevision ->
                if (sourceRevision == null) {
                    io.smallrye.mutiny.Uni.createFrom().nullItem()
                } else {
                    session.createQuery(
                        """from SepaWorkflowObservationEntity
                           where paymentId = :paymentId and paymentRevision <= :sourceRevision
                           order by paymentRevision desc
                        """.trimIndent(),
                        SepaWorkflowObservationEntity::class.java,
                    ).setParameter("paymentId", paymentId)
                        .setParameter("sourceRevision", sourceRevision)
                        .setMaxResults(MAX_OBSERVATIONS + 1)
                        .resultList.map { rows -> history(paymentId, sourceRevision, rows) }
                }
            }
        }
    }.awaitSuspending()

    private fun history(
        paymentId: UUID,
        sourceRevision: Long,
        rows: List<SepaWorkflowObservationEntity>,
    ): WorkflowObservationHistory {
        val truncated = rows.size > MAX_OBSERVATIONS
        val retained = rows.take(MAX_OBSERVATIONS).asReversed()
        val contiguousTail = retained.lastOrNull()?.paymentRevision == sourceRevision &&
            retained.zipWithNext().all { (older, newer) -> newer.paymentRevision == older.paymentRevision + 1 }
        val coverage = when {
            retained.isEmpty() || !contiguousTail -> WorkflowHistoryCoverage.UNKNOWN
            truncated -> WorkflowHistoryCoverage.PARTIAL
            retained.first().paymentRevision == 0L -> WorkflowHistoryCoverage.COMPLETE
            else -> WorkflowHistoryCoverage.UNKNOWN
        }
        return WorkflowObservationHistory(
            paymentId,
            sourceRevision,
            retained.map {
                WorkflowObservation(
                    it.eventId,
                    it.paymentRevision,
                    it.eventType,
                    it.paymentStatus,
                    it.contentDigest,
                    it.observedAt,
                    it.synthetic,
                )
            },
            coverage,
            truncated,
        )
    }

    private companion object {
        const val MAX_OBSERVATIONS = 100
    }
}
