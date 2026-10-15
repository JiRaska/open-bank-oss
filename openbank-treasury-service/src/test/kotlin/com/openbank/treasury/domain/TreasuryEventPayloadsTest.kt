// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.DealBooked
import com.openbank.treasury.domain.model.DealConfirmed
import com.openbank.treasury.domain.model.DealMatured
import com.openbank.treasury.domain.model.DealReversed
import com.openbank.treasury.domain.model.DealSettled
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.FxTerms
import com.openbank.treasury.domain.model.LimitOverride
import com.openbank.treasury.domain.model.MarketCurve
import com.openbank.treasury.domain.model.ProductLimit
import com.openbank.treasury.domain.model.ProductLimitApplied
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.SimulatedQuote
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Field-level evidence for the treasury event payloads and the small domain value types they
 * carry. These types are the wire contract other services (audit, ledger, risk) consume, and
 * before this file most of their getters were NO_COVERAGE under pitest — a data-class getter
 * returning a default is indistinguishable from the truth unless the values are distinctive.
 */
class TreasuryEventPayloadsTest {

    private val dealId = UUID.randomUUID()
    private val journalId = UUID.randomUUID()
    private val occurredAt = Instant.parse("2026-10-01T09:00:00Z")
    private val valueDate = LocalDate.parse("2026-10-03")
    private val maturityDate = LocalDate.parse("2026-11-03")

    @Test
    fun `deal booked payload carries the full booking evidence`() {
        val productLimit = ProductLimitApplied.of(
            ProductLimit(ProductType.MM_PLACEMENT, mapOf("CZK" to BigDecimal("5000000")), 90),
            "CZK",
        )

        val event = DealBooked(
            dealId = dealId,
            product = ProductType.FX_SPOT,
            counterpartyId = "cp-1",
            currency = "EUR",
            principal = BigDecimal("1000000"),
            rate = BigDecimal("25.10"),
            valueDate = valueDate,
            maturityDate = maturityDate,
            createdBy = "dealer-1",
            approvedBy = "approver-1",
            occurredAt = occurredAt,
            fxSide = FxSide.BUY,
            counterAmount = BigDecimal("25100000"),
            productLimit = productLimit,
        )

        assertThat(event.dealId).isEqualTo(dealId)
        assertThat(event.product).isEqualTo(ProductType.FX_SPOT)
        assertThat(event.counterpartyId).isEqualTo("cp-1")
        assertThat(event.currency).isEqualTo("EUR")
        assertThat(event.principal).isEqualByComparingTo("1000000")
        assertThat(event.rate).isEqualByComparingTo("25.10")
        assertThat(event.valueDate).isEqualTo(valueDate)
        assertThat(event.maturityDate).isEqualTo(maturityDate)
        assertThat(event.createdBy).isEqualTo("dealer-1")
        assertThat(event.approvedBy).isEqualTo("approver-1")
        assertThat(event.occurredAt).isEqualTo(occurredAt)
        assertThat(event.sourceService).isEqualTo("treasury-service")
        assertThat(event.fxSide).isEqualTo(FxSide.BUY)
        assertThat(event.counterAmount).isEqualByComparingTo("25100000")
        assertThat(event.productLimit).isEqualTo(productLimit)
        assertThat(DealBooked.EVENT_TYPE).isEqualTo("treasury.deal.booked.v1")
    }

    @Test
    fun `deal booked payload defaults fx and limit fields for money-market deals`() {
        val event = DealBooked(
            dealId = dealId,
            product = ProductType.MM_PLACEMENT,
            counterpartyId = "cp-2",
            currency = "CZK",
            principal = BigDecimal("5000000"),
            rate = BigDecimal("3.50"),
            valueDate = valueDate,
            maturityDate = maturityDate,
            createdBy = "dealer-2",
            approvedBy = "approver-2",
            occurredAt = occurredAt,
        )

        assertThat(event.fxSide).isNull()
        assertThat(event.counterAmount).isNull()
        assertThat(event.productLimit).isNull()
    }

