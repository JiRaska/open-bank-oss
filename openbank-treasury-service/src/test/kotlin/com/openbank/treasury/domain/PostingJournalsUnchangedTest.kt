// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.MONDAY
import com.openbank.treasury.domain.DealFixtures.fxSpot
import com.openbank.treasury.domain.DealFixtures.placement
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.ProductType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ADR-0341 D3 differential proof: for every existing deal shape and every posting event, the
 * journal built by the new class-based rules and resolved through the ledger map is the same
 * journal -- same GL accounts, sides, amounts, currencies, order and idempotency key -- as the one
 * the verbatim legacy rules ([LegacyPostingRules]) produce. A leg the map cannot resolve fails the
 * test; nothing falls back to a default.
 */
class PostingJournalsUnchangedTest {

    private val deals: Map<String, Deal> = buildMap {
        for (ccy in listOf("CZK", "EUR")) {
            put("placement-$ccy", placement(currency = ccy))
            put("borrowing-$ccy", placement(currency = ccy, product = ProductType.MM_BORROWING))
        }
        put("cnb-deposit", placement(product = ProductType.CNB_DEPOSIT_FACILITY, maturity = MONDAY.plusDays(1)))
        put("cnb-lombard", placement(product = ProductType.CNB_LOMBARD, maturity = MONDAY.plusDays(1)))
        put("fx-buy", fxSpot(side = FxSide.BUY))
        put("fx-sell", fxSpot(side = FxSide.SELL))
    }

    private fun render(spec: JournalSpec?) = spec?.let {
        listOf(it.event.name, it.idempotencyKey) + it.lines.map { l ->
            "${l.side} ${l.glCode()} ${l.amount.toPlainString()} ${l.currency}"
        }
    }

    private fun render(legacy: LegacyJournal?, deal: Deal) = legacy?.let {
        val key = "treasury:${deal.id}:${it.event.key}" + (it.suffix?.let { s -> ":$s" } ?: "")
        listOf(it.event.name, key) + it.lines.map { l ->
            "${l.side} ${l.glCode} ${l.amount.toPlainString()} ${l.currency}"
        }
    }

    @Test
    fun `settlement is unchanged for every product and currency`() {
        deals.forEach { (name, deal) ->
            assertThat(render(PostingRules.settlement(deal)))
                .describedAs(name)
                .isEqualTo(render(LegacyPostingRules.settlement(deal), deal))
        }
    }

    @Test
    fun `every daily accrual is unchanged`() {
        deals.filterValues { it.product != ProductType.FX_SPOT }.forEach { (name, deal) ->
            for (day in 0..deal.days + 1) {
                val date: LocalDate = deal.valueDate.plusDays(day)
                assertThat(render(PostingRules.accrual(deal, date)))
                    .describedAs("$name accrual $date")
                    .isEqualTo(render(LegacyPostingRules.accrual(deal, date), deal))
            }
        }
    }

    @Test
    fun `maturity is unchanged whether nothing, part or all of the interest was accrued`() {
        deals.filterValues { it.product != ProductType.FX_SPOT }.forEach { (name, deal) ->
            listOf(BigDecimal.ZERO, deal.interest.divide(BigDecimal("3"), 2, java.math.RoundingMode.HALF_UP), deal.interest)
                .forEach { accrued ->
                    assertThat(render(PostingRules.maturity(deal, accrued)))
                        .describedAs("$name maturity accrued=$accrued")
                        .isEqualTo(render(LegacyPostingRules.maturity(deal, accrued), deal))
                }
        }
    }

    @Test
    fun `reversal is unchanged, and a reversal from BOOKED still posts nothing`() {
        deals.forEach { (name, deal) ->
            val accruals = if (deal.product == ProductType.FX_SPOT) {
                listOf(BigDecimal.ZERO)
            } else {
                listOf(BigDecimal.ZERO, deal.interest.divide(BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP))
            }
            accruals.forEach { accrued ->
                assertThat(render(PostingRules.reversal(deal, DealState.SETTLED, accrued)))
                    .describedAs("$name reversal accrued=$accrued")
                    .isEqualTo(render(LegacyPostingRules.reversal(deal, DealState.SETTLED, accrued), deal))
            }
            assertThat(PostingRules.reversal(deal, DealState.BOOKED)).isNull()
            assertThat(LegacyPostingRules.reversal(deal, DealState.BOOKED)).isNull()
        }
    }

    @Test
    fun `the matrix is not vacuous`() {
        val journals = deals.values.map { PostingRules.settlement(it) }
        assertThat(journals).hasSize(deals.size)
        assertThat(journals.flatMap { it.lines }.map { it.glCode() }.toSet())
            .contains("1001", "1002", "1500", "1501", "1510", "2300", "2301", "2320", "1990", "1991")
    }
}
