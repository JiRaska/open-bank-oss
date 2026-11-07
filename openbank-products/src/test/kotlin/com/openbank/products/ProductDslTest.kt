// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.MaturityAction
import com.openbank.libs.product.ir.PostingAttribute
import com.openbank.libs.product.ir.ProductIrValidationException
import com.openbank.libs.product.ir.RateType
import com.openbank.products.dsl.anyOf
import com.openbank.products.dsl.comparison
import com.openbank.products.dsl.countEvents
import com.openbank.products.dsl.not
import com.openbank.products.dsl.postingComparison
import com.openbank.products.dsl.product
import com.openbank.products.dsl.sumEvents
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The DSL writes the document verbatim and validates nothing itself: the parser is the one validator. */
class ProductDslTest {

    @Test
    fun `tiers make a TIERED rate and a forfeited-interest penalty lands as declared`() {
        val p = product("t", "T-1", "CZK") {
            interest("interest.base") {
                tier("0.00", "1000.00", "0.0100")
                tier("1000.00", null, "0.0200")
                capitalisationCadence = Cadence.AT_MATURITY
            }
            termDeposit("term.main", 6, MaturityAction.PAY_OUT) { forfeitedInterest("term.forfeit", "0.5000") }
            onOpen {
                schedule("open.accrual", "ACCRUAL", Cadence.DAILY)
                schedule("open.maturity", "MATURITY", Cadence.AT_MATURITY)
            }
            scheduled {
                accrueInterest("s.accrue", "ACCRUAL")
                mature("s.mature", "MATURITY")
            }
            prePosting { earlyWithdrawalPenalty("p.penalty") }
        }
        val rev = p.compile()
        assertThat(rev.interest!!.rateType).isEqualTo(RateType.TIERED)
        assertThat(rev.interest!!.tiers).hasSize(2)
        assertThat(rev.termDeposit!!.earlyWithdrawal!!.fraction!!.toPlainString()).isEqualTo("0.5000")
        assertThat(p.sources).containsKeys("interest.base", "term.main", "term.forfeit", "open.accrual", "p.penalty")
    }

    @Test
    fun `predicate helpers produce the closed-language trees the parser reads`() {
        val pred = anyOf(
            not(countEvents(EventKind.CARD_PAYMENT, 30, WaiveOperator.GTE, 3)),
            sumEvents(EventKind.DEPOSIT, 90, WaiveOperator.GT, "1000.00"),
            comparison(WaiveAttribute.SEGMENT, WaiveOperator.EQ, textValue = "PREMIUM"),
        )
        assertThat(pred.tree["type"]).isEqualTo("or")

        @Suppress("UNCHECKED_CAST")
        val operands = pred.tree["operands"] as List<Map<String, Any?>>
        assertThat(operands.map { it["type"] }).containsExactly("not", "sumEvents", "comparison")
        assertThat(operands[2]).doesNotContainKey("threshold")
        val p = product("w", "W-1", "EUR") {
            fee("fee.card", "CARD", "1.00", Cadence.MONTHLY, waiver = pred)
            onOpen { schedule("open.fee", "FEE", Cadence.MONTHLY) }
            scheduled { chargeFee("s.fee", "FEE", "fee.card") }
            prePosting {
                reject(
                    "p.reject",
                    postingComparison(PostingAttribute.POSTING_AMOUNT, WaiveOperator.GT, "10000.00"),
                    "LIMIT",
                )
            }
        }
        assertThat(p.compile().fees.single().waiver).isNotNull()
    }

    @Test
    fun `an invalid document is refused by the parser with the path of every problem`() {
        val p = product("bad", "BAD-1", "EUR") {
            interest("interest.base") { rate = "0.0250" }
            scheduled { chargeFee("s.fee", "FEE", feeRuleId = "fee.does-not-exist") }
        }
        assertThatThrownBy { p.compile() }
            .isInstanceOf(ProductIrValidationException::class.java)
            .hasMessageContaining("fee.does-not-exist")
    }
}
