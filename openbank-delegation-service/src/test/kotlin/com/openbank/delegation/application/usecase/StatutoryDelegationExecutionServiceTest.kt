// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.domain.model.StatutoryRepresentative
import com.openbank.delegation.domain.model.StatutoryRuleMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class StatutoryDelegationExecutionServiceTest {
    private val principal = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC)
    private val mapper = ObjectMapper()
    private val evidence = StatutoryOperationEvidence(mapper)
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
            StatutoryRepresentative(other, setOf(1), setOf("director")),
        ),
    )
    private val draft = PreviewDelegationCommand(
        callerPartyId = principal,
        actorPartyId = actor,
        grantorPartyId = principal,
        granteePartyId = UUID.randomUUID(),
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validTo = null,
    )
    private val payload = evidence.payload(draft)
    private val snapshot = evidence.rule(rule)
    private val operation = StatutoryDelegationOperation(
        id = UUID.randomUUID(),
        principalPartyId = principal,
        initiatorPartyId = actor,
        requestKey = "proposal-1",
        requestHash = evidence.hash(payload),
        payloadJson = payload,
        policyId = rule.policyId,
        policyRevision = rule.revision,
        sourceCaseId = rule.sourceCaseId,
        ruleHash = evidence.hash(snapshot),
        ruleSnapshotJson = snapshot,
        createdAt = clock.instant(),
        expiresAt = clock.instant().plusSeconds(3600),
    )
    private val proposals = mockk<StatutoryDelegationProposalService>()
    private val validator = mockk<DelegationService>()
    private val grants = mockk<DelegationRepository>()
    private val operations = mockk<StatutoryDelegationOperationRepository>()
    private val service = StatutoryDelegationExecutionService(proposals, validator, grants, operations, mapper, clock)

    @Test
    fun `execution revalidates frozen draft before asking the locked repository to issue`(): Unit = runBlocking {
        val offered = grant()
        coEvery { proposals.current(operation.id, principal, principal, actor) } returns (operation to rule)
        coEvery { validator.buildStatutoryGrant(any(), any()) } returns offered
        coEvery { operations.execute(any(), any(), any(), any(), any(), any(), any()) } returns offered

        val actual = service.execute(operation.id, principal, actor)

        assertThat(actual.id).isEqualTo(offered.id)
        coVerify(exactly = 1) {
            validator.buildStatutoryGrant(
                match { evidence.payload(it) == operation.payloadJson },
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            )
        }
        coVerify(exactly = 1) {
            operations.execute(operation.id, principal, rule, operation.ruleHash, offered, any(), clock.instant())
        }
    }

    @Test
    fun `changed current rule never reaches grant validation or insertion`(): Unit = runBlocking {
        coEvery { proposals.current(operation.id, principal, principal, actor) } returns
            (operation to rule.copy(revision = 2))

        assertThatThrownBy { runBlocking { service.execute(operation.id, principal, actor) } }
            .isInstanceOf(StatutoryProposalStale::class.java)
        coVerify(exactly = 0) { validator.buildStatutoryGrant(any(), any()) }
        coVerify(exactly = 0) { operations.execute(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun grant(): DelegationGrant {
        val at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        return DelegationGrant(
            grantorPartyId = principal,
            granteePartyId = draft.granteePartyId,
            resourceType = draft.resourceType,
            resourceId = draft.resourceId,
            capabilities = draft.capabilities,
            validFrom = at,
            validTo = null,
            createdAt = at,
            updatedAt = at,
        )
    }
}
