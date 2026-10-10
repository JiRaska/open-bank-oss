// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.PensionContractService
import com.openbank.pension.domain.exit.InstallmentStatus
import com.openbank.pension.domain.exit.PayoutQuote
import com.openbank.pension.domain.exit.PayoutRequest
import com.openbank.pension.domain.exit.PayoutStatus
import com.openbank.pension.domain.exit.TaxBase
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.infrastructure.exit.rest.ExitExceptionMappers
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.testsupport.ContractFixtures
import com.openbank.pension.testsupport.ProviderFixtures
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The production race behind the flaky `PensionIntegrationRoutesIT` account-change test, made
 * DETERMINISTIC: an instalment activity saves the payout between the participant's read and the
 * account-change write. The change must re-read and apply on the fresh row (keeping the paid
 * instalment), the money must still go only to the signed account, and when the race is lost
 * every time the client gets a clear retryable 409 — never a lost update.
 */
class PayoutAccountChangeRaceTest {

    private val clock = Clock.fixed(Instant.parse("2030-03-10T10:00:00Z"), ZoneOffset.UTC)
    private val party = UUID.randomUUID()
    private val signedIban = "CZ6508000000192000145399"
    private val newIban = "CZ5508000000001234567899"

    @Test
    fun `an instalment saved concurrently is kept and the account change still applies on the fresh row`(): Unit =
        runBlocking {
            val w = world()
            w.payouts.racesLeft = 1
            val changed = w.service.changePayoutAccount(command(w))
            assertThat(w.payouts.racesRun).isEqualTo(1)
            assertThat(changed.pendingPayoutIban).isEqualTo(newIban)
            assertThat(changed.pendingPayoutIbanNotified).isTrue()
            // The concurrent writer's instalment survived the retry: no lost update.
            assertThat(changed.schedule!!.installments.first().status).isEqualTo(InstallmentStatus.PAID)
            // The signed account is untouched and still pays everything due before the hold ends.
            assertThat(changed.payoutIban).isEqualTo(signedIban)
            val firstDue = changed.schedule!!.installments.first { it.status == InstallmentStatus.PENDING }.dueDate
            assertThat(changed.accountFor(LocalDate.now(clock), LocalDate.now(clock))).isEqualTo(signedIban)
            assertThat(changed.accountFor(firstDue, LocalDate.now(clock))).isEqualTo(signedIban)
        }

    @Test
    fun `a race lost every time is a retryable 409 with Retry-After and nobody is notified`(): Unit = runBlocking {
        val w = world()
        w.payouts.racesLeft = Int.MAX_VALUE
        assertThatThrownBy { runBlocking { w.service.changePayoutAccount(command(w)) } }
            .isInstanceOf(ExitConcurrentUpdateException::class.java)
        assertThat(w.notified).isEmpty()
        val stored = requireNotNull(w.payouts.findById(w.payout.id))
        assertThat(stored.pendingPayoutIban).isNull()
        assertThat(stored.payoutIban).isEqualTo(signedIban)

        val response = ExitExceptionMappers().concurrent(ExitConcurrentUpdateException("lost"))
        assertThat(response.status).isEqualTo(409)
        assertThat(response.getHeaderString("Retry-After")).isEqualTo("1")
    }

