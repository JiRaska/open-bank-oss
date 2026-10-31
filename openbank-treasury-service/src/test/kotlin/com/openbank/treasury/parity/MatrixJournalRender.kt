// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.parity

import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.MatrixPostings
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.PostingMatrix
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.Ifrs9Category
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate

/** Every journal of every matrix fixture's life, rendered with ids replaced by stable ordinals. */
object MatrixJournalRender {
    private val SECURITIES = setOf(ProductType.BOND, ProductType.TREASURY_BILL, ProductType.BILL_OF_EXCHANGE)

    fun render(code: (PostingLine) -> String): List<String> = ParityFixtures.fixtures().map { it.second }
        .filter { !ProductType.valueOf(it["product"].asText()).legacy }
        .flatMap { fixture ->
            val product = ProductType.valueOf(fixture["product"].asText())
            val categories = if (product in SECURITIES) Ifrs9Category.entries else listOf(Ifrs9Category.AMORTISED_COST)
            categories.flatMap { category ->
                val deal = ParityFixtures.deal(fixture["deal"])
                val flows = runBlocking {
                    ParityFixtures.scheduleService(fixture, deal).generate(deal, ParityFixtures.fixings(fixture)).flows
                }
                val ordinal = flows.sortedWith(
                    compareBy({
                        it.legIndex
                    }, { it.periodIndex }, { it.kind }, { it.direction }),
                )
                    .mapIndexed { i, f -> f.flowId.toString() to "F$i" }.toMap()
                val journals = mutableListOf<JournalSpec>()
                MatrixPostings.trade(deal)?.let(journals::add)
                MatrixPostings.settlement(deal, flows, category)?.let(journals::add)
                val last = MatrixPostings.lastPaymentDate(deal, flows)
                journals += MatrixPostings.run(deal, flows, category, null, emptySet(), last).map { it.spec }
                if (!deal.product.settlesOnOneDay) {
                    MatrixPostings.maturity(deal, flows, category, MatrixPostings.amortisedThrough(deal, flows, last))
                        ?.let(journals::add)
                }
                if (PostingMatrix.hasEvent(product, PostingEvent.REVALUATION)) {
                    listOf(BigDecimal("1500.00"), BigDecimal("-200.00")).forEach { change ->
                        MatrixPostings.revaluation(deal, change, deal.currency, LocalDate.of(2026, 12, 31), category)
                            ?.let(journals::add)
                    }
                }
                journals.map { j ->
                    var key = j.idempotencyKey.replace(deal.id.toString(), "D")
                    ordinal.forEach { (id, o) -> key = key.replace(id, o) }
                    "${fixture["id"].asText()} [$category] $key :: " +
                        j.lines.joinToString(" | ") {
                            "${it.side} ${code(it)} ${it.amount.toPlainString()} ${it.currency}"
                        }
                }
            }
        }
}
