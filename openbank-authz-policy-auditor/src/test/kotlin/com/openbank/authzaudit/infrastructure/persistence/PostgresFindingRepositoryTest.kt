// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.persistence

import com.openbank.authzaudit.domain.model.AuthzPolicyCheckType
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.FindingSeverity
import com.openbank.authzaudit.domain.model.FindingStatus
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
 * The entity mapping and the three query shapes, driven over a stubbed [Mutiny.SessionFactory] so
 * no database is needed. What is being tested is the part a Testcontainers IT would NOT single out:
 * that a domain finding survives the round trip through [FindingEntity] field by field, that
 * `update` upserts rather than losing a finding whose row is gone, and that `findActive` excludes
 * the two terminal statuses (a finding a human already rejected must not come back to the queue).
 */
class PostgresFindingRepositoryTest {

    private val session = mockk<Mutiny.Session>()

    @Suppress("UNCHECKED_CAST")
    private fun factory(): Mutiny.SessionFactory {
        val sf = mockk<Mutiny.SessionFactory>()
        every { sf.withTransaction(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
        every { sf.withSession(any<Function<Mutiny.Session, Uni<Any>>>()) } answers {
            (firstArg<Function<Mutiny.Session, Uni<Any>>>()).apply(session)
        }
        return sf
    }

    private val id = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun finding(status: FindingStatus = FindingStatus.DIAGNOSED) = AuthzPolicyFinding(
        id = id.toString(),
        checkType = AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT,
        severity = FindingSeverity.WARNING,
        detectedAt = Instant.parse("2026-08-02T06:00:00Z"),
        title = "charter allow token is not in tool_tiers",
        component = "authz-policy-auditor",
        filePath = "openbank-libs/governance/agents.yaml",
        rawMetricValue = BigDecimal("2"),
        threshold = BigDecimal.ZERO,
        rootCause = "the tier vocabulary moved",
        proposalUrl = "https://github.com/JiRaska/open-bank-oss/issues/7",
        proposedFixDiff = null,
        status = status,
        diagnosedAt = Instant.parse("2026-08-02T06:05:00Z"),
        proposedAt = Instant.parse("2026-08-02T06:06:00Z"),
    )

    @Test
    fun `save maps every domain field onto the entity that is persisted`() {
        val persisted = slot<FindingEntity>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val domain = finding()
        val result = runBlocking { PostgresFindingRepository(factory()).save(domain) }

        assertThat(result).isEqualTo(domain)
        val entity = persisted.captured
        assertThat(entity.id).isEqualTo(id)
        assertThat(entity.checkType).isEqualTo(AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT)
        assertThat(entity.severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(entity.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(entity.detectedAt).isEqualTo(domain.detectedAt)
        assertThat(entity.title).isEqualTo(domain.title)
        assertThat(entity.component).isEqualTo(domain.component)
        assertThat(entity.filePath).isEqualTo(domain.filePath)
        assertThat(entity.rawMetricValue).isEqualByComparingTo(domain.rawMetricValue)
        assertThat(entity.threshold).isEqualByComparingTo(domain.threshold)
        assertThat(entity.rootCause).isEqualTo(domain.rootCause)
        assertThat(entity.proposalUrl).isEqualTo(domain.proposalUrl)
        assertThat(entity.proposedFixDiff).isNull()
        assertThat(entity.diagnosedAt).isEqualTo(domain.diagnosedAt)
        assertThat(entity.proposedAt).isEqualTo(domain.proposedAt)
    }

    @Test
    fun `update mutates the managed row in place and does not insert a second one`() {
        val existing = FindingEntity().apply {
            this.id = this@PostgresFindingRepositoryTest.id
            checkType = AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT
            severity = FindingSeverity.WARNING
            detectedAt = Instant.parse("2026-08-02T06:00:00Z")
            title = "stale title"
            component = "authz-policy-auditor"
            filePath = "openbank-libs/governance/agents.yaml"
            rawMetricValue = BigDecimal.ONE
            threshold = BigDecimal.ZERO
            status = FindingStatus.OPEN
        }
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().item(existing)

        val updated = finding(status = FindingStatus.PROPOSED)
        val result = runBlocking { PostgresFindingRepository(factory()).update(updated) }

        assertThat(result).isEqualTo(updated)
        assertThat(existing.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(existing.title).isEqualTo(updated.title)
        assertThat(existing.proposalUrl).isEqualTo(updated.proposalUrl)
        // A persist here would be a duplicate-key crash on an application-assigned id.
        verify(exactly = 0) { session.persist(any()) }
    }

    @Test
    fun `update of a finding whose row is gone falls back to an insert rather than losing it`() {
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()
        val persisted = slot<FindingEntity>()
        every { session.persist(capture(persisted)) } returns Uni.createFrom().voidItem()

        val result = runBlocking { PostgresFindingRepository(factory()).update(finding()) }

        assertThat(result).isEqualTo(finding())
        assertThat(persisted.captured.id).isEqualTo(id)
        assertThat(persisted.captured.status).isEqualTo(FindingStatus.DIAGNOSED)
    }

    @Test
    fun `findActive excludes the terminal statuses and maps rows back to the domain`() {
        val query = mockk<Mutiny.SelectionQuery<FindingEntity>>()
        val hql = slot<String>()
        val terminal = slot<Any>()
        every { session.createQuery(capture(hql), FindingEntity::class.java) } returns query
        every { query.setParameter("terminal", capture(terminal)) } returns query
        every { query.resultList } returns Uni.createFrom().item(listOf(entityOf(finding())))

        val rows = runBlocking { PostgresFindingRepository(factory()).findActive() }

        assertThat(hql.captured).contains("status NOT IN (:terminal)").contains("ORDER BY detectedAt DESC")
        assertThat(terminal.captured as List<*>)
            .containsExactlyInAnyOrder(FindingStatus.RESOLVED, FindingStatus.REJECTED)
        assertThat(rows).containsExactly(finding())
    }

    @Test
    fun `findById returns null for an id with no row and the mapped finding otherwise`() {
        every { session.find(FindingEntity::class.java, id) } returns Uni.createFrom().nullItem()
        assertThat(runBlocking { PostgresFindingRepository(factory()).findById(id.toString()) }).isNull()

        every { session.find(FindingEntity::class.java, id) } returns
            Uni.createFrom().item(entityOf(finding()))
        assertThat(runBlocking { PostgresFindingRepository(factory()).findById(id.toString()) })
            .isEqualTo(finding())
    }

    private fun entityOf(f: AuthzPolicyFinding) = FindingEntity().apply {
        id = UUID.fromString(f.id)
        checkType = f.checkType
        severity = f.severity
        detectedAt = f.detectedAt
        title = f.title
        component = f.component
        filePath = f.filePath
        rawMetricValue = f.rawMetricValue
        threshold = f.threshold
        rootCause = f.rootCause
        proposalUrl = f.proposalUrl
        proposedFixDiff = f.proposedFixDiff
        status = f.status
        diagnosedAt = f.diagnosedAt
        proposedAt = f.proposedAt
    }
}
