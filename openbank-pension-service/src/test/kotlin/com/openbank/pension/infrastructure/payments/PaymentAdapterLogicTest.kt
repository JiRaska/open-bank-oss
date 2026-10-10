// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.payments

import com.openbank.pension.application.exit.PaymentOrder
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.domain.contribution.MandateKind
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.infrastructure.identity.AccountOwnership
import com.openbank.pension.infrastructure.identity.OwnershipVerificationDto
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

/**
 * The real payment adapters' decisions against fake rails (#12378): what is sent, what is
 * refused before anything is sent, and that a retry re-sends the SAME instruction.
 */
class PaymentAdapterLogicTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC)
    private val collection = CollectionAccount("CZ5508000000001234567899", "OpenBank Pension", "CZ00ZZZ12345678")
    private val account = UUID.randomUUID()

    private class FakeRails : MandateRails {
        val standingOrders = mutableListOf<CreateStandingOrderDto>()
        val sdd = mutableListOf<RegisterSddMandateDto>()
        val cancelled = mutableListOf<String>()

        override suspend fun createStandingOrder(request: CreateStandingOrderDto): StandingOrderDto {
            standingOrders += request
            return StandingOrderDto(UUID.nameUUIDFromBytes(request.idempotencyKey.toByteArray()))
        }

        override suspend fun cancelStandingOrder(id: UUID) {
            cancelled += "SO:$id"
        }

        override suspend fun registerSddMandate(request: RegisterSddMandateDto): SddMandateDto {
            sdd += request
            return SddMandateDto(UUID.nameUUIDFromBytes(request.umr.toByteArray()))
        }

        override suspend fun cancelSddMandate(id: UUID) {
            cancelled += "DD:$id"
        }
    }

    private fun request(
        kind: MandateKind = MandateKind.STANDING_ORDER,
        currency: String = "CZK",
        accountId: UUID? = account,
        debtorName: String? = "Jana Novakova",
    ) = MandateRequest(
        contractId = UUID.fromString("00000000-0000-4000-8000-00000000c001"),
        participantPartyId = UUID.fromString("00000000-0000-4000-8000-00000000a001"),
        kind = kind,
        debtorIban = "cz65 0800 0000 1920 0014 5399",
        amount = BigDecimal("1700.00"),
        currency = currency,
        reference = "4711",
        firstCollection = LocalDate.parse("2026-11-01"),
        frequency = ContributionFrequency.QUARTERLY,
        debtorAccountId = accountId,
        debtorName = debtorName,
    )

    @Test
    fun `a standing order pays the collection account quoting the contract reference at the schedule frequency`() {
        val rails = FakeRails()
        val logic = MandateOrders(rails, { collection }, clock)

        val first = runBlocking { logic.setUp(request()) }
        val retry = runBlocking { logic.setUp(request()) }

        assertThat(rails.standingOrders).hasSize(2)
        val sent = rails.standingOrders.first()
        assertThat(sent.creditorIban).isEqualTo(collection.iban)
        assertThat(sent.remittanceInfo).isEqualTo("4711")
        assertThat(sent.frequency).isEqualTo("QUARTERLY")
        assertThat(sent.paymentType).isEqualTo("DOMESTIC")
        assertThat(sent.amountMinorUnits).isEqualTo(170_000)
        assertThat(sent.debitAccountId).isEqualTo(account)
        assertThat(sent.debtorIban).isEqualTo("CZ6508000000192000145399")
        // A retry sends the same key, so the provider returns the order it already created.
        assertThat(rails.standingOrders[1].idempotencyKey).isEqualTo(sent.idempotencyKey)
        assertThat(retry).isEqualTo(first)
    }

    @Test
    fun `a euro standing order executes as a SEPA credit transfer`() {
        val rails = FakeRails()
        runBlocking { MandateOrders(rails, { collection }, clock).setUp(request(currency = "EUR")) }
        assertThat(rails.standingOrders.single().paymentType).isEqualTo("SEPA_CREDIT")
    }

    @Test
    fun `a direct debit registers the debtor mandate with a UMR derived from the contract reference`() {
        val rails = FakeRails()
        runBlocking { MandateOrders(rails, { collection }, clock).setUp(request(kind = MandateKind.DIRECT_DEBIT)) }
        val mandate = rails.sdd.single()
        assertThat(mandate.umr).isEqualTo("PENSION-4711")
        assertThat(mandate.creditorIdentifier).isEqualTo("CZ00ZZZ12345678")
        assertThat(mandate.sequenceType).isEqualTo("RCUR")
        assertThat(mandate.accountId).isEqualTo(account)
        // sdd-service verifies THIS party owns the debtor IBAN behind accountId (#12419).
        assertThat(mandate.partyId).isEqualTo(UUID.fromString("00000000-0000-4000-8000-00000000a001"))
        assertThat(mandate.signatureDate).isEqualTo(LocalDate.parse("2026-10-09"))
    }

    @Test
    fun `every missing input is refused before any rail is called`() {
        val rails = FakeRails()
        val unconfigured = MandateOrders(rails, { null }, clock)
        assertThatThrownBy { runBlocking { unconfigured.setUp(request()) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("collection account")

        val logic = MandateOrders(rails, { collection }, clock)
        assertThatThrownBy { runBlocking { logic.setUp(request(accountId = null)) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("debtorAccountId")
        assertThatThrownBy { runBlocking { logic.setUp(request(kind = MandateKind.DIRECT_DEBIT, debtorName = null)) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("debtorName")
        val noCid = MandateOrders(rails, { collection.copy(creditorIdentifier = null) }, clock)
        assertThatThrownBy { runBlocking { noCid.setUp(request(kind = MandateKind.DIRECT_DEBIT)) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("creditor identifier")

        assertThat(rails.standingOrders).isEmpty()
        assertThat(rails.sdd).isEmpty()
    }

    @Test
    fun `cancel goes to the rail of the mandate's kind`() {
        val rails = FakeRails()
        val logic = MandateOrders(rails, { collection }, clock)
        val so = UUID.randomUUID()
        val dd = UUID.randomUUID()
        runBlocking {
            logic.cancel(MandateKind.STANDING_ORDER, so.toString())
            logic.cancel(MandateKind.DIRECT_DEBIT, dd.toString())
        }
        assertThat(rails.cancelled).containsExactly("SO:$so", "DD:$dd")
        assertThatThrownBy { runBlocking { logic.cancel(MandateKind.STANDING_ORDER, "stub-mandate-1") } }
            .isInstanceOf(PaymentRailRefusedException::class.java)
    }

    // ---- payouts ----

    private val payoutAccount = PayoutAccount(UUID.randomUUID(), "2000145399", "0800", "OpenBank Pension payouts")

    private fun order(iban: String = "CZ6508000000192000145399", currency: String = "CZK") = PaymentOrder(
        idempotencyKey = "pension-payout-${UUID.fromString("00000000-0000-4000-8000-0000000000aa")}-installment-1",
        contractId = UUID.randomUUID(),
        creditorName = "Jana Novakova",
        creditorIban = iban,
        amount = BigDecimal("12500.00"),
        currency = currency,
        reference = "PENSION PAYOUT x",
    )

    @Test
    fun `a payout is a CZK domestic payment keyed by the payout step's own idempotency key`() {
        val sent = mutableListOf<Pair<String, CreateDomesticPaymentDto>>()
        val logic = PayoutOrders(
            { key, body ->
                sent += key to body
                DomesticPaymentDto(UUID.nameUUIDFromBytes(key.toByteArray()))
            },
            { payoutAccount },
        )

        val ref = runBlocking { logic.pay(order()) }
        val retry = runBlocking { logic.pay(order()) }

        val (key, body) = sent.first()
        assertThat(key).isEqualTo(order().idempotencyKey)
        assertThat(sent[1].first).isEqualTo(key)
        assertThat(retry).isEqualTo(ref)
        assertThat(body.creditorAccountNumber).isEqualTo("19-2000145399")
        assertThat(body.creditorBankCode).isEqualTo("0800")
        assertThat(body.debtorAccountId).isEqualTo(payoutAccount.accountId)
        assertThat(body.currency).isEqualTo("CZK")
        assertThat(body.amount).isEqualByComparingTo("12500.00")
    }

    @Test
    fun `a payout the domestic rail cannot address is refused before it is sent`() {
        val sent = mutableListOf<String>()
        val logic = PayoutOrders({ key, _ ->
            sent += key
            DomesticPaymentDto(UUID.randomUUID())
        }, { payoutAccount })

        assertThatThrownBy { runBlocking { logic.pay(order(currency = "EUR")) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("CZK")
        assertThatThrownBy { runBlocking { logic.pay(order(iban = "DE89370400440532013000")) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("Czech IBAN")
        // Last digit changed: the base fails the national mod-11 check.
        assertThatThrownBy { runBlocking { logic.pay(order(iban = "CZ6508000000192000145398")) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("mod-11")
        assertThatThrownBy { runBlocking { PayoutOrders({ _, _ -> error("unreachable") }, { null }).pay(order()) } }
            .isInstanceOf(PaymentRailRefusedException::class.java).hasMessageContaining("payout account")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a Czech IBAN without a prefix maps to the bare base number`() {
        assertThat(PayoutOrders.czechAccount("CZ00 0800 0000 0020 0014 5399")).isEqualTo("2000145399" to "0800")
    }

    @Test
    fun `the debtor account id comes only from an owned and active ownership verdict`() {
        val id = UUID.randomUUID()
        assertThat(
            AccountOwnership.ownAccountId(OwnershipVerificationDto(owned = true, active = true, id)),
        ).isEqualTo(id)
        assertThat(AccountOwnership.ownAccountId(OwnershipVerificationDto(owned = true, active = false, id))).isNull()
        assertThat(AccountOwnership.ownAccountId(OwnershipVerificationDto(owned = false, active = true, id))).isNull()
        assertThat(AccountOwnership.ownAccountId(null)).isNull()
    }
}
