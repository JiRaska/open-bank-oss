// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.maintenance

import com.openbank.pension.application.exit.DeathClaimRepository
import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.maintenance.ChangeBeneficiariesCommand
import com.openbank.pension.application.maintenance.ChangeNotAuthorisedException
import com.openbank.pension.application.maintenance.ChangeScheduleCommand
import com.openbank.pension.application.maintenance.ContractChangeStore
import com.openbank.pension.application.maintenance.ContractMaintenanceService
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.PensionContractService
import com.openbank.pension.domain.exit.Claimant
import com.openbank.pension.domain.exit.DeathClaim
import com.openbank.pension.domain.exit.DeathClaimStatus
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationHistory
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationVersion
import com.openbank.pension.domain.maintenance.ContributionScheduleHistory
import com.openbank.pension.domain.maintenance.ScheduleChangeRequest
import com.openbank.pension.domain.maintenance.ScheduleVersionStatus
import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ContributionLimits
import com.openbank.pension.domain.pack.JurisdictionPack
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

class ContractMaintenanceTest {

    private val now: Instant = Instant.parse("2026-10-09T10:00:00Z")
    private val today: LocalDate = LocalDate.parse("2026-10-09")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val registry = JurisdictionPackLoader.loadRegistry()
    private val party = UUID.randomUUID()

    private fun contract(
        status: ContractStatus = ContractStatus.ACTIVE,
        amount: String = "1700",
        beneficiaries: List<Beneficiary> = listOf(b("Jane", "60"), b("John", "40")),
    ) = PensionContract(
        id = UUID.randomUUID(), participantPartyId = party, productLine = ProductLine.DPS, jurisdiction = "CZ",
        packVersion = 1, providerEntityId = UUID.randomUUID(), providerType = ProviderType.PENSION_COMPANY,
        participantBirthDate = LocalDate.parse("1985-01-01"), status = status,
        schedule = ContributionSchedule(BigDecimal(amount), "CZK", ContributionFrequency.MONTHLY),
        strategyHistory = listOf(
            com.openbank.pension.domain.model.StrategyElection("BALANCED", today, now),
        ),
        beneficiaries = beneficiaries,
        startDate = if (status == ContractStatus.DRAFT || status == ContractStatus.PENDING_ACTIVATION) null else today,
        createdAt = now, updatedAt = now,
    )

    private fun b(name: String, share: String, partyId: UUID? = null) = Beneficiary(name, partyId, BigDecimal(share))

    private fun pack(): JurisdictionPack = registry.pinnedFor(contract())

    private fun req(
        amount: String,
        freq: ContributionFrequency = ContributionFrequency.MONTHLY,
        day: Int = 15,
        ack: Boolean = false,
        start: LocalDate? = null,
    ) = ScheduleChangeRequest(BigDecimal(amount), freq, day, start, ack)

    // ---- schedule: domain ------------------------------------------------------------------

    @Test
    fun `a change takes effect from the next collection cycle, never the one in progress`() {
        val c = contract()
        val h = ContributionScheduleHistory.empty(c.id)
        assertThat(h.plan(c, pack(), req("2000", day = 15), today).effectiveFrom).isEqualTo("2026-10-15")
        // day 10 is within the 3-day notice window -> next month
        assertThat(h.plan(c, pack(), req("2000", day = 10), today).effectiveFrom).isEqualTo("2026-11-10")
        assertThat(h.plan(c, pack(), req("2000", day = 5, start = LocalDate.parse("2027-01-01")), today).effectiveFrom)
            .isEqualTo("2027-01-05")
    }

    @Test
    fun `the pack minimum is judged as a monthly equivalent`() {
        val c = contract()
        val h = ContributionScheduleHistory.empty(c.id)
        assertThatThrownBy {
            h.plan(c, pack(), req("99", ack = true), today)
        }.hasMessageContaining("below the pack minimum")
        assertThatThrownBy { h.plan(c, pack(), req("299", ContributionFrequency.QUARTERLY, ack = true), today) }
            .hasMessageContaining("below the pack minimum")
        assertThat(h.plan(c, pack(), req("300", ContributionFrequency.QUARTERLY, ack = true), today).amount)
            .isEqualByComparingTo("300")
    }

