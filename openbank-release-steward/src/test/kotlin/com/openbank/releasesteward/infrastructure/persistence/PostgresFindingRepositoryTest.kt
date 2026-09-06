// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.persistence

import com.openbank.releasesteward.domain.model.FindingSeverity
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.function.Function

/**
 * Domain <-> [FindingEntity] mapping and the branch structure of [PostgresFindingRepository],
 * driven over a mocked Mutiny session — no database, no Vert.x context. What is actually being
 * asserted is the mapping (every column round-trips, nullable columns stay null) and that `update`
 * mutates a MANAGED row when one exists but falls back to an insert when it does not; getting
 * either wrong loses the HITL lifecycle silently.
 */
class PostgresFindingRepositoryTest {

    private val session = mockk<Mutiny.Session>()
    private val sf = mockk<Mutiny.SessionFactory>()
    private val repository = PostgresFindingRepository(sf)

    private val id = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun finding(
        status: FindingStatus = FindingStatus.OPEN,
        rootCause: String? = null,
        prNumber: Int? = null,
    ) = ReleaseStewardFinding(
        id = id.toString(),
        checkType = ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-01-01T00:00:00Z"),
        title = "two open PRs race the ledger spec",
        component = "openbank-ledger-service/openapi.yaml",
        prNumber = prNumber,
        prUrl = prNumber?.let { "https://github.com/JiRaska/open-bank-oss/pull/$it" },
        rawMetricValue = BigDecimal("2"),
        threshold = BigDecimal.ONE,
        rootCause = rootCause,
        status = status,
    )

    private fun <T> stubWithTransaction() {
        every { sf.withTransaction(any<Function<Mutiny.Session, Uni<T>>>()) } answers {
            firstArg<Function<Mutiny.Session, Uni<T>>>().apply(session)
        }
    }

    private fun <T> stubWithSession() {
        every { sf.withSession(any<Function<Mutiny.Session, Uni<T>>>()) } answers {
            firstArg<Function<Mutiny.Session, Uni<T>>>().apply(session)
        }
    }

