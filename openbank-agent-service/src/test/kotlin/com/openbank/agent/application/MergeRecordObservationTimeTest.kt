// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.`in`.MergeRecordCommand
import com.openbank.agent.application.port.out.AgentMergeRecordRepository
import com.openbank.agent.domain.mergerecord.MergeActorType
import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeAuthorizationKind
import com.openbank.libs.audit.AuditEventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Deterministic boundary proof for the recorder/server clock-skew allowance. */
class MergeRecordObservationTimeTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val repository = mockk<AgentMergeRecordRepository>()
    private val publisher = mockk<AuditEventPublisher>()
    private val service = MergeRecordService(repository, publisher, Clock.fixed(now, ZoneOffset.UTC))

    private fun command(observedAt: Instant) = MergeRecordCommand(
        agentId = "fixture-agent",
        prNumber = 9035,
        prUrl = "https://github.com/JiRaska/open-bank-oss/pull/9035",
        headBranch = "codex/fixture",
        mergedBy = "openbank-gitops-bot[bot]",
        mergedAt = Instant.parse("2026-10-01T12:00:00Z"),
        title = "fixture merge",
        proposalId = null,
        source = "github-actions",
        mergedByType = MergeActorType.GITHUB_APP,
        mergedById = 9876,
        headSha = "a".repeat(40),
        mergeCommitSha = "b".repeat(40),
        authorization = MergeAuthorization(
            approvedBy = "fixture-owner",
            approvedById = 1234,
            approvedAt = Instant.parse("2026-10-01T11:59:00Z"),
            evidenceUrl = "https://github.com/JiRaska/open-bank-oss/pull/9035",
            kind = MergeAuthorizationKind.LEGACY_PR_AUTO_MERGE,
            pullRequestNodeId = "PR_fixture",
            mergeEventNodeId = "ME_fixture",
            mergeEventAt = Instant.parse("2026-10-01T12:00:01Z"),
            observedAt = observedAt,
        ),
    )

    @Test
    fun `observation at the sixty-second clock-skew boundary is accepted`() {
        every { repository.insertOrGetExisting(any()) } answers {
            AgentMergeRecordRepository.InsertResult(firstArg(), inserted = true)
        }
        coEvery { publisher.publish(any()) } just runs
        val result = service.record(command(now.plusSeconds(60)))
        assertThat(result.inserted).isTrue()
        assertThat(result.record.authorization?.observedAt).isEqualTo(now.plusSeconds(60))
        verify(exactly = 1) { repository.insertOrGetExisting(any()) }
        coVerify(exactly = 1) { publisher.publish(any()) }
    }

    @Test
    fun `observation beyond the clock-skew boundary is rejected before storage or audit`() {
        assertThatThrownBy { service.record(command(now.plusSeconds(61))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("legacy evidence observation time is in the future")
        verify(exactly = 0) { repository.insertOrGetExisting(any()) }
        coVerify(exactly = 0) { publisher.publish(any()) }
    }
}