    @Test
    fun `deal confirmed payload flags simulated counterparties`() {
        val event = DealConfirmed(
            dealId = dealId,
            product = ProductType.MM_PLACEMENT,
            counterpartyId = "cp-3",
            currency = "CZK",
            principal = BigDecimal("1000000"),
            valueDate = valueDate,
            confirmedBy = "backoffice-1",
            simulated = true,
            occurredAt = occurredAt,
        )

        assertThat(event.dealId).isEqualTo(dealId)
        assertThat(event.product).isEqualTo(ProductType.MM_PLACEMENT)
        assertThat(event.counterpartyId).isEqualTo("cp-3")
        assertThat(event.currency).isEqualTo("CZK")
        assertThat(event.principal).isEqualByComparingTo("1000000")
        assertThat(event.valueDate).isEqualTo(valueDate)
        assertThat(event.confirmedBy).isEqualTo("backoffice-1")
        // ADR-0315 D9: true when the in-process simulated counterparty set confirmed it — a
        // SYNTHETIC confirmation a consumer must be able to tell apart from a human one.
        assertThat(event.simulated).isTrue()
        assertThat(event.occurredAt).isEqualTo(occurredAt)
        assertThat(event.sourceService).isEqualTo("treasury-service")
        assertThat(DealConfirmed.EVENT_TYPE).isEqualTo("treasury.deal.confirmed.v1")
    }

    @Test
    fun `deal settled payload carries the posted journal reference`() {
        val event = DealSettled(
            dealId = dealId,
            product = ProductType.MM_PLACEMENT,
            counterpartyId = "cp-4",
            currency = "CZK",
            principal = BigDecimal("1000000"),
            valueDate = valueDate,
            maturityDate = maturityDate,
            ledgerJournalId = journalId,
            occurredAt = occurredAt,
        )

        assertThat(event.ledgerJournalId).isEqualTo(journalId)
        assertThat(event.counterpartyId).isEqualTo("cp-4")
        assertThat(event.currency).isEqualTo("CZK")
        assertThat(event.principal).isEqualByComparingTo("1000000")
        assertThat(event.valueDate).isEqualTo(valueDate)
        assertThat(event.maturityDate).isEqualTo(maturityDate)
        assertThat(event.fxSide).isNull()
        assertThat(event.counterAmount).isNull()
        assertThat(event.occurredAt).isEqualTo(occurredAt)
        assertThat(event.sourceService).isEqualTo("treasury-service")
        assertThat(DealSettled.EVENT_TYPE).isEqualTo("treasury.deal.settled.v1")
    }

    @Test
    fun `deal matured payload carries interest and the journal reference`() {
        val event = DealMatured(
            dealId = dealId,
            product = ProductType.MM_PLACEMENT,
            counterpartyId = "cp-5",
            currency = "CZK",
            principal = BigDecimal("1000000"),
            interest = BigDecimal("2916.67"),
            maturityDate = maturityDate,
            ledgerJournalId = journalId,
            occurredAt = occurredAt,
        )

        assertThat(event.dealId).isEqualTo(dealId)
        assertThat(event.product).isEqualTo(ProductType.MM_PLACEMENT)
        assertThat(event.counterpartyId).isEqualTo("cp-5")
        assertThat(event.currency).isEqualTo("CZK")
        assertThat(event.principal).isEqualByComparingTo("1000000")
        assertThat(event.interest).isEqualByComparingTo("2916.67")
        assertThat(event.maturityDate).isEqualTo(maturityDate)
        assertThat(event.ledgerJournalId).isEqualTo(journalId)
        assertThat(event.occurredAt).isEqualTo(occurredAt)
        assertThat(event.sourceService).isEqualTo("treasury-service")
        assertThat(DealMatured.EVENT_TYPE).isEqualTo("treasury.deal.matured.v1")
    }

    @Test
    fun `deal reversed payload records who reversed and why with nullable journal`() {
        val withJournal = DealReversed(
            dealId = dealId,
            product = ProductType.MM_PLACEMENT,
            counterpartyId = "cp-6",
            currency = "CZK",
            principal = BigDecimal("1000000"),
            reversedBy = "controller-1",
            reason = "duplicate booking",
            ledgerJournalId = journalId,
            occurredAt = occurredAt,
            fxSide = FxSide.SELL,
            counterAmount = BigDecimal("25100000"),
        )

        assertThat(withJournal.reversedBy).isEqualTo("controller-1")
        assertThat(withJournal.reason).isEqualTo("duplicate booking")
        assertThat(withJournal.ledgerJournalId).isEqualTo(journalId)
        assertThat(withJournal.counterAmount).isEqualByComparingTo("25100000")
        assertThat(withJournal.fxSide).isEqualTo(FxSide.SELL)
        assertThat(withJournal.counterpartyId).isEqualTo("cp-6")
        assertThat(withJournal.currency).isEqualTo("CZK")
        assertThat(withJournal.principal).isEqualByComparingTo("1000000")
        assertThat(withJournal.occurredAt).isEqualTo(occurredAt)
        assertThat(withJournal.sourceService).isEqualTo("treasury-service")
        assertThat(DealReversed.EVENT_TYPE).isEqualTo("treasury.deal.reversed.v1")

        val beforePosting = withJournal.copy(ledgerJournalId = null, fxSide = null, counterAmount = null)
        assertThat(beforePosting.ledgerJournalId).isNull()
        assertThat(beforePosting.fxSide).isNull()
        assertThat(beforePosting.counterAmount).isNull()
    }

