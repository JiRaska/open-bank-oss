// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.finops.infrastructure.persistence

import com.openbank.finops.application.port.out.AnomalyRepository
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/**
 * Durable, cross-restart cost-anomaly memory (ADR-0112 / ADR-0148 fleet episodic-memory follow-up).
 *
 * Replaces the InMemoryAnomalyRepository, which forgot every anomaly on pod restart — so the daily
 * 03:00 run could re-propose a fix for an anomaly a human had already rejected, and the "have we
 * seen this before" signal was lost. Mirrors devops-agent's PostgresFindingRepository exactly:
 * writes via the reactive [Mutiny.SessionFactory], `update` does find-then-mutate-managed (the
 * assigned-@Id persist-is-INSERT-only trap from CLAUDE.md is avoided by mutating the managed row and
 * letting dirty-flush issue the UPDATE, never a second persist).
 */
@ApplicationScoped
class PostgresAnomalyRepository(private val sf: Mutiny.SessionFactory) : AnomalyRepository {

    override suspend fun save(anomaly: CostAnomaly): CostAnomaly =
        sf.withTransaction { s -> s.persist(AnomalyEntity().applyFrom(anomaly)) }
            .replaceWith(anomaly)
            .coAwait()

    override suspend fun update(anomaly: CostAnomaly): CostAnomaly = sf.withTransaction { s ->
        s.find(AnomalyEntity::class.java, UUID.fromString(anomaly.id)).flatMap { existing ->
            if (existing != null) {
                existing.applyFrom(anomaly) // managed — mutations flush on commit
                Uni.createFrom().item(anomaly)
            } else {
                s.persist(AnomalyEntity().applyFrom(anomaly)).replaceWith(anomaly)
            }
        }
    }.coAwait()

    override suspend fun findActive(): List<CostAnomaly> = sf.withSession { s ->
        s.createQuery(
            "FROM AnomalyEntity WHERE status NOT IN (:terminal) ORDER BY detectedAt DESC",
            AnomalyEntity::class.java,
        ).setParameter("terminal", listOf(AnomalyStatus.RESOLVED, AnomalyStatus.REJECTED)).resultList
    }.map { rows -> rows.map { it.toDomain() } }.coAwait()

    override suspend fun findById(id: String): CostAnomaly? =
        sf.withSession { s -> s.find(AnomalyEntity::class.java, UUID.fromString(id)) }
            .map { it?.toDomain() }
            .coAwait()

    /** Bridge a Mutiny [Uni] to a coroutine without pulling in mutiny-kotlin. */
    private suspend fun <T> Uni<T>.coAwait(): T = subscribeAsCompletionStage().await()
}

private fun AnomalyEntity.applyFrom(a: CostAnomaly): AnomalyEntity {
    id = UUID.fromString(a.id)
    detector = a.detector
    severity = a.severity
    detectedAt = a.detectedAt
    title = a.title
    rawMetricValue = a.rawMetricValue
    threshold = a.threshold
    affectedResource = a.affectedResource
    rootCause = a.rootCause
    proposalPrUrl = a.proposalPrUrl
    proposedIacDiff = a.proposedIacDiff
    estimatedMonthlySavingUsd = a.estimatedMonthlySavingUsd
    status = a.status
    diagnosedAt = a.diagnosedAt
    proposedAt = a.proposedAt
    return this
}

private fun AnomalyEntity.toDomain(): CostAnomaly = CostAnomaly(
    id = id.toString(),
    detector = detector,
    severity = severity,
    detectedAt = detectedAt,
    title = title,
    rawMetricValue = rawMetricValue,
    threshold = threshold,
    affectedResource = affectedResource,
    rootCause = rootCause,
    proposalPrUrl = proposalPrUrl,
    proposedIacDiff = proposedIacDiff,
    estimatedMonthlySavingUsd = estimatedMonthlySavingUsd,
    status = status,
    diagnosedAt = diagnosedAt,
    proposedAt = proposedAt,
)
