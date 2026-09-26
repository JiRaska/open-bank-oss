// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain

import com.openbank.risk.domain.Fixtures.AS_OF
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.cashflow.CashFlowKind
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.model.InputHash
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.TieOut
import com.openbank.risk.domain.model.TieOutStatus
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.model.TreasuryInstrumentMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** ADR-0314 D4 / ADR-0315 D6: the bank's money-market deals as contract-level instruments. */
class TreasuryInstrumentTest {

    private fun deal(
        product: String = TreasuryDeal.CNB_DEPOSIT_FACILITY,
        principal: String = "10000000.00",
        currency: String = "CZK",
        state: String = TreasuryDeal.SETTLED,
        valueDaysBefore: Long = 2,
        maturityDaysAfter: Long = 1,
        rate: String? = "2.50",
    ) = TreasuryDeal(
        dealId = UUID.nameUUIDFromBytes("$product$principal$state".toByteArray()),
        product = product,
        counterpartyId = if (product == TreasuryDeal.CNB_DEPOSIT_FACILITY) "CNB" else "SIMBK-A",
        currency = currency,
        principal = BigDecimal(principal),
        rate = rate?.let(::BigDecimal),
        valueDate = AS_OF.minusDays(valueDaysBefore),
        maturityDate = AS_OF.plusDays(maturityDaysAfter),
        state = state,
    )

    private fun inputs(vararg tb: com.openbank.risk.domain.model.TrialBalanceLine) =
        LedgerInputs(AS_OF, tb.toList(), emptyList())

    @Test
    fun `a ČNB deposit maps to a MONEY_MARKET_DEAL on 1510 at plus principal, a borrowing to minus`() {
        val cnb = TreasuryInstrumentMapper.toInstrument(deal())
        assertThat(cnb.kind).isEqualTo(InstrumentKind.MONEY_MARKET_DEAL)
        assertThat(cnb.glAccountCode).isEqualTo("1510")
        assertThat(cnb.outstanding).isEqualByComparingTo("10000000.00")
        val borrowing = TreasuryInstrumentMapper.toInstrument(
            deal(product = TreasuryDeal.MM_BORROWING, currency = "EUR"),
        )
        assertThat(borrowing.glAccountCode).isEqualTo("2301")
        assertThat(borrowing.outstanding).isEqualByComparingTo("-10000000.00")
    }

    @Test
    fun `on the book - settled from its value date, matured only before its maturity, never booked or reversed`() {
        assertThat(deal().onBookAt(AS_OF)).isTrue()
        assertThat(deal(valueDaysBefore = -1).onBookAt(AS_OF)).isFalse()
        assertThat(deal(state = TreasuryDeal.MATURED).onBookAt(AS_OF)).isTrue()
        assertThat(deal(state = TreasuryDeal.MATURED, maturityDaysAfter = 0).onBookAt(AS_OF)).isFalse()
        assertThat(deal(state = TreasuryDeal.BOOKED).onBookAt(AS_OF)).isFalse()
        assertThat(deal(state = TreasuryDeal.REVERSED).onBookAt(AS_OF)).isFalse()
    }

    @Test
    fun `once deals are read, the principal accounts are contract-level and the deal ties out against the GL`() {
        val ledger =
            inputs(tb("1510", "ASSET", "CZK", "10000000.00", "0"), tb("1001", "ASSET", "CZK", "0", "10000000.00"))
        val positions = PositionBuilder.build(
            ledger,
            treasuryDeals = listOf(TreasuryInstrumentMapper.toInstrument(deal())),
        )
        assertThat(
            positions.filter {
                it.glAccountCode == "1510"
            }.map { it.kind },
        ).containsExactly(PositionKind.TREASURY_DEAL)
        assertThat(TieOut.check(ledger.trialBalance, positions).status).isEqualTo(TieOutStatus.TIED_OUT)
    }

    @Test
    fun `a deal missing from the book cannot tie out by the GL figure standing in for it`() {
        val ledger =
            inputs(tb("1510", "ASSET", "CZK", "10000000.00", "0"), tb("1001", "ASSET", "CZK", "0", "10000000.00"))
        val positions = PositionBuilder.build(ledger, treasuryDeals = emptyList())
        assertThat(positions.none { it.glAccountCode == "1510" }).isTrue()
        assertThat(TieOut.check(ledger.trialBalance, positions).status).isEqualTo(TieOutStatus.UNTIED)
    }

    @Test
    fun `with the treasury read off, the principal accounts stay GL-level exactly as before`() {
        val ledger =
            inputs(tb("1510", "ASSET", "CZK", "10000000.00", "0"), tb("1001", "ASSET", "CZK", "0", "10000000.00"))
        val positions = PositionBuilder.build(ledger)
        assertThat(positions.single { it.glAccountCode == "1510" }.kind).isEqualTo(PositionKind.GL_ACCOUNT)
    }

    @Test
    fun `a deal repays principal and ACT-360 interest at maturity, signed from the bank's side`() {
        val placement = TreasuryInstrumentMapper.toInstrument(
            deal(
                product = TreasuryDeal.MM_PLACEMENT,
                principal = "100000.00",
                rate = "4.25",
                valueDaysBefore = 0,
                maturityDaysAfter = 30,
            ),
        )
        val flows = SnapshotCashFlowProjection.moneyMarketFlows(placement)
        assertThat(flows.map { it.kind to it.amount.toPlainString() })
            .containsExactly(CashFlowKind.PRINCIPAL to "100000.00", CashFlowKind.INTEREST to "354.17")
        val borrowing = TreasuryInstrumentMapper.toInstrument(
            deal(
                product = TreasuryDeal.MM_BORROWING,
                principal = "100000.00",
                rate = "4.25",
                valueDaysBefore = 0,
                maturityDaysAfter = 30,
            ),
        )
        assertThat(SnapshotCashFlowProjection.moneyMarketFlows(borrowing).map { it.amount.signum() }).containsOnly(-1)
    }

    @Test
    fun `a deal whose rate never arrived projects its principal and no guessed coupon`() {
        val flows = SnapshotCashFlowProjection.moneyMarketFlows(
            TreasuryInstrumentMapper.toInstrument(deal(rate = null)),
        )
        assertThat(flows.map { it.kind }).containsExactly(CashFlowKind.PRINCIPAL)
    }

    @Test
    fun `the input hash moves with the deals and is unchanged when the read is off`() {
        val ledger = inputs(tb("1510", "ASSET", "CZK", "10000000.00", "0"))
        val off = InputHash.of(ledger)
        assertThat(InputHash.of(ledger, null, null)).isEqualTo(off)
        val one = InputHash.of(ledger, null, listOf(deal()))
        assertThat(one).isNotEqualTo(off)
        assertThat(InputHash.of(ledger, null, listOf(deal(state = TreasuryDeal.MATURED)))).isNotEqualTo(one)
    }
}
