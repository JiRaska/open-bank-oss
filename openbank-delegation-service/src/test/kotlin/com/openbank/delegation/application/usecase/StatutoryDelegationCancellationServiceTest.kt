// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StatutoryDelegationCancellationServiceTest {
    private val company = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val operationId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val operation = mockk<StatutoryDelegationOperation> {
        every { operationKind } returns StatutoryOperationKind.ISSUE
        every { initiatorPartyId } returns actor
    }
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
    private val rules = mockk<StatutoryRuleClient>()
    private val repository = mockk<StatutoryDelegationOperationRepository>()
    private val service = StatutoryDelegationCancellationService(
        rules,
        repository,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `current initiator cancels only the selected company's exact kind`(): Unit = runBlocking {
        coEvery { repository.find(operationId, company) } returns operation
        coEvery { rules.resolve(company, actor) } returns StatutoryRuleResolution.RosterMatched(rule)
        coEvery { repository.cancel(operationId, company, actor, StatutoryOperationKind.ISSUE, now) } returns operation

        assertThat(service.cancel(operationId, company, company, actor, StatutoryOperationKind.ISSUE))
            .isSameAs(operation)
        coVerify(exactly = 1) { repository.cancel(operationId, company, actor, StatutoryOperationKind.ISSUE, now) }
    }

    @Test
    fun `another representative cannot cancel the initiator's proposal`(): Unit = runBlocking {
        coEvery { repository.find(operationId, company) } returns operation
        assertThatThrownBy {
            runBlocking { service.cancel(operationId, company, company, other, StatutoryOperationKind.ISSUE) }
        }.isInstanceOf(StatutoryProposalDenied::class.java)
        coVerify(exactly = 0) { repository.cancel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unverifiable current roster fails closed before touching the ledger`(): Unit = runBlocking {
        coEvery { repository.find(operationId, company) } returns operation
        coEvery { rules.resolve(company, actor) } returns StatutoryRuleResolution.Unverifiable
        assertThatThrownBy {
            runBlocking { service.cancel(operationId, company, company, actor, StatutoryOperationKind.ISSUE) }
        }.isInstanceOf(StatutoryProposalUnavailable::class.java)
        coVerify(exactly = 0) { repository.cancel(any(), any(), any(), any(), any()) }
    }
}
