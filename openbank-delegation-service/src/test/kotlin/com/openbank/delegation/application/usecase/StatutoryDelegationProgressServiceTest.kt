// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.domain.model.StatutoryRepresentative
import com.openbank.delegation.domain.model.StatutoryRuleMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StatutoryDelegationProgressServiceTest {
    private val principal = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val rule = StatutoryRepresentationRule(
        policyId = UUID.randomUUID(),
        principalPartyId = principal,
        revision = 1,
        sourceCaseId = UUID.randomUUID(),
        attestationId = UUID.randomUUID(),
        ruleTextHash = "a".repeat(64),
        mode = StatutoryRuleMode.JOINT_ALL,
        requiredSignatures = 2,
        requiredOffices = listOf("director"),
        registryRepresentativeCount = 2,
        eligibleRepresentatives = listOf(
            StatutoryRepresentative(actor, setOf(0), setOf("director")),
            StatutoryRepresentative(second, setOf(1), setOf("director")),
        ),
    )
    private val proposals = mockk<StatutoryDelegationProposalService>()
    private val repository = mockk<StatutoryDelegationOperationRepository>()
    private val service = StatutoryDelegationProgressService(proposals, repository)

    @Test
    fun `progress distinguishes incomplete, impossible and satisfied office aware quorum`(): Unit = runBlocking {
        val operation = mockk<StatutoryDelegationOperation> { every { state } returns StatutoryOperationState.PENDING }
        coEvery { proposals.current(id, principal, principal, actor) } returns (operation to rule)
        val rejection = decision(second, StatutoryDecisionVerdict.REJECT)
        val approval = decision(actor, StatutoryDecisionVerdict.APPROVE)
        val secondApproval = decision(second, StatutoryDecisionVerdict.APPROVE)
        coEvery { repository.decisions(id) } returnsMany listOf(
            emptyList(),
            listOf(rejection),
            listOf(approval, secondApproval),
        )

        val waiting = service.get(id, principal, actor)
        assertThat(waiting.quorumSatisfied).isFalse()
        assertThat(waiting.quorumPossible).isTrue()
        assertThat(waiting.myVerdict).isNull()
        val blocked = service.get(id, principal, actor)
        assertThat(blocked.quorumSatisfied).isFalse()
        assertThat(blocked.quorumPossible).isFalse()
        assertThat(blocked.rejectionCount).isEqualTo(1)
        val ready = service.get(id, principal, actor)
        assertThat(ready.quorumSatisfied).isTrue()
        assertThat(ready.approvalCount).isEqualTo(2)
        assertThat(ready.myVerdict).isEqualTo(StatutoryDecisionVerdict.APPROVE)
    }

    @Test
    fun `executed proposal never presents a pending signing action`(): Unit = runBlocking {
        val operation = mockk<StatutoryDelegationOperation> { every { state } returns StatutoryOperationState.EXECUTED }
        coEvery { proposals.current(id, principal, principal, actor) } returns (operation to rule)

        assertThatThrownBy { runBlocking { service.get(id, principal, actor) } }
            .isInstanceOf(StatutoryProposalStale::class.java)
        coVerify(exactly = 0) { repository.decisions(any()) }
    }

    private fun decision(person: UUID, verdict: StatutoryDecisionVerdict) = StatutoryDelegationDecision(
        id,
        person,
        verdict,
        if (verdict == StatutoryDecisionVerdict.APPROVE) UUID.randomUUID() else null,
        Instant.parse("2026-09-18T12:00:00Z"),
    )
}