    @Test
    fun `product limit applied reflects the evaluated limit`() {
        val limit = ProductLimit(ProductType.FX_SPOT, mapOf("EUR" to BigDecimal("2000000")), 30)

        val applied = ProductLimitApplied.of(limit, "EUR")

        assertThat(applied.decision).isEqualTo(ProductLimitApplied.WITHIN)
        assertThat(applied.maxPrincipal).isEqualByComparingTo("2000000")
        assertThat(applied.maxTenorDays).isEqualTo(30)
    }

    @Test
    fun `counterparty exposes its limits and synthetic taint`() {
        val counterparty = Counterparty(
            id = "cp-7",
            name = "Simulated MM Desk",
            kind = CounterpartyKind.BANK,
            limits = mapOf("CZK" to BigDecimal("10000000")),
            synthetic = true,
        )

        assertThat(counterparty.id).isEqualTo("cp-7")
        assertThat(counterparty.name).isEqualTo("Simulated MM Desk")
        assertThat(counterparty.kind).isEqualTo(CounterpartyKind.BANK)
        assertThat(counterparty.limits).containsEntry("CZK", BigDecimal("10000000"))
        assertThat(counterparty.synthetic).isTrue()
        assertThat(counterparty.limitFor("CZK")).isEqualByComparingTo("10000000")
        assertThat(counterparty.limitFor("EUR")).isEqualByComparingTo("0")
    }

    @Test
    fun `limit override records the approver reason and covered exposure`() {
        val at = Instant.parse("2026-10-01T10:00:00Z")

        val override = LimitOverride(
            by = Actor("head-treasurer-1", ActorType.HUMAN),
            reason = "strategic placement above desk limit",
            at = at,
            coversExposureUpTo = BigDecimal("15000000"),
            limitAtOverride = BigDecimal("10000000"),
        )

        assertThat(override.by).isEqualTo(Actor("head-treasurer-1", ActorType.HUMAN))
        assertThat(override.reason).isEqualTo("strategic placement above desk limit")
        assertThat(override.at).isEqualTo(at)
        assertThat(override.coversExposureUpTo).isEqualByComparingTo("15000000")
        assertThat(override.limitAtOverride).isEqualByComparingTo("10000000")
    }

    @Test
    fun `simulated quote exposes its pricing terms and is always synthetic`() {
        val quote = SimulatedQuote(
            counterpartyId = "cp-8",
            currency = "EUR",
            tenorDays = 30,
            curveSetId = UUID.randomUUID(),
            curveAsOf = LocalDate.parse("2026-10-01"),
            curveIndex = "EURIBOR_3M",
            curveProvenance = "synthetic",
            mid = BigDecimal("2.15"),
            spreadBp = 12,
            bid = BigDecimal("2.09"),
            ask = BigDecimal("2.21"),
        )

        assertThat(quote.counterpartyId).isEqualTo("cp-8")
        assertThat(quote.currency).isEqualTo("EUR")
        assertThat(quote.tenorDays).isEqualTo(30)
        assertThat(quote.curveIndex).isEqualTo("EURIBOR_3M")
        assertThat(quote.curveProvenance).isEqualTo("synthetic")
        assertThat(quote.mid).isEqualByComparingTo("2.15")
        assertThat(quote.spreadBp).isEqualTo(12)
        assertThat(quote.bid).isEqualByComparingTo("2.09")
        assertThat(quote.ask).isEqualByComparingTo("2.21")
        assertThat(quote.synthetic).isTrue()
    }

    @Test
    fun `fx terms expose the booked mid rate and default it to null`() {
        val struck = FxTerms(FxSide.BUY, BigDecimal("25100000"), midRate = BigDecimal("25.10"))
        assertThat(struck.midRate).isEqualByComparingTo("25.10")
        assertThat(struck.side).isEqualTo(FxSide.BUY)
        assertThat(struck.counterAmount).isEqualByComparingTo("25100000")

        val legacy = FxTerms(FxSide.SELL, BigDecimal("10000000"))
        assertThat(legacy.midRate).isNull()
        assertThat(legacy.rateFlag).isNull()
    }

    @Test
    fun `market curve exposes its currency and index`() {
        val curve = MarketCurve("EURIBOR_3M", "EUR", emptyList())

        assertThat(curve.index).isEqualTo("EURIBOR_3M")
        assertThat(curve.currency).isEqualTo("EUR")
        assertThat(curve.pillars).isEmpty()
    }
}