    @Test
    fun `a lost race is retryable by the workflow - it is not one of the do-not-retry domain refusals`() {
        // ExitActivityStub marks IllegalStateException/IllegalArgumentException do-not-retry; an
        // instalment activity that lost to an account change must re-read, not fail the payout.
        assertThat(ExitConcurrentUpdateException("x")).isNotInstanceOf(IllegalStateException::class.java)
        assertThat(ExitConcurrentUpdateException("x")).isNotInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------------------------------------

    private fun command(w: World) = ChangePayoutAccountCommand(
        Caller.customer(party),
        w.contract.id,
        w.payout.id,
        "sca-${UUID.randomUUID()}",
        newIban,
    )

    private class World(
        val service: PayoutService,
        val contract: PensionContract,
        val payout: PayoutRequest,
        val payouts: RacingPayouts,
        val notified: MutableList<String>,
    )

    private fun world(): World = runBlocking {
        val packs = JurisdictionPackLoader.loadRegistry()
        val contracts = Contracts()
        val useCase =
            PensionContractService(
                contracts,
                packs,
                clock,
                com.openbank.pension.infrastructure.notification.RecordingParticipantNotifier(),
                com.openbank.pension.testsupport.RecordingSuitability(),
                com.openbank.pension.testsupport.RecordingSca(),
                ProviderFixtures.boundary,
            )
        val id = ContractFixtures.activeContract(
            useCase,
            contracts,
            party,
            birthDate = LocalDate.parse("1960-02-02"),
            startDate = LocalDate.parse("2010-01-01"),
        )
        val contract = requireNotNull(contracts.findById(id))
        val quote = PayoutQuote(
            PayoutForm.PHASED_WITHDRAWAL, BigDecimal("12000.00"), BigDecimal("12000.00"), TaxBase.NONE,
            BigDecimal("0.00"), BigDecimal("0.00"), BigDecimal("12000.00"), "CZK", 1, months = 12,
        )
        val payouts = RacingPayouts()
        val confirmed = PayoutRequest.quote(id, party, quote, 30, clock.instant())
            .confirm(signedIban, "sca-confirm", "idem", LocalDate.now(clock), clock.instant())
        val payout = payouts.save(confirmed)
        val notified = CopyOnWriteArrayList<String>()
        val gateways = ExitGateways(
            fund = mockk(relaxed = true),
            incentives = mockk(relaxed = true),
            tax = mockk(relaxed = true),
            payments = mockk(relaxed = true),
            annuities = mockk(relaxed = true),
            accounts = mockk<OwnAccountVerificationPort>().also {
                coEvery { it.isOwnVerifiedAccount(any(), any()) } returns
                    true
            },
            sca = mockk<ScaVerificationPort>().also { coEvery { it.verify(any(), any(), any(), any()) } returns true },
            beneficiaryKyc = mockk(relaxed = true),
            notifications = object : ParticipantNotificationPort {
                override suspend fun payoutAccountChanged(
                    partyId: UUID,
                    contractId: UUID,
                    payoutId: UUID,
                    accountLast4: String,
                    effectiveFrom: LocalDate,
                ) {
                    notified += "$payoutId|$accountLast4"
                }
            },
            notifier = com.openbank.pension.infrastructure.notification.RecordingParticipantNotifier(),
        )
        val stores = ExitStores(contracts, mockk(relaxed = true), payouts, mockk(relaxed = true), mockk(relaxed = true))
        val service = PayoutService(useCase, ExitContext(stores, gateways, packs, clock), mockk(relaxed = true))
        World(service, contract, payout, payouts, notified)
    }

    /**
     * Optimistic-lock repository that simulates the instalment activity: on an armed save it first
     * commits "instalment 1 paid" (a newer version), then refuses the stale write exactly as the
     * real upsert does.
     */
    private inner class RacingPayouts : PayoutRequestRepository {
        val rows = ConcurrentHashMap<UUID, PayoutRequest>()
        var racesLeft = 0
        var racesRun = 0

        override suspend fun save(request: PayoutRequest): PayoutRequest {
            val stored = rows[request.id]
            if (stored != null && racesLeft > 0 && request.pendingPayoutIban != null) {
                racesLeft--
                racesRun++
                val running = stored.startInstallments(clock.instant())
                val first = requireNotNull(running.schedule).installments.first {
                    it.status == InstallmentStatus.PENDING
                }
                rows[request.id] = running.markInstallmentPaid(first.seq, "PAY-${first.seq}", clock.instant())
                    .copy(version = stored.version + 1)
            }
            val current = rows[request.id]
            if (current != null && current.version != request.version) {
                throw ExitConcurrentUpdateException("payout ${request.id} changed concurrently")
            }
            return request.copy(version = (current?.version ?: -1) + 1).also { rows[it.id] = it }
        }

        override suspend fun findById(id: UUID) = rows[id]
        override suspend fun findByContract(contractId: UUID) = rows.values.filter { it.contractId == contractId }
        override suspend fun findInPayment() = rows.values.filter { it.status == PayoutStatus.IN_PAYMENT }
        override suspend fun list(status: PayoutStatus?, contractId: UUID?, limit: Int) = rows.values.toList()
    }

    private class Contracts : PensionContractRepository {
        val rows = ConcurrentHashMap<UUID, PensionContract>()
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
}
