// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.application.port.out.StatutoryDecisionConflict
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StatutoryDelegationDecisionServiceTest {
    private val principal = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC)
    private val operation = operation()
    private val session = UUID.randomUUID()
    private val proposals = mockk<StatutoryDelegationProposalService>()
    private val repository = mockk<StatutoryDelegationOperationRepository>()
    private val sca = mockk<ScaChallengeClient>()
    private val service = StatutoryDelegationDecisionService(proposals, repository, sca, clock)

    @Test
    fun `approved decision consumes exact device binding and records no grant`(): Unit = runBlocking {
        allowProposal()
        val hash = service.approvalIntent(operation.id, principal, actor)
        val challenge = challenge(hash)
        coEvery { repository.findDecision(operation.id, actor) } returns null
        coEvery { sca.getChallenge(session) } returns challenge
        coEvery { sca.consumeStatutoryApproval(session, actor, operation.id, hash) } returns
            challenge.copy(consumedAt = clock.instant().toString())
        coEvery { repository.recordDecision(any()) } answers { firstArg() }

        val recorded = service.decide(operation.id, principal, actor, StatutoryDecisionVerdict.APPROVE, session)

        assertThat(recorded.verdict).isEqualTo(StatutoryDecisionVerdict.APPROVE)
        assertThat(recorded.scaSessionId).isEqualTo(session)
        assertThat(recorded.actorPartyId).isEqualTo(actor)
        assertThat(hash).matches("[0-9a-f]{64}")
        coVerify(exactly = 1) { sca.consumeStatutoryApproval(session, actor, operation.id, hash) }
        coVerify(exactly = 1) { repository.recordDecision(any()) }
    }

    @Test
    fun `mismatched signed content cannot become a decision`(): Unit = runBlocking {
        allowProposal()
        coEvery { repository.findDecision(operation.id, actor) } returns null
        coEvery { sca.getChallenge(session) } returns challenge("f".repeat(64))

        assertThatThrownBy {
            runBlocking { service.decide(operation.id, principal, actor, StatutoryDecisionVerdict.APPROVE, session) }
        }.isInstanceOf(StatutoryDecisionConflict::class.java)
        coVerify(exactly = 0) { sca.consumeStatutoryApproval(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.recordDecision(any()) }
    }

    @Test
    fun `ambiguous consume timeout recovers only the exact already-consumed binding`(): Unit = runBlocking {
        allowProposal()
        val hash = service.approvalIntent(operation.id, principal, actor)
        coEvery { repository.findDecision(operation.id, actor) } returns null
        coEvery { sca.getChallenge(session) } returnsMany listOf(
            challenge(hash),
            challenge(hash).copy(consumedAt = clock.instant().toString()),
        )
        coEvery { sca.consumeStatutoryApproval(session, actor, operation.id, hash) } throws RuntimeException("timeout")
        coEvery { repository.recordDecision(any()) } answers { firstArg() }

        val recorded = service.decide(operation.id, principal, actor, StatutoryDecisionVerdict.APPROVE, session)

        assertThat(recorded.scaSessionId).isEqualTo(session)
        coVerify(exactly = 2) { sca.getChallenge(session) }
        coVerify(exactly = 1) { repository.recordDecision(any()) }
    }

    @Test
    fun `authenticated rejection writes no SCA claim`(): Unit = runBlocking {
        allowProposal()
        coEvery { repository.findDecision(operation.id, actor) } returns null
        coEvery { repository.recordDecision(any()) } answers { firstArg() }

        val recorded = service.decide(operation.id, principal, actor, StatutoryDecisionVerdict.REJECT, null)

        assertThat(recorded.verdict).isEqualTo(StatutoryDecisionVerdict.REJECT)
        assertThat(recorded.scaSessionId).isNull()
        coVerify(exactly = 0) { sca.getChallenge(any()) }
        coVerify(exactly = 0) { sca.consumeStatutoryApproval(any(), any(), any(), any()) }
    }

    private fun allowProposal() {
        coEvery { proposals.get(operation.id, principal, principal, actor) } returns operation
    }

    private fun challenge(hash: String) = ScaChallengeSnapshot(
        id = session,
        partyId = actor,
        purpose = "DELEGATION_STATUTORY_APPROVAL",
        status = "COMPLETED",
        operationId = operation.id.toString(),
        operationHash = hash,
    )

    private fun operation(): StatutoryDelegationOperation {
        val payload = """{"grantorPartyId":"$principal"}"""
        val rule = """{"policyId":"${UUID.randomUUID()}"}"""
        val at = clock.instant()
        return StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = principal,
            initiatorPartyId = actor,
            requestKey = "proposal-1",
            requestHash = sha256(payload),
            payloadJson = payload,
            policyId = UUID.randomUUID(),
            policyRevision = 1,
            sourceCaseId = UUID.randomUUID(),
            ruleHash = sha256(rule),
            ruleSnapshotJson = rule,
            createdAt = at,
            expiresAt = at.plusSeconds(3600),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
