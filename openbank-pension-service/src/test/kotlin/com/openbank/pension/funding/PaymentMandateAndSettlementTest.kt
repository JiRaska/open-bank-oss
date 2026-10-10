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

    /** Records what each challenge was spent over; accepts only the challenge id "sca-ok". */
    private class Sca : com.openbank.pension.application.exit.ScaVerificationPort {
        val spent = mutableListOf<Pair<String, com.openbank.pension.application.exit.ScaOperation>>()
        override suspend fun verify(
            partyId: UUID,
            challengeId: String,
            documentSha256: String,
            operation: com.openbank.pension.application.exit.ScaOperation,
        ): Boolean {
            spent += documentSha256 to operation
            return challengeId == "sca-ok"
        }
    }

    private val sca = Sca()

    private fun service(port: Port, mandates: Mandates): PaymentMandateService {
        val contributions = mockk<ContributionService>()
        coEvery { contributions.setUpMandate(any()) } answers {
            sentToRail += firstArg<MandateRequest>()
            "rail-${firstArg<MandateRequest>().contractId}"
        }
        return PaymentMandateService(contributions, port, mandates, accounts, sca, clock)
    }

    @Test
    fun `a debtor account the participant does not own is refused and its account id is never caller-chosen`() {
        val svc = service(Port(), Mandates())
        val contract = UUID.randomUUID()
        assertThatThrownBy { runBlocking { svc.setUp(request(contract).copy(debtorIban = FOREIGN), "sca-ok") } }
            .isInstanceOf(com.openbank.pension.application.usecase.ForeignDebtorAccountException::class.java)
        assertThat(sentToRail).isEmpty()

        val attacker = UUID.randomUUID()
        runBlocking { svc.setUp(request(contract).copy(debtorAccountId = attacker), "sca-ok") }
        assertThat(sentToRail.single().debtorAccountId).isNotEqualTo(attacker)
    }

    @Test
    fun `a mandate is set up only under SCA over its exact document, before any account lookup or rail call`() {
        val svc = service(Port(), Mandates())
        val contract = UUID.randomUUID()
        val req = request(contract)
        listOf(null, " ", "sca-wrong").forEach { challenge ->
            assertThatThrownBy { runBlocking { svc.setUp(req, challenge) } }
                .isInstanceOf(com.openbank.pension.application.usecase.MandateSetupScaFailedException::class.java)
        }
        assertThat(sentToRail).isEmpty()

        runBlocking { svc.setUp(req, "sca-ok") }
        val (hash, op) = sca.spent.last()
        assertThat(op).isEqualTo(com.openbank.pension.application.exit.ScaOperation.MANDATE_SETUP)
        assertThat(hash).isEqualTo(com.openbank.pension.application.usecase.PaymentMandateSetup.documentHash(req))
        // The document binds IBAN, contract and amount: a challenge for one never covers another.
        val doc = com.openbank.pension.application.usecase.PaymentMandateSetup::documentHash
        assertThat(doc(req.copy(debtorIban = FOREIGN))).isNotEqualTo(hash)
        assertThat(doc(req.copy(contractId = UUID.randomUUID()))).isNotEqualTo(hash)
        assertThat(doc(req.copy(amount = BigDecimal("1701")))).isNotEqualTo(hash)
        assertThat(doc(req.copy(debtorIban = "cz65 0800 0000 1920 0014 5399"))).isEqualTo(hash)
    }

    @Test
    fun `cancel and employer enrolment are SCA-bound in the use case, and replays spend nothing`() {
        val mandates = Mandates()
        val svc = service(Port(), mandates)
        val contract = UUID.randomUUID()
        val created = runBlocking { svc.setUp(request(contract), "sca-ok") }
        listOf(null, "sca-wrong").forEach { challenge ->
            assertThatThrownBy { runBlocking { svc.cancel(contract, PARTY, created.id, challenge) } }
                .isInstanceOf(
                    com.openbank.pension.application.usecase.MandateCancellationScaFailedException::class.java,
                )
        }
        runBlocking { svc.cancel(contract, PARTY, created.id, "sca-ok") }
        val spent = sca.spent.size
        runBlocking { svc.cancel(contract, PARTY, created.id, null) } // already cancelled: no-op, no SCA
        assertThat(sca.spent).hasSize(spent)
        assertThat(sca.spent.last().second)
            .isEqualTo(com.openbank.pension.application.exit.ScaOperation.MANDATE_CANCELLATION)
    }

    private companion object {
        val PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000b001")
        const val FOREIGN = "CZ5508000000001234567899"
    }

    private fun request(contract: UUID) = MandateRequest(
        contract,
        UUID.randomUUID(),
        MandateKind.STANDING_ORDER,
        "CZ6508000000192000145399",
        BigDecimal("1700"),
        "CZK",
        "",
        java.time.LocalDate.parse("2026-11-01"),
    )

    @Test
    fun `a mandate is recorded once and cancelled only through the contract that owns it`() {
        val port = Port()
        val mandates = Mandates()
        val svc = service(port, mandates)
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()

        val created = runBlocking { svc.setUp(request(mine), "sca-ok") }
        val again = runBlocking { svc.setUp(request(mine), "sca-ok") }
        assertThat(again.id).isEqualTo(created.id)
        assertThat(mandates.rows).hasSize(1)

        // Another contract naming this mandate id: 404, and nothing reaches the rail.
        assertThatThrownBy { runBlocking { svc.cancel(other, PARTY, created.id, "sca-ok") } }
            .isInstanceOf(PaymentMandateNotFoundException::class.java)
        assertThat(port.cancelled).isEmpty()

        val cancelled = runBlocking { svc.cancel(mine, PARTY, created.id, "sca-ok") }
        assertThat(cancelled.status).isEqualTo(PaymentMandateStatus.CANCELLED)
        runBlocking { svc.cancel(mine, PARTY, created.id, "sca-ok") } // replay: no second downstream call
        assertThat(port.cancelled).containsExactly(created.externalId)
    }

    @Test
    fun `a refused downstream cancel leaves the mandate ACTIVE`() {
        val port = Port().apply { refuse = true }
        val mandates = Mandates()
        val svc = service(port, mandates)
        val contract = UUID.randomUUID()
        val created = runBlocking { svc.setUp(request(contract), "sca-ok") }

        assertThatThrownBy {
            runBlocking { svc.cancel(contract, PARTY, created.id, "sca-ok") }
        }.hasMessageContaining("rail down")
        assertThat(mandates.rows.getValue(created.id).status).isEqualTo(PaymentMandateStatus.ACTIVE)
    }

    private class Instructions(status: InstructionStatus) : PayoutSettlementRepository {
        var row = PaymentInstruction(
            "pension-payout-x-1",
            UUID.randomUUID(),
            "PAYOUT",
            BigDecimal.TEN,
            "CZK",
            "CZ6508000000192000145399",
            status,
            "pay-1",
        )
        var settledAt: Instant? = null

        override suspend fun findByPaymentRef(paymentRef: String) = row.takeIf { it.paymentRef == paymentRef }

        override suspend fun markSettlement(
            paymentRef: String,
            from: Set<InstructionStatus>,
            to: InstructionStatus,
            occurredAt: Instant,
            settledAt: Instant?,
        ): Boolean {
            if (row.paymentRef != paymentRef || row.status !in from) return false
            val staleReturn = this.settledAt?.isAfter(occurredAt) == true
            if (row.status == InstructionStatus.SETTLED && to == InstructionStatus.REJECTED && staleReturn) {
                return false
            }
            row = row.copy(status = to)
            this.settledAt = settledAt.takeIf { to == InstructionStatus.SETTLED }
            return true
        }
    }

    @Test
    fun `settlement moves SENT to SETTLED, a later return to REJECTED, and a replay changes nothing`() {
        val repo = Instructions(InstructionStatus.SENT)
        val counted = mutableListOf<SettlementOutcome>()
        val svc = PayoutSettlementService(repo) { counted += it }
        val railTime = Instant.parse("2009-03-31T23:30:00Z")

        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED, railTime, railTime) })
            .isEqualTo(SettlementOutcome.SETTLED)
        assertThat(repo.settledAt).isEqualTo(railTime)
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.REJECTED, railTime.minusSeconds(60)) })
            .isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(repo.settledAt).isEqualTo(railTime)
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED, railTime.plusSeconds(60), railTime) })
            .isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(repo.settledAt).isEqualTo(railTime)
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.REJECTED, railTime.plusSeconds(120)) })
            .isEqualTo(SettlementOutcome.REJECTED)
        assertThat(repo.row.status).isEqualTo(InstructionStatus.REJECTED)
        assertThat(repo.settledAt).isNull()
        // A late SETTLED after a return must not resurrect the payment.
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED, railTime.plusSeconds(180), railTime) })
            .isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(repo.row.status).isEqualTo(InstructionStatus.REJECTED)
        assertThat(runBlocking { svc.record("someone-elses-payment", RailSettlement.SETTLED, railTime, railTime) })
            .isEqualTo(SettlementOutcome.NOT_OURS)
        assertThat(counted).containsExactly(
            SettlementOutcome.SETTLED,
            SettlementOutcome.UNCHANGED,
            SettlementOutcome.UNCHANGED,
            SettlementOutcome.REJECTED,
            SettlementOutcome.UNCHANGED,
        )
    }

    @Test
    fun `a PENDING instruction is never marked settled by an event for its reference`() {
        val repo = Instructions(InstructionStatus.PENDING)
        val svc = PayoutSettlementService(repo)
        val settledAt = Instant.parse("2009-03-31T23:30:00Z")
        assertThat(runBlocking { svc.record("pay-1", RailSettlement.SETTLED, settledAt, settledAt) })
            .isEqualTo(SettlementOutcome.UNCHANGED)
        assertThat(repo.row.status).isEqualTo(InstructionStatus.PENDING)
    }

    @Test
    fun `the consumer maps terminal domestic statuses and ignores intermediate ones and poison pills`() {
        val consumer =
            DomesticPaymentSettlementConsumer(
                ObjectMapper(),
                PayoutSettlementService(Instructions(InstructionStatus.SENT)),
            )
        val id = UUID.randomUUID()
        val railTime = Instant.parse("2009-03-31T23:30:00Z")
        fun body(status: String) =
            """{"paymentId":"$id","previousStatus":"SENT_TO_CLEARING","newStatus":"$status","occurredAt":"$railTime","settledAt":"$railTime"}"""

        assertThat(consumer.decode(body("SETTLED"))).isEqualTo(
            DomesticPaymentSettlementConsumer.DecodedSettlement(id, RailSettlement.SETTLED, railTime, railTime),
        )
        listOf("REJECTED", "RETURNED", "CANCELLED").forEach {
            assertThat(consumer.decode(body(it))).isEqualTo(
                DomesticPaymentSettlementConsumer.DecodedSettlement(id, RailSettlement.REJECTED, railTime, null),
            )
        }
        listOf("RECEIVED", "VALIDATED", "SENT_TO_CLEARING").forEach { assertThat(consumer.decode(body(it))).isNull() }
        assertThat(consumer.decode("{not json")).isNull()
        assertThat(consumer.decode("""{"paymentId":"nope","newStatus":"SETTLED"}""")).isNull()
        assertThat(consumer.decode("""{"paymentId":"$id","newStatus":"SETTLED"}""")).isNull()
        assertThat(consumer.decode("""{"paymentId":"$id","newStatus":"SETTLED","occurredAt":"$railTime"}"""))
            .isEqualTo(DomesticPaymentSettlementConsumer.DecodedSettlement(id, RailSettlement.SETTLED, railTime, null))
    }
}
