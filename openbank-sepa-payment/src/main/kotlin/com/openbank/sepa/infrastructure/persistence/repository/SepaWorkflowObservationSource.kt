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
    val environment: String?,
    val sourceService: String,
    val eventType: String,
    val status: String,
    val contentDigest: String,
    val observedAt: Instant,
    val workflowStartedAt: Instant?,
    val recordedAt: Instant,
    val synthetic: Boolean,
)

data class WorkflowObservationHistory(
    val paymentId: UUID,
    val sourceRevision: Long,
    val observations: List<WorkflowObservation>,
    val coverage: WorkflowHistoryCoverage,
    val truncated: Boolean,
)

data class WorkflowObservationRecord(val paymentId: UUID, val observation: WorkflowObservation)

/** Bounded internal read; no endpoint grants case access until incident assignment policy exists. */
@ApplicationScoped
class SepaWorkflowObservationSource {
    /** Exact source lookup after a separate incident-case authorization; no cross-case enumeration. */
    suspend fun find(eventId: UUID, paymentId: UUID, environment: String): WorkflowObservationRecord? =
        Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    """from SepaWorkflowObservationEntity
                       where eventId = :eventId and paymentId = :paymentId and environment = :environment
                    """.trimIndent(),
                    SepaWorkflowObservationEntity::class.java,
                ).setParameter("eventId", eventId).setParameter("paymentId", paymentId)
                    .setParameter("environment", environment).singleResultOrNull
                    .map { row -> row?.let { WorkflowObservationRecord(it.paymentId, it.toObservation()) } }
            }
        }.awaitSuspending()

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
            retained.any { it.environment == null || it.workflowStartedAt == null } -> WorkflowHistoryCoverage.UNKNOWN
            retained.mapNotNull { it.environment }.distinct().size > 1 -> WorkflowHistoryCoverage.UNKNOWN
            retained.any { it.workflowStartedAt?.isAfter(it.observedAt) == true } -> WorkflowHistoryCoverage.UNKNOWN
            truncated -> WorkflowHistoryCoverage.PARTIAL
            retained.first().paymentRevision == 0L -> WorkflowHistoryCoverage.COMPLETE
            else -> WorkflowHistoryCoverage.UNKNOWN
        }
        return WorkflowObservationHistory(
            paymentId,
            sourceRevision,
            retained.map { it.toObservation() },
            coverage,
            truncated,
        )
    }

    private fun SepaWorkflowObservationEntity.toObservation() = WorkflowObservation(
        eventId,
        paymentRevision,
        environment,
        SOURCE_SERVICE,
        eventType,
        paymentStatus,
        contentDigest,
        observedAt,
        workflowStartedAt,
        recordedAt,
        synthetic,
    )

    private companion object {
        const val MAX_OBSERVATIONS = 100
        const val SOURCE_SERVICE = "openbank-sepa-payment"
    }
}
