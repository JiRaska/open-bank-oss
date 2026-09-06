// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.persistence

import com.openbank.govaudit.domain.model.FindingSeverity
import com.openbank.govaudit.domain.model.FindingStatus
import com.openbank.govaudit.domain.model.GovernanceCheckType
import com.openbank.govaudit.domain.model.GovernanceFinding
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
 * The entity<->domain mapping and the update's find-or-persist branch, driven over a stubbed
 * [Mutiny.SessionFactory] so no database is needed.
 *
 * Worth pinning without infra: `update` on a MISSING row must persist rather than silently do
 * nothing (a finding diagnosed by an activity whose save was lost would otherwise vanish), and the
 * round-trip must carry every nullable HITL field — a mapping that drops `proposalUrl` or
 * `proposedAt` reproduces exactly the #5897 defect of a finding that reads as unproposed.
 */
class PostgresFindingRepositoryTest {

    private val session = mockk<Mutiny.Session>()
    private val sessionFactory = mockk<Mutiny.SessionFactory>()
    private val repository = PostgresFindingRepository(sessionFactory)

    private val id = UUID.fromString("0f3c2b9a-1111-2222-3333-444455556666")

    private fun finding(
        status: FindingStatus = FindingStatus.PROPOSED,
        rootCause: String? = "no reviewer assigned",
        proposalUrl: String? = "https://github.com/JiRaska/open-bank-oss/issues/99",
    ) = GovernanceFinding(
        id = id.toString(),
        checkType = GovernanceCheckType.APPROVAL_COUNT,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T04:30:00Z"),
        title = "PR #4242 merged with 0 approval(s), 2 required",
        prNumber = 4242,
        prUrl = "https://github.com/JiRaska/open-bank-oss/pull/4242",
        rawMetricValue = BigDecimal.ZERO,
        threshold = BigDecimal.valueOf(2),
        rootCause = rootCause,
        proposalUrl = proposalUrl,
        proposedFixDiff = null,
        status = status,
        diagnosedAt = Instant.parse("2026-07-25T04:31:00Z"),
        proposedAt = Instant.parse("2026-07-25T04:32:00Z"),
    )

    @Suppress("UNCHECKED_CAST")
    private fun stubUnitOfWork() {
        every { sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
        every { sessionFactory.withSession(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
    }

    @Test
    fun `save writes every field of the domain finding onto a new entity`(): Unit = runBlocking {
        stubUnitOfWork()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()
        val toSave = finding()

        assertThat(repository.save(toSave)).isEqualTo(toSave)

        val entity = persisted.captured as FindingEntity
        assertThat(entity.id).isEqualTo(id)
        assertThat(entity.checkType).isEqualTo(GovernanceCheckType.APPROVAL_COUNT)
        assertThat(entity.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(entity.prNumber).isEqualTo(4242)
        assertThat(entity.threshold).isEqualTo(BigDecimal.valueOf(2))
        assertThat(entity.rootCause).isEqualTo("no reviewer assigned")
        assertThat(entity.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/99")
        assertThat(entity.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(entity.diagnosedAt).isEqualTo(Instant.parse("2026-07-25T04:31:00Z"))
        assertThat(entity.proposedAt).isEqualTo(Instant.parse("2026-07-25T04:32:00Z"))
    }

    @Test
    fun `update mutates the managed row in place and does NOT insert a duplicate`(): Unit = runBlocking {
        stubUnitOfWork()
        val managed = FindingEntity().also {
            it.id = id
            it.status = FindingStatus.OPEN
            it.rootCause = null
            it.proposalUrl = null
        }
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().item(managed)

        val updated = finding(status = FindingStatus.PROPOSED)
        assertThat(repository.update(updated)).isEqualTo(updated)

        assertThat(managed.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(managed.rootCause).isEqualTo("no reviewer assigned")
        assertThat(managed.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/99")
        // A persist here would be an INSERT of an already-present application-assigned id.
        verify(exactly = 0) { session.persist(any()) }
    }

    @Test
    fun `update of a row that is not there persists it instead of losing the finding`(): Unit = runBlocking {
        stubUnitOfWork()
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val toUpdate = finding(status = FindingStatus.DIAGNOSED, proposalUrl = null)
        assertThat(repository.update(toUpdate)).isEqualTo(toUpdate)

        val entity = persisted.captured as FindingEntity
        assertThat(entity.id).isEqualTo(id)
        assertThat(entity.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(entity.proposalUrl).isNull()
    }

    @Test
    fun `findById maps the row back to the domain, nullable HITL fields included`(): Unit = runBlocking {
        stubUnitOfWork()
        val stored = finding(rootCause = null, proposalUrl = null)
        every { session.find(FindingEntity::class.java, id) } returns
            Uni.createFrom().item(FindingEntity().fromDomain(stored))

        val loaded = repository.findById(id.toString())

        assertThat(loaded).isEqualTo(stored)
        assertThat(loaded!!.rootCause).isNull()
        assertThat(loaded.proposalUrl).isNull()
    }

    @Test
    fun `findById returns null for an absent row rather than a blank finding`(): Unit = runBlocking {
        stubUnitOfWork()
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()

        assertThat(repository.findById(id.toString())).isNull()
    }

    @Test
    fun `findById rejects an id that is not a UUID instead of querying with a bad key`() {
        stubUnitOfWork()

        assertThatThrownBy { runBlocking { repository.findById("not-a-uuid") } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `findActive excludes the terminal statuses and maps every row`(): Unit = runBlocking {
        stubUnitOfWork()
        val terminal = slot<Any>()
        val query = mockk<Mutiny.SelectionQuery<FindingEntity>>()
        val stored = finding()
        every { session.createQuery(any<String>(), FindingEntity::class.java) } returns query
        every { query.setParameter("terminal", capture(terminal)) } returns query
        every { query.resultList } returns
            Uni.createFrom().item(listOf(FindingEntity().fromDomain(stored)))

        assertThat(repository.findActive()).containsExactly(stored)

        @Suppress("UNCHECKED_CAST")
        val excluded = terminal.captured as List<FindingStatus>
        assertThat(excluded).containsExactlyInAnyOrder(FindingStatus.RESOLVED, FindingStatus.REJECTED)
    }
}

/** Test-local mirror of the production (private) entity writer, so a row can be staged. */
private fun FindingEntity.fromDomain(f: GovernanceFinding): FindingEntity {
    id = UUID.fromString(f.id)
    checkType = f.checkType
    severity = f.severity
    detectedAt = f.detectedAt
    title = f.title
    prNumber = f.prNumber
    prUrl = f.prUrl
    rawMetricValue = f.rawMetricValue
    threshold = f.threshold
    rootCause = f.rootCause
    proposalUrl = f.proposalUrl
    proposedFixDiff = f.proposedFixDiff
    status = f.status
    diagnosedAt = f.diagnosedAt
    proposedAt = f.proposedAt
    return this
}
