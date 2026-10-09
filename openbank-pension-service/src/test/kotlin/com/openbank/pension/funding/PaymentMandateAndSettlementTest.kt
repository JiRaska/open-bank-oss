// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.application.exit.InstructionStatus
import com.openbank.pension.application.exit.PaymentInstruction
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.PaymentMandate
import com.openbank.pension.application.usecase.PaymentMandateNotFoundException
import com.openbank.pension.application.usecase.PaymentMandateRepository
import com.openbank.pension.application.usecase.PaymentMandateService
import com.openbank.pension.application.usecase.PaymentMandateStatus
import com.openbank.pension.application.usecase.PayoutSettlementRepository
import com.openbank.pension.application.usecase.PayoutSettlementService
import com.openbank.pension.application.usecase.RailSettlement
import com.openbank.pension.application.usecase.SettlementOutcome
import com.openbank.pension.domain.contribution.MandateKind
import com.openbank.pension.infrastructure.payments.DomesticPaymentSettlementConsumer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** #12378: mandate ownership on cancel, and the settlement write-back's transitions. */
class PaymentMandateAndSettlementTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC)

    private class Mandates : PaymentMandateRepository {
        val rows = linkedMapOf<UUID, PaymentMandate>()

        override suspend fun recordIfAbsent(mandate: PaymentMandate): PaymentMandate =
            rows.values.firstOrNull { it.kind == mandate.kind && it.externalId == mandate.externalId }
                ?: mandate.also { rows[it.id] = it }

        override suspend fun findById(id: UUID) = rows[id]

        override suspend fun markCancelled(id: UUID, at: Instant) {
            rows[id] = rows.getValue(id).copy(status = PaymentMandateStatus.CANCELLED, updatedAt = at)
        }
    }

    private class Port : PaymentMandatePort {
        val cancelled = mutableListOf<String>()
        var refuse = false

        override suspend fun setUp(request: MandateRequest) = "rail-${request.contractId}"

        override suspend fun cancel(kind: MandateKind, externalId: String) {
            check(!refuse) { "rail down" }
            cancelled += externalId
        }
    }

    private val sentToRail = mutableListOf<MandateRequest>()

    /** Owns every IBAN except FOREIGN; the account id is derived, never the caller's. */
    private val accounts = object : com.openbank.pension.application.usecase.ParticipantAccountPort {
        override suspend fun ownAccountId(partyId: UUID, iban: String) =
            if (iban == FOREIGN) null else UUID.nameUUIDFromBytes("$partyId|$iban".toByteArray())
    }

    private fun service(port: Port, mandates: Mandates): PaymentMandateService {
        val contributions = mockk<ContributionService>()
        coEvery { contributions.setUpMandate(any()) } answers {
            sentToRail += firstArg<MandateRequest>()
            "rail-${firstArg<MandateRequest>().contractId}"
        }
        return PaymentMandateService(contributions, port, mandates, accounts, clock)
    }

    @Test
    fun `a debtor account the participant does not own is refused and its account id is never caller-chosen`() {
        val svc = service(Port(), Mandates())
        val contract = UUID.randomUUID()
        assertThatThrownBy { runBlocking { svc.setUp(request(contract).copy(debtorIban = FOREIGN)) } }
            .isInstanceOf(com.openbank.pension.application.usecase.ForeignDebtorAccountException::class.java)
        assertThat(sentToRail).isEmpty()

        val attacker = UUID.randomUUID()
        runBlocking { svc.setUp(request(contract).copy(debtorAccountId = attacker)) }
        assertThat(sentToRail.single().debtorAccountId).isNotEqualTo(attacker)
    }

    private companion object {
        const val FOREIGN = "CZ5508000000001234567899"
    }

    private fun request(contract: UUID) = MandateRequest(
        contract, UUID.randomUUID(), MandateKind.STANDING_ORDER, "CZ6508000000192000145399",
        BigDecimal("1700"), "CZK", "", java.time.LocalDate.parse("2026-11-01"),
    )

    @Test
    fun `a mandate is recorded once and cancelled only through the contract that owns it`() {
        val port = Port()
        val mandates = Mandates()
        val svc = service(port, mandates)
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()

        val created = runBlocking { svc.setUp(request(mine)) }
        val again = runBlocking { svc.setUp(request(mine)) }
        assertThat(again.id).isEqualTo(created.id)
        assertThat(mandates.rows).hasSize(1)

        // Another contract naming this mandate id: 404, and nothing reaches the rail.
        assertThatThrownBy { runBlocking { svc.cancel(other, created.id) } }
            .isInstanceOf(PaymentMandateNotFoundException::class.java)
        assertThat(port.cancelled).isEmpty()

        val cancelled = runBlocking { svc.cancel(mine, created.id) }
        assertThat(cancelled.status).isEqualTo(PaymentMandateStatus.CANCELLED)
        runBlocking { svc.cancel(mine, created.id) } // replay: no second downstream call
        assertThat(port.cancelled).containsExactly(created.externalId)
    }

    @Test
    fun `a refused downstream cancel leaves the mandate ACTIVE`() {
        val port = Port().apply { refuse = true }
        val mandates = Mandates()
        val svc = service(port, mandates)
        val contract = UUID.randomUUID()
        val created = runBlocking { svc.setUp(request(contract)) }

        assertThatThrownBy { runBlocking { svc.cancel(contract, created.id) } }.hasMessageContaining("rail down")
        assertThat(mandates.rows.getValue(created.id).status).isEqualTo(PaymentMandateStatus.ACTIVE)
    }

    private class Instructions(status: InstructionStatus) : PayoutSettlementRepository {
        var row = PaymentInstruction(
            "pension-payout-x-1", UUID.randomUUID(), "PAYOUT", BigDecimal.TEN, "CZK",
            "CZ6508000000192000145399", status, "pay-1",
        )

        override suspend fun findByPaymentRef(paymentRef: String) = row.takeIf { it.paymentRef == paymentRef }

        override suspend fun markSettlement(paymentRef: String, from: Set<InstructionStatus>, to: InstructionStatus): Boolean {
            if (row.paymentRef != paymentRef || row.status !in from) return false
            row = row.copy(status = to)
            return true
        }
    }

    @Test
    fun `settlement moves SENT to SETTLED, a later return to REJECTED, and a replay changes nothing`() {
        val repo = Instructions(InstructionStatus.SENT)
        val counted = mutableListOf<SettlementOutcome>()
        val svc = PayoutSettlementService(repo) { counted += it }

        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED) }).isEqualTo(SettlementOutcome.SETTLED)
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED) }).isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.REJECTED) }).isEqualTo(SettlementOutcome.REJECTED)
        assertThat(repo.row.status).isEqualTo(InstructionStatus.REJECTED)
        // A late SETTLED after a return must not resurrect the payment.
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED) }).isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(repo.row.status).isEqualTo(InstructionStatus.REJECTED)
        assertThat(runBlocking { svc.record("someone-elses-payment", RailSettlement.SETTLED) })
            .isEqualTo(SettlementOutcome.NOT_OURS)
        assertThat(counted).containsExactly(
            SettlementOutcome.SETTLED,
            SettlementOutcome.UNCHANGED,
            SettlementOutcome.REJECTED,
            SettlementOutcome.UNCHANGED,
        )
    }

    @Test
    fun `a PENDING instruction is never marked settled by an event for its reference`() {
        val repo = Instructions(InstructionStatus.PENDING)
        val svc = PayoutSettlementService(repo)
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED) }).isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(repo.row.status).isEqualTo(InstructionStatus.PENDING)
    }

    @Test
    fun `the consumer maps terminal domestic statuses and ignores intermediate ones and poison pills`() {
        val consumer = DomesticPaymentSettlementConsumer(ObjectMapper(), PayoutSettlementService(Instructions(InstructionStatus.SENT)))
        val id = UUID.randomUUID()
        fun body(status: String) = """{"paymentId":"$id","previousStatus":"SENT_TO_CLEARING","newStatus":"$status"}"""

        assertThat(consumer.decode(body("SETTLED"))).isEqualTo(id to RailSettlement.SETTLED)
        listOf("REJECTED", "RETURNED", "CANCELLED").forEach {
            assertThat(consumer.decode(body(it))).isEqualTo(id to RailSettlement.REJECTED)
        }
        listOf("RECEIVED", "VALIDATED", "SENT_TO_CLEARING").forEach { assertThat(consumer.decode(body(it))).isNull() }
        assertThat(consumer.decode("{not json")).isNull()
        assertThat(consumer.decode("""{"paymentId":"nope","newStatus":"SETTLED"}""")).isNull()
    }
}
