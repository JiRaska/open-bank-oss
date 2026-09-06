// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.persistence

import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
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
 * The domain<->entity mapping and the branch structure of [PostgresFindingRepository], driven over a
 * mocked Mutiny session so it stays a pure JVM unit test.
 *
 * The mapping is the part that fails silently in production: a field dropped from `applyFrom` or
 * `toDomain` compiles, persists and reads back — it simply loses data. Each direction is asserted
 * field by field.
 */
class PostgresFindingRepositoryTest {

    private val session = mockk<Mutiny.Session>()
    private val sf = mockk<Mutiny.SessionFactory>()
    private val repository = PostgresFindingRepository(sf)

    private val finding = FlakyTestFinding(
        id = "9d1f5b2e-2a4c-4f1e-9a3d-0c7b6e5f4a31",
        checkType = FlakyTestCheckType.PACT_PROVIDER_CLASS_COLLISION,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-22T10:00:00Z"),
        title = "two provider classes declare the same provider",
        component = "openbank-ledger-service",
        filePath = "src/test/kotlin/Foo.kt",
        rawMetricValue = BigDecimal("2"),
        threshold = BigDecimal.ONE,
        rootCause = "the broker fetch collides",
        proposalUrl = "https://example.invalid/pr/1",
        proposedFixDiff = "--- a\n+++ b\n",
        status = FindingStatus.PROPOSED,
        diagnosedAt = Instant.parse("2026-08-22T10:05:00Z"),
        proposedAt = Instant.parse("2026-08-22T10:06:00Z"),
    )

    @Suppress("UNCHECKED_CAST")
    private fun runWorkInTransaction() {
        every { sf.withTransaction(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runWorkInSession() {
        every { sf.withSession(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
    }

    private fun entityOf(source: FlakyTestFinding): FindingEntity = FindingEntity().apply {
        id = UUID.fromString(source.id)
        checkType = source.checkType
        severity = source.severity
        detectedAt = source.detectedAt
        title = source.title
        component = source.component
        filePath = source.filePath
        rawMetricValue = source.rawMetricValue
        threshold = source.threshold
        rootCause = source.rootCause
        proposalUrl = source.proposalUrl
        proposedFixDiff = source.proposedFixDiff
        status = source.status
        diagnosedAt = source.diagnosedAt
        proposedAt = source.proposedAt
    }

    @Test
    fun `save persists every domain field onto the entity`(): Unit = runBlocking {
        runWorkInTransaction()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val returned = repository.save(finding)

        assertThat(returned).isEqualTo(finding)
        val entity = persisted.captured as FindingEntity
        assertThat(entity.id).isEqualTo(UUID.fromString(finding.id))
        assertThat(entity.checkType).isEqualTo(finding.checkType)
        assertThat(entity.severity).isEqualTo(finding.severity)
        assertThat(entity.detectedAt).isEqualTo(finding.detectedAt)
        assertThat(entity.title).isEqualTo(finding.title)
        assertThat(entity.component).isEqualTo(finding.component)
        assertThat(entity.filePath).isEqualTo(finding.filePath)
        assertThat(entity.rawMetricValue).isEqualByComparingTo(finding.rawMetricValue)
        assertThat(entity.threshold).isEqualByComparingTo(finding.threshold)
        assertThat(entity.rootCause).isEqualTo(finding.rootCause)
        assertThat(entity.proposalUrl).isEqualTo(finding.proposalUrl)
        assertThat(entity.proposedFixDiff).isEqualTo(finding.proposedFixDiff)
        assertThat(entity.status).isEqualTo(finding.status)
        assertThat(entity.diagnosedAt).isEqualTo(finding.diagnosedAt)
        assertThat(entity.proposedAt).isEqualTo(finding.proposedAt)
    }

    @Test
    fun `findById maps every entity column back into the domain finding`(): Unit = runBlocking {
        runWorkInSession()
        every { session.find(FindingEntity::class.java, UUID.fromString(finding.id)) } returns
            Uni.createFrom().item(entityOf(finding))

        assertThat(repository.findById(finding.id)).isEqualTo(finding)
    }

    @Test
    fun `findById returns null for an id the session does not resolve`(): Unit = runBlocking {
        runWorkInSession()
        every { session.find(FindingEntity::class.java, any<Any>()) } returns Uni.createFrom().nullItem()

        assertThat(repository.findById(finding.id)).isNull()
    }

    @Test
    fun `update mutates the managed row in place instead of persisting a duplicate`(): Unit = runBlocking {
        runWorkInTransaction()
        val managed = entityOf(finding)
        every { session.find(FindingEntity::class.java, UUID.fromString(finding.id)) } returns
            Uni.createFrom().item(managed)

        val resolved = finding.copy(status = FindingStatus.RESOLVED, rootCause = null, proposalUrl = null)
        assertThat(repository.update(resolved)).isEqualTo(resolved)

        // The managed instance carries the new state, and nothing was persisted — an assigned-id
        // entity re-persisted here would hit a duplicate-key violation at flush.
        assertThat(managed.status).isEqualTo(FindingStatus.RESOLVED)
        assertThat(managed.rootCause).isNull()
        assertThat(managed.proposalUrl).isNull()
        verify(exactly = 0) { session.persist(any()) }
    }

    @Test
    fun `update falls back to an insert when the row is absent`(): Unit = runBlocking {
        runWorkInTransaction()
        every { session.find(FindingEntity::class.java, any<Any>()) } returns Uni.createFrom().nullItem()
        val persisted = slot<Any>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        assertThat(repository.update(finding)).isEqualTo(finding)
        assertThat((persisted.captured as FindingEntity).id).isEqualTo(UUID.fromString(finding.id))
    }

    @Test
    fun `findActive excludes the terminal statuses and maps each row`(): Unit = runBlocking {
        runWorkInSession()
        val query = mockk<Mutiny.SelectionQuery<FindingEntity>>()
        val terminal = slot<Any>()
        every { session.createQuery(any<String>(), FindingEntity::class.java) } returns query
        every { query.setParameter("terminal", capture(terminal)) } returns query
        every { query.resultList } returns Uni.createFrom().item(listOf(entityOf(finding)))

        assertThat(repository.findActive()).containsExactly(finding)

        @Suppress("UNCHECKED_CAST")
        assertThat(terminal.captured as List<FindingStatus>)
            .containsExactlyInAnyOrder(FindingStatus.RESOLVED, FindingStatus.REJECTED)
    }
}
