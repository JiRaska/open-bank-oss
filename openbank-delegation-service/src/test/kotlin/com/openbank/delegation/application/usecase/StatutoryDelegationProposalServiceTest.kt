// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.domain.model.StatutoryRepresentative
import com.openbank.delegation.domain.model.StatutoryRuleMode
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StatutoryDelegationProposalServiceTest {
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC)
    private val principal = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val otherSigner = UUID.randomUUID()
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
            StatutoryRepresentative(otherSigner, setOf(1), setOf("director")),
        ),
    )
    private val rules = mockk<StatutoryRuleClient>()
    private val validator = mockk<DelegationService>()
    private val repository = mockk<StatutoryDelegationOperationRepository>()
    private val service = StatutoryDelegationProposalService(rules, validator, repository, mapper, clock)

    @Test
    fun `proposal stores a content-addressed inert operation after live rule and ordinary draft gates`(): Unit =
        runBlocking {
            val draft = draft()
            coEvery { rules.resolve(principal, actor) } returns StatutoryRuleResolution.RosterMatched(rule)
            coJustRun { validator.validateStatutoryProposal(draft) }
            coEvery { repository.create(any()) } answers { StatutoryOperationCreateOutcome.Created(firstArg()) }

            val result = service.propose(draft, "offer-1")
            val operation = result.operation

            assertThat(result).isInstanceOf(StatutoryOperationCreateOutcome.Created::class.java)
            assertThat(operation.principalPartyId).isEqualTo(principal)
            assertThat(operation.initiatorPartyId).isEqualTo(actor)
            assertThat(operation.expiresAt).isEqualTo(clock.instant().plusSeconds(24 * 3600))
            assertThat(operation.grantId).isNull()
            assertThat(mapper.readTree(operation.payloadJson).path("capabilities").map { it.asText() })
                .containsExactly("ACCOUNT_READ_BALANCES", "ACCOUNT_VIEW_DETAILS")
            assertThat(operation.requestHash)
                .isEqualTo(StatutoryOperationEvidence(mapper).hash(operation.payloadJson))
            assertThat(
                operation.ruleHash,
            ).isEqualTo(StatutoryOperationEvidence(mapper).hash(operation.ruleSnapshotJson))
            coVerify(exactly = 1) { validator.validateStatutoryProposal(draft) }
            coVerify(exactly = 1) { repository.create(any()) }
        }

    @Test
    fun `caller cannot propose as a different company or an unauthenticated actor`(): Unit = runBlocking {
        listOf(draft().copy(callerPartyId = UUID.randomUUID()), draft().copy(actorPartyId = null)).forEach { invalid ->
            assertThatThrownBy { runBlocking { service.propose(invalid, "offer-1") } }
                .isInstanceOf(StatutoryProposalDenied::class.java)
        }
        coVerify(exactly = 0) { rules.resolve(any(), any()) }
        coVerify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `missing or unverifiable legal authority never writes a proposal`(): Unit = runBlocking {
        coEvery { rules.resolve(principal, actor) } returnsMany listOf(
            StatutoryRuleResolution.Denied,
            StatutoryRuleResolution.Unverifiable,
        )
        assertThatThrownBy { runBlocking { service.propose(draft(), "offer-1") } }
            .isInstanceOf(StatutoryProposalDenied::class.java)
        assertThatThrownBy { runBlocking { service.propose(draft(), "offer-1") } }
            .isInstanceOf(StatutoryProposalUnavailable::class.java)
        coVerify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `a changed legal rule makes an unexecuted proposal unreadable for signing`(): Unit = runBlocking {
        val operation = operation()
        coEvery { repository.find(operation.id, principal) } returns operation
        coEvery { rules.resolve(principal, actor) } returns
            StatutoryRuleResolution.RosterMatched(rule.copy(revision = 2))

        assertThatThrownBy { runBlocking { service.get(operation.id, principal, principal, actor) } }
            .isInstanceOf(StatutoryProposalStale::class.java)
    }

    @Test
    fun `an expired pending proposal is not revived by an exact idempotency replay`(): Unit = runBlocking {
        val draft = draft()
        val expired = operation().copy(
            createdAt = clock.instant().minusSeconds(3600),
            expiresAt = clock.instant().minusSeconds(1),
        )
        coEvery { rules.resolve(principal, actor) } returns StatutoryRuleResolution.RosterMatched(rule)
        coJustRun { validator.validateStatutoryProposal(draft) }
        coEvery { repository.create(any()) } returns StatutoryOperationCreateOutcome.Replayed(expired)

        assertThatThrownBy { runBlocking { service.propose(draft, "offer-1") } }
            .isInstanceOf(StatutoryProposalStale::class.java)
    }

    @Test
    fun `reordered capability and representative sets keep the same signed bytes`() {
        val evidence = StatutoryOperationEvidence(mapper)
        val first = draft()
        val reversed = first.copy(capabilities = first.capabilities.reversed().toSet())
        assertThat(evidence.payload(first)).isEqualTo(evidence.payload(reversed))
        assertThat(evidence.rule(rule)).isEqualTo(
            evidence.rule(
                rule.copy(
                    eligibleRepresentatives = rule.eligibleRepresentatives.reversed(),
                ),
            ),
        )
    }

    @Test
    fun `frozen payload round trips to the same draft for execution revalidation`() {
        val evidence = StatutoryOperationEvidence(mapper)
        val draft = draft()

        val restored = evidence.decode(evidence.payload(draft), principal, actor)

        assertThat(evidence.payload(restored)).isEqualTo(evidence.payload(draft))
    }

    private fun draft() = PreviewDelegationCommand(
        callerPartyId = principal,
        actorPartyId = actor,
        grantorPartyId = principal,
        granteePartyId = UUID.randomUUID(),
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = linkedSetOf(
            DelegationCapability.ACCOUNT_VIEW_DETAILS,
            DelegationCapability.ACCOUNT_READ_BALANCES,
        ),
        approvalPolicy = ApprovalPolicy.SOLO,
        validTo = null,
    )

    private fun operation() = with(StatutoryOperationEvidence(mapper)) {
        val payload = payload(draft())
        val snapshot = rule(rule)
        com.openbank.delegation.domain.model.StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = principal,
            initiatorPartyId = actor,
            requestKey = "offer-1",
            requestHash = hash(payload),
            payloadJson = payload,
            policyId = rule.policyId,
            policyRevision = rule.revision,
            sourceCaseId = rule.sourceCaseId,
            ruleHash = hash(snapshot),
            ruleSnapshotJson = snapshot,
            createdAt = clock.instant(),
            expiresAt = clock.instant().plusSeconds(3600),
        )
    }
}
