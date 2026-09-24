// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.persistence

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DoraMetric
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RemediationKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.function.Function

/**
 * Entity mapping of [PostgresFindingRepository] with the Mutiny session stubbed — no database, no
 * container. The value here is the DOMAIN↔ENTITY round trip: every column is copied by hand in
 * `applyFrom`/`toDomain`, so a field added to [DevOpsFinding] and forgotten in one of the two
 * silently loses HITL state (a diagnosis, or the PR url that makes a finding approvable) across a
 * pod restart, with no compiler or database error anywhere.
 */
class PostgresFindingRepositoryTest {

    private val session = mockk<Mutiny.Session>(relaxed = true)
    private val sf = mockk<Mutiny.SessionFactory>()

    private val repository = PostgresFindingRepository(sf)

    private val id = UUID.fromString("6f1c0b5e-0000-4000-8000-000000000042")

    private val finding = DevOpsFinding(
        id = id.toString(),
        detector = DetectorId.D3_RUNNER_CAPACITY,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-02T03:00:00Z"),
        title = "Runner pool stranded",
        rawMetricValue = BigDecimal("3"),
        threshold = BigDecimal("0.8"),
        affectedResource = "arc-runners",
        doraMetricImpacted = DoraMetric.LEAD_TIME_FOR_CHANGES,
        rootCause = "no online runner pods",
        remediationKind = RemediationKind.PULL_REQUEST,
        proposalPrUrl = "https://github.com/JiRaska/open-bank/pull/99",
        proposedRemediation = "add the label to reregister-runner.sh",
        status = FindingStatus.PROPOSED,
        diagnosedAt = Instant.parse("2026-08-02T03:01:00Z"),
        proposedAt = Instant.parse("2026-08-02T03:02:00Z"),
    )

    @Suppress("UNCHECKED_CAST")
    private fun runningTransactionsInline() {
        every { sf.withTransaction(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
        every { sf.withSession(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
    }

    @Test
    fun `save persists an entity carrying every domain field`(): Unit = runBlocking {
        runningTransactionsInline()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val out = repository.save(finding)

        assertThat(out).isSameAs(finding)
        val entity = persisted.captured as FindingEntity
        assertThat(entity.id).isEqualTo(id)
        assertThat(entity.detector).isEqualTo(DetectorId.D3_RUNNER_CAPACITY)
        assertThat(entity.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(entity.rawMetricValue).isEqualByComparingTo(BigDecimal("3"))
        assertThat(entity.doraMetricImpacted).isEqualTo(DoraMetric.LEAD_TIME_FOR_CHANGES)
        assertThat(entity.rootCause).isEqualTo("no online runner pods")
        assertThat(entity.proposalPrUrl).isEqualTo("https://github.com/JiRaska/open-bank/pull/99")
        assertThat(entity.proposedRemediation).isEqualTo("add the label to reregister-runner.sh")
        assertThat(entity.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(entity.diagnosedAt).isEqualTo(Instant.parse("2026-08-02T03:01:00Z"))
        assertThat(entity.proposedAt).isEqualTo(Instant.parse("2026-08-02T03:02:00Z"))
    }

    @Test
    fun `findById maps every column back, losing no HITL state`(): Unit = runBlocking {
        runningTransactionsInline()
        val stored = FindingEntity().alsoApply(finding)
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().item(stored)

        val out = repository.findById(id.toString())

        // Round-trip equality is the assertion that a newly added field cannot be dropped by one
        // half of the hand-written mapping.
        assertThat(out).isEqualTo(finding)
    }

    @Test
    fun `findById returns null for a row that is not there`(): Unit = runBlocking {
        runningTransactionsInline()
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()

        assertThat(repository.findById(id.toString())).isNull()
    }

    @Test
    fun `a non-uuid id fails loudly rather than silently missing`(): Unit = runBlocking {
        runningTransactionsInline()

        assertThatThrownBy { runBlocking { repository.findById("not-a-uuid") } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `update mutates the managed row instead of inserting a duplicate`(): Unit = runBlocking {
        runningTransactionsInline()
        val managed = FindingEntity().alsoApply(finding)
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().item(managed)

        val approved = finding.copy(status = FindingStatus.APPROVED)
        val out = repository.update(approved)

        assertThat(out).isEqualTo(approved)
        assertThat(managed.status).isEqualTo(FindingStatus.APPROVED)
        verify(exactly = 0) { session.persist(any()) }
    }

    @Test
    fun `update inserts when the row is absent - an in-flight finding is never lost`(): Unit = runBlocking {
        runningTransactionsInline()
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val out = repository.update(finding)

        assertThat(out).isEqualTo(finding)
        assertThat((persisted.captured as FindingEntity).id).isEqualTo(id)
    }

    @Test
    fun `findActive excludes the terminal statuses and maps the rows`(): Unit = runBlocking {
        runningTransactionsInline()
        val query = mockk<Mutiny.SelectionQuery<FindingEntity>>()
        val hql = slot<String>()
        val terminal = slot<Any>()
        every { session.createQuery(capture(hql), FindingEntity::class.java) } returns query
        every { query.setParameter("terminal", capture(terminal)) } returns query
        every { query.resultList } returns Uni.createFrom().item(listOf(FindingEntity().alsoApply(finding)))

        val out = repository.findActive()

        assertThat(out).containsExactly(finding)
        assertThat(hql.captured).contains("status NOT IN (:terminal)").contains("ORDER BY detectedAt DESC")
        assertThat(terminal.captured as List<*>)
            .containsExactlyInAnyOrder(FindingStatus.RESOLVED, FindingStatus.REJECTED)
    }
}

/** Builds a persisted-shaped entity through the same field set the production mapper writes. */
private fun FindingEntity.alsoApply(f: DevOpsFinding): FindingEntity {
    id = UUID.fromString(f.id)
    detector = f.detector
    severity = f.severity
    detectedAt = f.detectedAt
    title = f.title
    rawMetricValue = f.rawMetricValue
    threshold = f.threshold
    affectedResource = f.affectedResource
    doraMetricImpacted = f.doraMetricImpacted
    rootCause = f.rootCause
    remediationKind = f.remediationKind
    proposalPrUrl = f.proposalPrUrl
    proposedRemediation = f.proposedRemediation
    status = f.status
    diagnosedAt = f.diagnosedAt
    proposedAt = f.proposedAt
    return this
}
