// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import com.openbank.delegation.domain.model.StatutoryOperationState
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.delegation.domain.model.StatutoryRepresentative
import com.openbank.delegation.domain.model.StatutoryRuleMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class StatutoryDelegationAcceptanceExecutionServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
    private val company = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val offered = DelegationGrant(
        grantorPartyId = UUID.randomUUID(),
        granteePartyId = company,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validFrom = OffsetDateTime.now(clock),
        validTo = null,
        createdAt = OffsetDateTime.now(clock),
        updatedAt = OffsetDateTime.now(clock),
    )
    private val rule = StatutoryRepresentationRule(
        policyId = UUID.randomUUID(),
        principalPartyId = company,
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
    private val operation = operation()
    private val proposals = mockk<StatutoryDelegationAcceptanceProposalService>()
    private val grants = mockk<DelegationRepository>()
    private val operations = mockk<StatutoryDelegationOperationRepository>()
    private val service = StatutoryDelegationAcceptanceExecutionService(
        proposals,
        grants,
        operations,
        ObjectMapper(),
        clock,
    )

    @Test
    fun `already executed retry reads linked grant without emitting another event`(): Unit = runBlocking {
        val active = offered.acceptJoint(operation.id, OffsetDateTime.now(clock))
        coEvery { proposals.current(operation.id, company, company, actor) } returns
            (
                operation.copy(
                    state = StatutoryOperationState.EXECUTED,
                    grantId = offered.id,
                    executedAt = clock.instant(),
                ) to
                    rule
                )
        coEvery { grants.findById(offered.id) } returns active

        assertThat(service.execute(operation.id, company, actor)).isEqualTo(active)
        coVerify(exactly = 0) { operations.executeAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pending execution sends exact offer and activated event to transactional repository`(): Unit = runBlocking {
        coEvery { proposals.current(operation.id, company, company, actor) } returns (operation to rule)
        coEvery { grants.findById(offered.id) } returns offered
        coEvery { operations.executeAcceptance(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            offered.acceptJoint(operation.id, OffsetDateTime.now(clock))
        }

        val active = service.execute(operation.id, company, actor)

        assertThat(active.acceptStatutoryOperationId).isEqualTo(operation.id)
        coVerify(exactly = 1) {
            operations.executeAcceptance(
                operation.id,
                company,
                rule,
                any(),
                match { it.contains(offered.id.toString()) },
                offered,
                match { it.aggregateId == offered.id && it.lifecycleRevision == 1L },
                clock.instant(),
            )
        }
    }

    private fun operation(): StatutoryDelegationOperation {
        val payload = """{"kind":"ACCEPT","grantId":"${offered.id}"}"""
        val snapshot = """{"policyId":"${rule.policyId}"}"""
        return StatutoryDelegationOperation(
            id = UUID.randomUUID(),
            principalPartyId = company,
            initiatorPartyId = actor,
            requestKey = "accept-1",
            requestHash = sha256(payload),
            payloadJson = payload,
            policyId = rule.policyId,
            policyRevision = rule.revision,
            sourceCaseId = rule.sourceCaseId,
            ruleHash = sha256(snapshot),
            ruleSnapshotJson = snapshot,
            createdAt = clock.instant(),
            expiresAt = clock.instant().plusSeconds(3600),
            operationKind = StatutoryOperationKind.ACCEPT,
            targetGrantId = offered.id,
            expectedLifecycleRevision = 0,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
