// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.PensionContractService
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PensionContractServiceTest {

    private class InMemoryRepo : PensionContractRepository {
        val rows = mutableMapOf<UUID, PensionContract>()
        override suspend fun save(contract: PensionContract) = contract.also { rows[it.id] = it }
        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun findByIdempotencyKey(participantPartyId: UUID, idempotencyKey: String) =
            rows.values.firstOrNull {
                it.participantPartyId == participantPartyId && it.idempotencyKey == idempotencyKey
            }
        override suspend fun findByParticipant(participantPartyId: UUID, limit: Int) =
            rows.values.filter { it.participantPartyId == participantPartyId }.take(limit)
        override suspend fun findByStatus(status: ContractStatus?, limit: Int) =
            rows.values.filter { status == null || it.status == status }.take(limit)
    }

    private val repo = InMemoryRepo()
    private val clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC)
    private val service = PensionContractService(repo, JurisdictionPackLoader.loadRegistry(), clock)

    private val party = UUID.randomUUID()
    private val me = Caller.customer(party)

    private fun command(
        line: ProductLine = ProductLine.DPS,
        provider: ProviderType = ProviderType.PENSION_COMPANY,
        birth: String = "1990-01-01",
        currency: String = "CZK",
        key: String? = null,
    ) = CreateDraftCommand(
        participantPartyId = party,
        productLine = line,
        jurisdiction = "CZ",
        providerEntityId = UUID.randomUUID(),
        providerType = provider,
        birthDate = LocalDate.parse(birth),
        residencyCountry = "CZ",
        residencyEvidence = emptySet(),
        hasGuardian = false,
        schedule = ContributionSchedule(BigDecimal("1700"), currency, ContributionFrequency.MONTHLY),
        initialStrategy = "BALANCED",
        beneficiaries = emptyList(),
        idempotencyKey = key,
    )

    private suspend fun seed(status: ContractStatus = ContractStatus.DRAFT): PensionContract {
        val input = command()
        val draft = PensionContract.draft(
            participantPartyId = party,
            productLine = input.productLine,
            jurisdiction = input.jurisdiction,
            packVersion = 1,
            providerEntityId = input.providerEntityId,
            providerType = input.providerType,
            participantBirthDate = input.birthDate,
            schedule = input.schedule,
            initialStrategy = input.initialStrategy,
            beneficiaries = emptyList(),
            today = LocalDate.parse("2026-10-09"),
            now = clock.instant(),
        )
        val chosen = when (status) {
            ContractStatus.DRAFT -> draft
            ContractStatus.PENDING_ACTIVATION -> draft.submit(clock.instant())
            ContractStatus.ACTIVE -> draft.submit(clock.instant()).activate(
                LocalDate.parse("2026-10-09"),
                clock.instant(),
            )
            else -> error("unsupported test status")
        }
        return repo.save(chosen)
    }

    @Test
    fun `direct create cannot bypass onboarding even with an idempotency key`(): Unit = runBlocking {
        repeat(2) {
            assertThatThrownBy { runBlocking { service.createDraft(command(key = "same-key")) } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("onboarding flow")
        }
        assertThat(repo.rows).isEmpty()
    }

    @Test
    fun `direct create is closed for every product line and provider type`(): Unit = runBlocking {
        listOf(command(provider = ProviderType.BANK), command(ProductLine.DIP, ProviderType.BANK)).forEach { input ->
            assertThatThrownBy { runBlocking { service.createDraft(input) } }
                .isInstanceOf(IllegalStateException::class.java)
        }
        assertThat(repo.rows).isEmpty()
    }

    @Test
    fun `invalid direct create inputs cannot bypass the closed path`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.createDraft(command(birth = "2015-01-01")) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { runBlocking { service.createDraft(command(currency = "EUR")) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(repo.rows).isEmpty()
    }

    @Test
    fun `strategy election cannot use an unassessed unpublished code`(): Unit = runBlocking {
        val active = seed(ContractStatus.ACTIVE)
        assertThatThrownBy { runBlocking { service.electStrategy(me, active.id, "UNPUBLISHED", null) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("strategy changes are unavailable")
        assertThat(repo.rows.getValue(active.id).strategyHistory).isEqualTo(active.strategyHistory)
    }

    @Test
    fun `another participant's contract is not found, and staff may read but not change it`(): Unit = runBlocking {
        val id = seed().id
        val stranger = Caller.customer(UUID.randomUUID())
        assertThatThrownBy { runBlocking { service.get(stranger, id) } }
            .isInstanceOf(com.openbank.pension.application.port.out.ContractNotFoundException::class.java)
        assertThatThrownBy { runBlocking { service.submit(stranger, id) } }
            .isInstanceOf(com.openbank.pension.application.port.out.ContractNotFoundException::class.java)
        assertThat(service.get(Caller.STAFF, id).id).isEqualTo(id)
        assertThatThrownBy { runBlocking { service.submit(Caller.STAFF, id) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `direct submit cannot bypass questionnaire KID and signature`(): Unit = runBlocking {
        val draft = seed()
        assertThatThrownBy { runBlocking { service.submit(me, draft.id) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("onboarding flow")
        assertThat(repo.rows.getValue(draft.id).status).isEqualTo(ContractStatus.DRAFT)
    }

    @Test
    fun `reads and status actions remain available`(): Unit = runBlocking {
        val active = seed(ContractStatus.ACTIVE)
        assertThat(service.get(me, active.id)).isEqualTo(active)
        assertThat(service.list(me, ContractStatus.ACTIVE, 10)).contains(active)
        assertThat(service.suspendContributions(me, active.id).status).isEqualTo(ContractStatus.SUSPENDED)
        assertThat(service.resumeContributions(me, active.id).status).isEqualTo(ContractStatus.ACTIVE)
    }

    @Test
    fun `amounts above the bound are refused`(): Unit = runBlocking {
        assertThatThrownBy {
            ContributionSchedule(BigDecimal("1000000000.01"), "CZK", ContributionFrequency.MONTHLY)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
