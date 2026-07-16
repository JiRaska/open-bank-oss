// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.persistence

import com.openbank.liveness.application.port.out.FindingRepository
import com.openbank.liveness.domain.model.FindingStatus
import com.openbank.liveness.domain.model.LivenessFinding
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

/**
 * Postgres-backed finding repository (ADR-0163). Replaces the in-memory store so findings — and their
 * HITL lifecycle — survive a pod restart. Reactive Panache (the fleet standard); each Mutiny [Uni] is
 * bridged to the suspend port. Callers already supply a Vert.x context (Temporal activities via
 * VertxContextSupport; REST via suspend resource methods on RESTEasy Reactive).
 */
@ApplicationScoped
class PostgresFindingRepository(private val sf: Mutiny.SessionFactory) : FindingRepository {

    override suspend fun save(finding: LivenessFinding): LivenessFinding =
        sf.withTransaction { s -> s.persist(FindingEntity().applyFrom(finding)) }
            .replaceWith(finding)
            .coAwait()

    override suspend fun update(finding: LivenessFinding): LivenessFinding = sf.withTransaction { s ->
        s.find(FindingEntity::class.java, UUID.fromString(finding.id)).flatMap { existing ->
            if (existing != null) {
                existing.applyFrom(finding) // managed — mutations flush on commit
                Uni.createFrom().item(finding)
            } else {
                s.persist(FindingEntity().applyFrom(finding)).replaceWith(finding)
            }
        }
    }.coAwait()

    override suspend fun findActive(): List<LivenessFinding> = sf.withSession { s ->
        s.createQuery(
            "FROM FindingEntity WHERE status NOT IN (:terminal) ORDER BY detectedAt DESC",
            FindingEntity::class.java,
        ).setParameter("terminal", listOf(FindingStatus.RESOLVED, FindingStatus.REJECTED)).resultList
    }.map { rows -> rows.map { it.toDomain() } }.coAwait()

    override suspend fun findById(id: String): LivenessFinding? =
        sf.withSession { s -> s.find(FindingEntity::class.java, UUID.fromString(id)) }
            .map { it?.toDomain() }
            .coAwait()

    /** Bridge a Mutiny [Uni] to a coroutine without pulling in mutiny-kotlin. */
    private suspend fun <T> Uni<T>.coAwait(): T = subscribeAsCompletionStage().await()
}

private fun FindingEntity.applyFrom(f: LivenessFinding): FindingEntity {
    id = UUID.fromString(f.id)
    mechanism = f.mechanism
    severity = f.severity
    detectedAt = f.detectedAt
    title = f.title
    affectedControl = f.affectedControl
    rawMetricValue = f.rawMetricValue
    threshold = f.threshold
    rootCause = f.rootCause
    proposalPrUrl = f.proposalPrUrl
    proposedFixDiff = f.proposedFixDiff
    status = f.status
    diagnosedAt = f.diagnosedAt
    proposedAt = f.proposedAt
    return this
}

private fun FindingEntity.toDomain(): LivenessFinding = LivenessFinding(
    id = id.toString(),
    mechanism = mechanism,
    severity = severity,
    detectedAt = detectedAt,
    title = title,
    affectedControl = affectedControl,
    rawMetricValue = rawMetricValue,
    threshold = threshold,
    rootCause = rootCause,
    proposalPrUrl = proposalPrUrl,
    proposedFixDiff = proposedFixDiff,
    status = status,
    diagnosedAt = diagnosedAt,
    proposedAt = proposedAt,
)