    @Test
    fun `save persists an entity carrying every field of the domain finding`(): Unit = runBlocking {
        stubWithTransaction<ReleaseStewardFinding>()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val input = finding(status = FindingStatus.DIAGNOSED, rootCause = "racing PRs", prNumber = 42)
        val result = repository.save(input)

        assertThat(result).isEqualTo(input)
        val entity = persisted.captured as FindingEntity
        assertThat(entity.id).isEqualTo(id)
        assertThat(entity.checkType).isEqualTo(ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION)
        assertThat(entity.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(entity.detectedAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        assertThat(entity.title).isEqualTo("two open PRs race the ledger spec")
        assertThat(entity.component).isEqualTo("openbank-ledger-service/openapi.yaml")
        assertThat(entity.prNumber).isEqualTo(42)
        assertThat(entity.prUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/42")
        assertThat(entity.rawMetricValue).isEqualByComparingTo(BigDecimal("2"))
        assertThat(entity.threshold).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(entity.rootCause).isEqualTo("racing PRs")
        assertThat(entity.status).isEqualTo(FindingStatus.DIAGNOSED)
    }

    @Test
    fun `save leaves the optional columns null rather than defaulting them`(): Unit = runBlocking {
        stubWithTransaction<ReleaseStewardFinding>()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        repository.save(finding())

        val entity = persisted.captured as FindingEntity
        assertThat(entity.prNumber).isNull()
        assertThat(entity.prUrl).isNull()
        assertThat(entity.rootCause).isNull()
        assertThat(entity.proposalUrl).isNull()
        assertThat(entity.proposedFixDiff).isNull()
        assertThat(entity.diagnosedAt).isNull()
        assertThat(entity.proposedAt).isNull()
    }

    @Test
    fun `update MUTATES the managed row it found and never inserts a second one`(): Unit = runBlocking {
        stubWithTransaction<ReleaseStewardFinding>()
        val managed = entityOf(finding())
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().item(managed)

        val updated = finding(status = FindingStatus.PROPOSED, rootCause = "racing PRs").copy(
            proposalUrl = "https://github.com/JiRaska/open-bank-oss/issues/9",
            proposedAt = Instant.parse("2026-01-02T00:00:00Z"),
        )
        val result = repository.update(updated)

        assertThat(result).isEqualTo(updated)
        // The lifecycle transition must land on the MANAGED instance, which is what flushes.
        assertThat(managed.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(managed.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/9")
        assertThat(managed.proposedAt).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"))
        verify(exactly = 0) { session.persist(any()) }
    }

    @Test
    fun `update INSERTS when the row is absent, so a lost row is not silently dropped`(): Unit = runBlocking {
        stubWithTransaction<ReleaseStewardFinding>()
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val input = finding(status = FindingStatus.PROPOSED)
        val result = repository.update(input)

        assertThat(result).isEqualTo(input)
        assertThat((persisted.captured as FindingEntity).status).isEqualTo(FindingStatus.PROPOSED)
    }

    @Test
    fun `findById maps a row back to the domain, including its nullable columns`(): Unit = runBlocking {
        stubWithSession<FindingEntity?>()
        val row = entityOf(
            finding(status = FindingStatus.DIAGNOSED, rootCause = "racing PRs", prNumber = 42),
        ).also { it.diagnosedAt = Instant.parse("2026-01-03T00:00:00Z") }
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().item(row)

        val result = repository.findById(id.toString())

        assertThat(result).isNotNull
        assertThat(result!!.id).isEqualTo(id.toString())
        assertThat(result.checkType).isEqualTo(ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION)
        assertThat(result.prNumber).isEqualTo(42)
        assertThat(result.rootCause).isEqualTo("racing PRs")
        assertThat(result.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(result.diagnosedAt).isEqualTo(Instant.parse("2026-01-03T00:00:00Z"))
        assertThat(result.proposalUrl).isNull()
        assertThat(result.proposedAt).isNull()
    }

    @Test
    fun `findById returns null for an unknown id instead of a blank finding`(): Unit = runBlocking {
        stubWithSession<FindingEntity?>()
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()

        assertThat(repository.findById(id.toString())).isNull()
    }

    @Test
    fun `findActive excludes the two TERMINAL statuses and maps every row`(): Unit = runBlocking {
        stubWithSession<List<FindingEntity>>()
        val query = mockk<Mutiny.SelectionQuery<FindingEntity>>()
        val hql = slot<String>()
        val terminal = slot<Any>()
        every { session.createQuery(capture(hql), FindingEntity::class.java) } returns query
        every { query.setParameter("terminal", capture(terminal)) } returns query
        every { query.resultList } returns Uni.createFrom().item(listOf(entityOf(finding())))

        val result = repository.findActive()

        assertThat(result).hasSize(1)
        assertThat(result.single().id).isEqualTo(id.toString())
        assertThat(hql.captured).contains("FROM FindingEntity").contains("ORDER BY detectedAt DESC")
        assertThat(terminal.captured as List<*>)
            .containsExactlyInAnyOrder(FindingStatus.RESOLVED, FindingStatus.REJECTED)
    }

    /** A detached row as the database would hand it back. */
    private fun entityOf(f: ReleaseStewardFinding): FindingEntity = FindingEntity().also {
        it.id = UUID.fromString(f.id)
        it.checkType = f.checkType
        it.severity = f.severity
        it.detectedAt = f.detectedAt
        it.title = f.title
        it.component = f.component
        it.prNumber = f.prNumber
        it.prUrl = f.prUrl
        it.rawMetricValue = f.rawMetricValue
        it.threshold = f.threshold
        it.rootCause = f.rootCause
        it.proposalUrl = f.proposalUrl
        it.proposedFixDiff = f.proposedFixDiff
        it.status = f.status
        it.diagnosedAt = f.diagnosedAt
        it.proposedAt = f.proposedAt
    }
}