    @Test
    fun `pack maximum, allowed frequencies and collection day are enforced`() {
        val c = contract()
        val h = ContributionScheduleHistory.empty(c.id)
        val strict = pack().copy(
            contributionLimits = ContributionLimits(
                minMonthly = BigDecimal("100"),
                maxMonthly = BigDecimal("5000"),
                allowedFrequencies = setOf(ContributionFrequency.MONTHLY),
                maxDayOfMonth = 20,
            ),
        )
        assertThatThrownBy { h.plan(c, strict, req("5001"), today) }.hasMessageContaining("exceeds the pack maximum")
        assertThatThrownBy { h.plan(c, strict, req("6000", ContributionFrequency.QUARTERLY), today) }
            .hasMessageContaining("not allowed")
        assertThatThrownBy { h.plan(c, strict, req("2000", day = 21), today) }.hasMessageContaining("dayOfMonth")
        assertThatThrownBy {
            h.plan(c, strict, req("2000", start = today.minusDays(1)), today)
        }.hasMessageContaining("past")
    }

    @Test
    fun `a pack without contribution limits refuses every change`() {
        val c = contract()
        assertThatThrownBy {
            ContributionScheduleHistory.empty(c.id).plan(c, pack().copy(contributionLimits = null), req("2000"), today)
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("no contribution limits")
    }

    @Test
    fun `lowering the state incentive needs an explicit acknowledgement`() {
        val c = contract(amount = "1700") // 20 % capped at 340 / month
        val h = ContributionScheduleHistory.empty(c.id)
        assertThatThrownBy {
            h.plan(c, pack(), req("400"), today)
        }.hasMessageContaining("acknowledgeIncentiveReduction")
        val plan = h.plan(c, pack(), req("400", ack = true), today)
        assertThat(plan.impact.annualBefore).isEqualByComparingTo("4080")
        assertThat(plan.impact.annualAfter).isEqualByComparingTo("0")
        assertThat(plan.impact.warnings).anyMatch { it.startsWith("state-contribution") }
        // raising it needs no acknowledgement
        assertThat(h.plan(c, pack(), req("2500"), today).impact.reduces).isFalse()
    }

    @Test
    fun `only a live contract changes its schedule`() {
        listOf(ContractStatus.DRAFT, ContractStatus.TERMINATING, ContractStatus.CLOSED).forEach { s ->
            val c = contract(status = s)
            assertThatThrownBy { ContributionScheduleHistory.empty(c.id).plan(c, pack(), req("2000"), today) }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `a newer change supersedes a pending one and history keeps both`() {
        val c = contract()
        var h = ContributionScheduleHistory.empty(c.id)
        h = h.record(h.plan(c, pack(), req("2000"), today), "sca-1", "k1", today, now)
        h = h.record(h.plan(c, pack(), req("2200"), today), "sca-2", "k2", today, now)
        assertThat(h.versions.map { it.status })
            .containsExactly(ScheduleVersionStatus.SUPERSEDED, ScheduleVersionStatus.SCHEDULED)
        assertThat(h.pendingAfter(today)!!.amount).isEqualByComparingTo("2200")
        assertThat(h.inForceOn(today)).isNull()
        assertThat(h.inForceOn(LocalDate.parse("2026-10-15"))!!.seq).isEqualTo(2)
    }

    @Test
    fun `a stale preview cannot be recorded into a newer history`() {
        val c = contract()
        val h0 = ContributionScheduleHistory.empty(c.id)
        val stale = h0.plan(c, pack(), req("2000"), today)
        val h1 = h0.record(h0.plan(c, pack(), req("2100"), today), "s", "k1", today, now)
        assertThatThrownBy { h1.record(stale, "s2", "k2", today, now) }.hasMessageContaining("preview again")
        assertThat(stale.documentSha256).isNotEqualTo(h1.plan(c, pack(), req("2000"), today).documentSha256)
    }

    // ---- beneficiaries: domain -------------------------------------------------------------

    @Test
    fun `shares must total exactly 100 with at most two decimals`() {
        val c = contract()
        val h = BeneficiaryDesignationHistory.empty(c.id)
        assertThatThrownBy {
            h.plan(c, listOf(b("A", "60"), b("B", "40.01")), false)
        }.hasMessageContaining("exactly 100")
        assertThatThrownBy { h.plan(c, listOf(b("A", "33.333"), b("B", "33.333"), b("C", "33.334")), false) }
            .hasMessageContaining("decimals")
        assertThat(h.plan(c, listOf(b("A", "33.33"), b("B", "33.33"), b("C", "33.34")), false).beneficiaries).hasSize(3)
        assertThat(h.plan(c, emptyList(), false).beneficiaries).isEmpty() // remove all -> estate rule
    }

    @Test
    fun `nobody twice and never the participant`() {
        val c = contract()
        val h = BeneficiaryDesignationHistory.empty(c.id)
        assertThatThrownBy {
            h.plan(c, listOf(b("Ann", "50"), b("ann", "50")), false)
        }.hasMessageContaining("only once")
        assertThatThrownBy { h.plan(c, listOf(b("Me", "100", party)), false) }.hasMessageContaining("own beneficiary")
    }

    @Test
    fun `reorder is a change, an identical list is not`() {
        val c = contract()
        val h = BeneficiaryDesignationHistory.empty(c.id)
        assertThat(
            h.plan(c, listOf(b("John", "40"), b("Jane", "60")), false).beneficiaries.first().name,
        ).isEqualTo("John")
        assertThatThrownBy { h.plan(c, c.beneficiaries, false) }.hasMessageContaining("already in force")
    }

    @Test
    fun `a death claim freezes the designation, at preview and at record`() {
        val c = contract()
        val h = BeneficiaryDesignationHistory.empty(c.id)
        assertThatThrownBy { h.plan(c, listOf(b("X", "100")), true) }.hasMessageContaining("death claim")
        val plan = h.plan(c, listOf(b("X", "100")), false)
        assertThatThrownBy { h.record(plan, true, "s", "k", now) }.hasMessageContaining("death claim")
    }

    // ---- service ---------------------------------------------------------------------------

    private class Repo : PensionContractRepository {
        val rows = mutableMapOf<UUID, PensionContract>()
        override suspend fun save(contract: PensionContract) = contract.also { rows[it.id] = it }
        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun findByIdempotencyKey(participantPartyId: UUID, idempotencyKey: String) = null
        override suspend fun findByParticipant(participantPartyId: UUID, limit: Int) = rows.values.toList()
        override suspend fun findByStatus(status: ContractStatus?, limit: Int) = rows.values.toList()
    }

    private class Store(val repo: Repo) : ContractChangeStore {
        val schedules = mutableMapOf<UUID, ContributionScheduleHistory>()
        val designations = mutableMapOf<UUID, BeneficiaryDesignationHistory>()
        override suspend fun scheduleHistory(contractId: UUID) =
            schedules[contractId] ?: ContributionScheduleHistory.empty(contractId)
        override suspend fun saveSchedule(history: ContributionScheduleHistory) =
            history.also { schedules[it.contractId] = it }
        override suspend fun beneficiaryHistory(contractId: UUID) =
            designations[contractId] ?: BeneficiaryDesignationHistory.empty(contractId)
        override suspend fun saveBeneficiaries(contract: PensionContract, version: BeneficiaryDesignationVersion) {
            repo.rows[contract.id] = contract
            val h = beneficiaryHistory(contract.id)
            designations[contract.id] = h.copy(versions = h.versions + version)
        }
    }

    private class Sca(var accept: Boolean = true) : ScaVerificationPort {
        val operations = java.util.concurrent.CopyOnWriteArrayList<ScaOperation>()
        val signed = mutableListOf<String>()
        override suspend fun verify(
            partyId: UUID,
            challengeId: String,
            documentSha256: String,
            operation: ScaOperation,
        ): Boolean {
            operations += operation
            signed += documentSha256
            return accept
        }
    }

    private class Deaths : DeathClaimRepository {
        val byContract = mutableMapOf<UUID, DeathClaim>()
        override suspend fun save(claim: DeathClaim) = claim.also { byContract[it.contractId] = it }
        override suspend fun findById(id: UUID) = byContract.values.firstOrNull { it.id == id }
        override suspend fun findByContract(contractId: UUID) = byContract[contractId]
        override suspend fun list(status: DeathClaimStatus?, limit: Int) = byContract.values.toList()
    }

    private val repo = Repo()
    private val store = Store(repo)
    private val sca = Sca()
    private val deaths = Deaths()
    private val service = ContractMaintenanceService(
        PensionContractService(
            repo,
            registry,
            clock,
            RecordingParticipantNotifier(),
            com.openbank.pension.testsupport.RecordingSuitability(),
            com.openbank.pension.testsupport.RecordingSca(),
        ),
        store,
        deaths,
        sca,
        registry,
        clock,
    )
    private val me = Caller.customer(party)

    private fun stored(): PensionContract = contract().also { repo.rows[it.id] = it }

    @Test
    fun `the SCA challenge is verified over exactly the previewed document`(): Unit = runBlocking {
        val c = stored()
        val preview = service.previewSchedule(me, c.id, req("2000"))
        val v = service.changeSchedule(ChangeScheduleCommand(me, c.id, req("2000"), "sca", "k1"))
        assertThat(sca.signed).containsExactly(preview.documentSha256)
        assertThat(sca.operations).containsExactly(ScaOperation.SCHEDULE_CHANGE)
        assertThat(v.documentSha256).isEqualTo(preview.documentSha256)
    }

    @Test
    fun `a refused challenge stores nothing`(): Unit = runBlocking {
        val c = stored()
        sca.accept = false
        assertThatThrownBy {
            runBlocking { service.changeSchedule(ChangeScheduleCommand(me, c.id, req("2000"), "sca", "k1")) }
        }.isInstanceOf(ChangeNotAuthorisedException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.changeBeneficiaries(ChangeBeneficiariesCommand(me, c.id, listOf(b("X", "100")), "sca", "k2"))
            }
        }.isInstanceOf(ChangeNotAuthorisedException::class.java)
        assertThat(store.schedules).isEmpty()
        assertThat(store.designations).isEmpty()
        assertThat(repo.rows.getValue(c.id).beneficiaries).hasSize(2)
    }

    @Test
    fun `a retry with the same key returns the first version and signs nothing again`(): Unit = runBlocking {
        val c = stored()
        val cmd = ChangeBeneficiariesCommand(me, c.id, listOf(b("X", "100")), "sca", "k1")
        val first = service.changeBeneficiaries(cmd)
        val again = service.changeBeneficiaries(cmd)
        assertThat(again).isEqualTo(first)
        assertThat(sca.signed).hasSize(1)
        assertThat(sca.operations).containsExactly(ScaOperation.BENEFICIARY_CHANGE)
        assertThat(repo.rows.getValue(c.id).beneficiaries.single().name).isEqualTo("X")
    }

    @Test
    fun `staff cannot change and another party sees not found`(): Unit = runBlocking {
        val c = stored()
        assertThatThrownBy { runBlocking { service.previewSchedule(Caller.STAFF, c.id, req("2000")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking { service.previewSchedule(Caller.customer(UUID.randomUUID()), c.id, req("2000")) }
        }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `a registered death claim refuses a beneficiary change before any challenge is spent`(): Unit = runBlocking {
        val c = stored()
        deaths.save(
            DeathClaim.notify(
                c.id,
                today,
                "ev",
                "op",
                listOf(Claimant(UUID.randomUUID(), "Jane", null, BigDecimal("100"), false)),
                "dk",
                now,
            ),
        )
        assertThatThrownBy {
            runBlocking {
                service.changeBeneficiaries(ChangeBeneficiariesCommand(me, c.id, listOf(b("X", "100")), "sca", "k1"))
            }
        }.hasMessageContaining("death claim")
        assertThat(sca.signed).isEmpty()
    }
}
