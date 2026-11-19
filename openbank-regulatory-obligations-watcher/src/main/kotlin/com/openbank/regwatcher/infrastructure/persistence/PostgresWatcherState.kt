// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.regwatcher.application.port.WatcherStatePort
import com.openbank.regwatcher.domain.FindingDisposition
import com.openbank.regwatcher.domain.SourceBaseline
import com.openbank.regwatcher.domain.SourceOutcome
import com.openbank.regwatcher.domain.WatchAudit
import com.openbank.regwatcher.domain.WatchFinding
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant

/**
 * Reactive Hibernate state store. The baseline's id is the URL (application-assigned), so it is
 * written with `merge` — `persist` on an assigned id is INSERT-only and would fail every second run
 * with a duplicate key. Callers carry a Vert.x context (the suspend `@Scheduled` method, or a REST
 * request).
 */
@ApplicationScoped
class PostgresWatcherState(private val sf: Mutiny.SessionFactory) : WatcherStatePort {

    override suspend fun baseline(url: String): SourceBaseline? =
        sf.withSession { s -> s.find(SourceBaselineEntity::class.java, url) }.map { it?.toDomain() }.coAwait()

    override suspend fun saveBaseline(baseline: SourceBaseline) {
        val e = SourceBaselineEntity().apply {
            url = baseline.url
            sha256 = baseline.sha256
            fetchedAt = baseline.fetchedAt
            lastOutcome = baseline.lastOutcome.name
            lastAttemptAt = baseline.lastAttemptAt
            lastReason = baseline.lastReason?.take(MAX_REASON)
        }
        sf.withTransaction { s -> s.merge(e) }.replaceWithVoid().coAwait()
    }

    override suspend fun hasFinding(fingerprint: String): Boolean = sf.withSession { s ->
        s.createQuery("SELECT count(f) FROM WatchFindingEntity f WHERE f.fingerprint = :fp", java.lang.Long::class.java)
            .setParameter("fp", fingerprint).singleResult
    }.map { it.toLong() > 0 }.coAwait()

    override suspend fun issueFor(fingerprint: String): String? = sf.withSession { s ->
        s.createQuery(
            "SELECT f.issueUrl FROM WatchFindingEntity f WHERE f.fingerprint = :fp AND f.issueUrl IS NOT NULL ORDER BY f.recordedAt DESC",
            String::class.java,
        ).setParameter("fp", fingerprint).setMaxResults(1).resultList
    }.map { it.firstOrNull() }.coAwait()

    override suspend fun recordFinding(
        finding: WatchFinding,
        disposition: FindingDisposition,
        issueUrl: String?,
        audit: WatchAudit,
    ) {
        val e = WatchFindingEntity().apply {
            id = Ids.newId()
            fingerprint = finding.fingerprint
            kind = finding.kind.name
            verdict = finding.verdict
            this.disposition = disposition.name
            this.issueUrl = issueUrl
            obligationId = audit.obligationId
            sourceUrl = audit.sourceUrl
            sha256 = finding.sha256
            sourceQuote = audit.sourceQuote
            summary = finding.summary
            modelId = audit.modelId
            promptHash = audit.promptHash
            toolCalls = audit.toolCalls.joinToString(",")
            policyDecision = audit.policyDecision.take(MAX_REASON)
            recordedAt = Instant.now()
        }
        sf.withTransaction { s -> s.persist(e) }.coAwait()
    }

    override suspend fun runsStartedSince(since: Instant): Int = sf.withSession { s ->
        s.createQuery("SELECT count(r) FROM WatchRunEntity r WHERE r.startedAt >= :since", java.lang.Long::class.java)
            .setParameter("since", since).singleResult
    }.map { it.toInt() }.coAwait()

    override suspend fun recordRunStart(at: Instant) {
        val e = WatchRunEntity().apply {
            id = Ids.newId()
            startedAt = at
        }
        sf.withTransaction { s -> s.persist(e) }.coAwait()
    }

    private fun SourceBaselineEntity.toDomain() = SourceBaseline(
        url = url,
        sha256 = sha256,
        fetchedAt = fetchedAt,
        lastOutcome = SourceOutcome.valueOf(lastOutcome),
        lastAttemptAt = lastAttemptAt,
        lastReason = lastReason,
    )

    private suspend fun <T> Uni<T>.coAwait(): T = subscribeAsCompletionStage().await()

    private companion object {
        const val MAX_REASON = 255
    }
}
