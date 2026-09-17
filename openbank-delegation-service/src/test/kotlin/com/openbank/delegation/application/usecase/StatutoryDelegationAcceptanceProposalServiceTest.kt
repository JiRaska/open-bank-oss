// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryOperationCreateOutcome
import com.openbank.delegation.application.port.out.StatutoryRuleClient
import com.openbank.delegation.application.port.out.StatutoryRuleResolution
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryOperationKind
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

class StatutoryDelegationAcceptanceProposalServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC)
    private val company = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val grant = DelegationGrant(
        grantorPartyId = UUID.randomUUID(),
        granteePartyId = company,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validFrom = OffsetDateTime.now(clock),
        validTo = OffsetDateTime.now(clock).plusDays(30),
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
    private val grants = mockk<DelegationRepository>()
    private val rules = mockk<StatutoryRuleClient>()
    private val operations = mockk<StatutoryDelegationOperationRepository>()
    private val service = StatutoryDelegationAcceptanceProposalService(grants, rules, operations, ObjectMapper(), clock)

    @Test
    fun `joint acceptance proposal freezes exact offered grant and live legal rule without activating it`(): Unit =
        runBlocking {
            coEvery { grants.findById(grant.id) } returns grant
            coEvery { rules.resolve(company, actor) } returns StatutoryRuleResolution.RosterMatched(rule)
            coEvery { operations.create(any()) } answers { StatutoryOperationCreateOutcome.Created(firstArg()) }

            val operation = service.propose(grant.id, company, company, actor, "accept-1").operation

            assertThat(operation.operationKind).isEqualTo(StatutoryOperationKind.ACCEPT)
            assertThat(operation.targetGrantId).isEqualTo(grant.id)
            assertThat(operation.expectedLifecycleRevision).isEqualTo(0)
            assertThat(operation.grantId).isNull()
            assertThat(operation.expiresAt).isEqualTo(clock.instant().plusSeconds(86_400))
            assertThat(operation.payloadJson).contains("\"kind\":\"ACCEPT\"")
            assertThat(operation.payloadJson).contains(grant.resourceId.toString())
        }

    @Test
    fun `other company cannot learn whether grant exists or reach legal roster`(): Unit = runBlocking {
        coEvery { grants.findById(grant.id) } returns grant
        assertThatThrownBy {
            runBlocking { service.propose(grant.id, UUID.randomUUID(), UUID.randomUUID(), actor, "accept-1") }
        }.isInstanceOf(StatutoryProposalDenied::class.java)
        assertThatThrownBy {
            runBlocking { service.propose(grant.id, UUID.randomUUID(), company, actor, "accept-1") }
        }.isInstanceOf(StatutoryProposalDenied::class.java)
        val unrelatedCompany = UUID.randomUUID()
        assertThatThrownBy {
            runBlocking { service.propose(grant.id, unrelatedCompany, unrelatedCompany, actor, "accept-1") }
        }.isInstanceOf(StatutoryProposalNotFound::class.java)
        coVerify(exactly = 0) { rules.resolve(any(), any()) }
        coVerify(exactly = 0) { operations.create(any()) }
    }

    @Test
    fun `closed or expired offers never create an acceptance proposal`(): Unit = runBlocking {
        coEvery { grants.findById(grant.id) } returnsMany listOf(
            grant.copy(status = DelegationStatus.DECLINED),
            grant.copy(validFrom = OffsetDateTime.now(clock).minusDays(1), validTo = OffsetDateTime.now(clock)),
        )
        repeat(2) {
            assertThatThrownBy {
                runBlocking { service.propose(grant.id, company, company, actor, "accept-1") }
            }.isInstanceOf(StatutoryProposalDenied::class.java)
        }
        coVerify(exactly = 0) { operations.create(any()) }
    }

    @Test
    fun `changed offer revision or legal rule invalidates pending ballot`(): Unit = runBlocking {
        coEvery { grants.findById(grant.id) } returns grant
        coEvery { rules.resolve(company, actor) } returns StatutoryRuleResolution.RosterMatched(rule)
        coEvery { operations.create(any()) } answers { StatutoryOperationCreateOutcome.Created(firstArg()) }
        val operation = service.propose(grant.id, company, company, actor, "accept-1").operation
        coEvery { operations.find(operation.id, company) } returns operation
        assertThat(service.pending(operation.id, company, company, actor)).isEqualTo(operation)

        coEvery { grants.findById(grant.id) } returns grant.copy(lifecycleRevision = 1)
        assertThatThrownBy { runBlocking { service.pending(operation.id, company, company, actor) } }
            .isInstanceOf(StatutoryProposalStale::class.java)

        coEvery { grants.findById(grant.id) } returns grant
        coEvery { rules.resolve(company, actor) } returns StatutoryRuleResolution.RosterMatched(rule.copy(revision = 2))
        assertThatThrownBy { runBlocking { service.pending(operation.id, company, company, actor) } }
            .isInstanceOf(StatutoryProposalStale::class.java)
    }

    @Test
    fun `acceptance inbox requests only the acceptance family under the live company rule`(): Unit = runBlocking {
        coEvery { grants.findById(grant.id) } returns grant
        coEvery { rules.resolve(company, actor) } returns StatutoryRuleResolution.RosterMatched(rule)
        coEvery { operations.create(any()) } answers { StatutoryOperationCreateOutcome.Created(firstArg()) }
        val acceptance = service.propose(grant.id, company, company, actor, "accept-page").operation
        coEvery {
            operations.pending(
                company,
                acceptance.ruleHash,
                clock.instant(),
                21,
                null,
                null,
                StatutoryOperationKind.ACCEPT,
            )
        } returns listOf(acceptance)

        assertThat(service.page(company, company, actor, null, null).operations).containsExactly(acceptance)
        coVerify(exactly = 1) {
            operations.pending(
                company,
                acceptance.ruleHash,
                clock.instant(),
                21,
                null,
                null,
                StatutoryOperationKind.ACCEPT,
            )
        }
        assertThatThrownBy { runBlocking { service.page(company, other, actor, null, null) } }
            .isInstanceOf(StatutoryProposalDenied::class.java)
        assertThatThrownBy { runBlocking { service.page(company, company, actor, null, 51) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        val issuanceCursor = StatutoryInboxCursor.encode(acceptance.ruleHash, acceptance)
        assertThatThrownBy { runBlocking { service.page(company, company, actor, issuanceCursor, 20) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
