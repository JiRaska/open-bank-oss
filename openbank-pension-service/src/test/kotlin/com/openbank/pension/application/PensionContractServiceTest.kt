// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.out.ParticipantNotificationKind
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.PensionContractService
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.infrastructure.notification.RecordingParticipantNotifier
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
    private val notifier = RecordingParticipantNotifier()
    private val suitability = com.openbank.pension.testsupport.RecordingSuitability()
    private val sca = com.openbank.pension.testsupport.RecordingSca()
    private val service =
        PensionContractService(repo, JurisdictionPackLoader.loadRegistry(), clock, notifier, suitability, sca)

    private fun change(id: UUID, code: String, from: String?, challenge: String? = "sca-${UUID.randomUUID()}") =
        com.openbank.pension.application.port.`in`.ElectStrategyCommand(
            me,
            id,
            code,
            from?.let(LocalDate::parse),
            challenge,
        )

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

    @Test
    fun `a draft pins the pack version in force`(): Unit = runBlocking {
        val draft = service.createDraft(command())
        assertThat(draft.status).isEqualTo(ContractStatus.DRAFT)
        assertThat(draft.packVersion).isEqualTo(2)
        assertThat(repo.rows).containsKey(draft.id)
    }

    @Test
    fun `a provider type the pack does not permit is refused`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.createDraft(command(provider = ProviderType.BANK)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("may not provide")
        assertThat(
            service.createDraft(command(ProductLine.DIP, ProviderType.BANK)).productLine,
        ).isEqualTo(ProductLine.DIP)
    }

    @Test
    fun `an ineligible participant and a foreign currency are refused`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.createDraft(command(birth = "2015-01-01")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { service.createDraft(command(currency = "EUR")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a strategy change cannot take effect in the past`(): Unit = runBlocking {
        val id = service.createDraft(command()).id
        assertThatThrownBy { runBlocking { service.electStrategy(change(id, "DYNAMIC", "2020-01-01")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        // A refused change tells the participant nothing.
        assertThat(notifier.sent).isEmpty()
    }

    @Test
    fun `an accepted strategy change notifies the participant of its effective date (#12379)`(): Unit = runBlocking {
        val id = service.createDraft(command()).id
        service.electStrategy(change(id, "DYNAMIC", "2026-11-01"))
        val notice = notifier.sent.single()
        assertThat(notice.kind).isEqualTo(ParticipantNotificationKind.STRATEGY_CHANGE_EFFECTIVE)
        assertThat(notice.partyId).isEqualTo(party)
        assertThat(
            notice.variables,
        ).containsEntry("effectiveFrom", "2026-11-01").containsEntry("strategyCode", "DYNAMIC")
    }

    @Test
    fun `another participant's contract is not found, and staff may read but not change it`(): Unit = runBlocking {
        val id = service.createDraft(command()).id
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
    fun `a retried create with the same key returns the first contract`(): Unit = runBlocking {
        val first = service.createDraft(command(key = "k-1"))
        val second = service.createDraft(command(key = "k-1"))
        assertThat(second.id).isEqualTo(first.id)
        assertThat(repo.rows).hasSize(1)
    }

    @Test
    fun `a retried lifecycle action is a no-op, not a conflict`(): Unit = runBlocking {
        val id = service.createDraft(command()).id
        service.submit(me, id)
        assertThat(service.submit(me, id).status).isEqualTo(ContractStatus.PENDING_ACTIVATION)
    }

    @Test
    fun `amounts above the bound are refused`(): Unit = runBlocking {
        assertThatThrownBy {
            ContributionSchedule(BigDecimal("1000000000.01"), "CZK", ContributionFrequency.MONTHLY)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `every strategy change goes through the suitability gate BEFORE any challenge is spent`(): Unit = runBlocking {
        val id = service.createDraft(command()).id
        suitability.refusal = { com.openbank.pension.application.port.out.StrategyNotPermittedException("no") }
        assertThatThrownBy { runBlocking { service.electStrategy(change(id, "DYNAMIC", "2026-11-01")) } }
            .isInstanceOf(com.openbank.pension.application.port.out.StrategyNotPermittedException::class.java)
        assertThat(sca.spent).isEmpty()
        assertThat(repo.findById(id)!!.strategyHistory).hasSize(1)
        assertThat(notifier.sent).isEmpty()
    }

    @Test
    fun `a strategy change needs a single-use challenge over its exact document`(): Unit = runBlocking {
        val id = service.createDraft(command()).id
        listOf(null, " ", "forged").forEach { challenge ->
            assertThatThrownBy { runBlocking { service.electStrategy(change(id, "DYNAMIC", "2026-11-01", challenge)) } }
                .isInstanceOf(com.openbank.pension.application.usecase.StrategyChangeScaFailedException::class.java)
        }
        assertThat(repo.findById(id)!!.strategyHistory).hasSize(1)
        service.electStrategy(change(id, "DYNAMIC", "2026-11-01", "sca-1"))
        val (_, hash, op) = sca.spent.single()
        assertThat(op).isEqualTo(com.openbank.pension.application.exit.ScaOperation.STRATEGY_CHANGE)
        assertThat(hash).isEqualTo(
            com.openbank.pension.application.port.`in`.StrategyChangeDocument.hash(
                id,
                "DYNAMIC",
                LocalDate.parse("2026-11-01"),
                emptySet(),
            ),
        )
        assertThat(suitability.recorded).hasSize(1)
    }

    @Test
    fun `repeating an applied change is idempotent - nothing is signed, stored or announced twice`(): Unit =
        runBlocking {
            val id = service.createDraft(command()).id
            service.electStrategy(change(id, "DYNAMIC", "2026-11-01", "sca-1"))
            val again = service.electStrategy(change(id, "DYNAMIC", "2026-11-01", "sca-2"))
            assertThat(again.strategyHistory).hasSize(2)
            assertThat(sca.spent).hasSize(1)
            assertThat(notifier.sent).hasSize(1)
        }

    @Test
    fun `a draft's initial strategy goes through the same gate`(): Unit = runBlocking {
        suitability.refusal = { com.openbank.pension.application.port.out.StrategyNotPermittedException("no") }
        assertThatThrownBy { runBlocking { service.createDraft(command()) } }
            .isInstanceOf(com.openbank.pension.application.port.out.StrategyNotPermittedException::class.java)
        assertThat(suitability.asked.single().contractId).isNull()
    }
}
