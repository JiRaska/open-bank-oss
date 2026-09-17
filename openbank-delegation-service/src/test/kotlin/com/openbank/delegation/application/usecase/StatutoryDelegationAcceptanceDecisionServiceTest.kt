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
import com.openbank.delegation.domain.model.StatutoryOperationKind
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

class StatutoryDelegationAcceptanceDecisionServiceTest {
    private val company = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val session = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
    private val operation = operation()
    private val proposals = mockk<StatutoryDelegationAcceptanceProposalService>()
    private val repository = mockk<StatutoryDelegationOperationRepository>()
    private val sca = mockk<ScaChallengeClient>()
    private val service = StatutoryDelegationAcceptanceDecisionService(proposals, repository, sca, clock)

    @Test
    fun `acceptance intent has different hash domain from grant issuance`(): Unit = runBlocking {
        coEvery { proposals.pending(operation.id, company, company, actor) } returns operation

        val acceptHash = service.approvalIntent(operation.id, company, actor)
        val issueHash = StatutoryDecisionCeremony(sca).approvalHash(
            operation.copy(
                operationKind = StatutoryOperationKind.ISSUE,
                targetGrantId = null,
                expectedLifecycleRevision = null,
            ),
            actor,
        )

        assertThat(acceptHash).matches("[0-9a-f]{64}").isNotEqualTo(issueHash)
    }

    @Test
    fun `acceptance refuses issuance purpose before consuming challenge`(): Unit = runBlocking {
        coEvery { proposals.pending(operation.id, company, company, actor) } returns operation
        coEvery { repository.findDecision(operation.id, actor) } returns null
        val hash = service.approvalIntent(operation.id, company, actor)
        coEvery { sca.getChallenge(session) } returns challenge(hash).copy(purpose = "DELEGATION_STATUTORY_APPROVAL")

        assertThatThrownBy {
            runBlocking { service.decide(operation.id, company, actor, StatutoryDecisionVerdict.APPROVE, session) }
        }.isInstanceOf(StatutoryDecisionConflict::class.java)
        coVerify(exactly = 0) { sca.consumeStatutoryApproval(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.recordDecision(any()) }
    }

    @Test
    fun `valid joint acceptance challenge records one ballot, never activates grant`(): Unit = runBlocking {
        coEvery { proposals.pending(operation.id, company, company, actor) } returns operation
        coEvery { repository.findDecision(operation.id, actor) } returns null
        val hash = service.approvalIntent(operation.id, company, actor)
        coEvery { sca.getChallenge(session) } returns challenge(hash)
        coEvery { sca.consumeStatutoryApproval(session, actor, operation.id, hash) } returns
            challenge(hash).copy(consumedAt = clock.instant().toString())
        coEvery { repository.recordDecision(any()) } answers { firstArg() }

        val ballot = service.decide(operation.id, company, actor, StatutoryDecisionVerdict.APPROVE, session)

        assertThat(ballot.operationId).isEqualTo(operation.id)
        assertThat(ballot.actorPartyId).isEqualTo(actor)
        coVerify(exactly = 1) { repository.recordDecision(any()) }
    }

    private fun challenge(hash: String) = ScaChallengeSnapshot(
        id = session,
        partyId = actor,
        purpose = "DELEGATION_STATUTORY_ACCEPTANCE",
        status = "COMPLETED",
        operationId = operation.id.toString(),
        operationHash = hash,
    )

    private fun operation(): StatutoryDelegationOperation {
        val payload = """{"grantId":"${UUID.randomUUID()}"}"""
        val rule = """{"policyId":"${UUID.randomUUID()}"}"""
        return StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = company,
            initiatorPartyId = actor,
            requestKey = "accept-1",
            requestHash = sha256(payload),
            payloadJson = payload,
            policyId = UUID.randomUUID(),
            policyRevision = 1,
            sourceCaseId = UUID.randomUUID(),
            ruleHash = sha256(rule),
            ruleSnapshotJson = rule,
            createdAt = clock.instant(),
            expiresAt = clock.instant().plusSeconds(3600),
            operationKind = StatutoryOperationKind.ACCEPT,
            targetGrantId = UUID.randomUUID(),
            expectedLifecycleRevision = 0,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
