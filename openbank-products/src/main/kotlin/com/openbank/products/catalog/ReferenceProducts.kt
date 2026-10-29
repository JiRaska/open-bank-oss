// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.catalog

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import com.openbank.libs.product.ir.Cadence
import com.openbank.libs.product.ir.EventKind
import com.openbank.libs.product.ir.IrDayCount
import com.openbank.libs.product.ir.MaturityAction
import com.openbank.libs.product.ir.PostingAttribute
import com.openbank.products.dsl.ProductDefinition
import com.openbank.products.dsl.allOf
import com.openbank.products.dsl.balanceMin
import com.openbank.products.dsl.comparison
import com.openbank.products.dsl.noEvents
import com.openbank.products.dsl.postingComparison
import com.openbank.products.dsl.product

/**
 * The public reference product library (ADR-0352 §11.3): savings, current account, term deposit.
 * Each compiles to `ir/<name>.json` (committed; `ReferenceProductsTest` fails on drift) and has a
 * golden scenario under `product-golden/` that doubles as a public conformance vector.
 *
 * These are reference shapes, not a bank's commercial terms: every rate, fee and threshold here is
 * illustrative, and a deployment publishes its own revisions through the catalog.
 */
object ReferenceProducts {

    /** Daily-accruing, monthly-capitalising savings with a conditional loyalty bonus and a waivable fee. */
    val savingsFixed: ProductDefinition = product("savings-fixed", "SAVINGS-FIXED", "EUR") {
        interest("interest.base") {
            rate = "0.0250"
            dayCount = IrDayCount.ACT_365_FIXED
            accrualCadence = Cadence.DAILY
            capitalisationCadence = Cadence.MONTHLY
            bonus(
                "interest.loyalty-bonus",
                "0.0100",
                allOf(
                    noEvents(EventKind.WITHDRAWAL, days = 30),
                    balanceMin(days = 30, WaiveOperator.GTE, "1000.00"),
                ),
            )
        }
        fee(
            "fee.maintenance",
            feeCode = "MAINT",
            amount = "2.00",
            cadence = Cadence.MONTHLY,
            waiver = comparison(WaiveAttribute.BALANCE, WaiveOperator.GTE, threshold = "5000.00", currency = "EUR"),
        )
        onOpen {
            schedule("open.schedule-accrual", tag = "ACCRUAL", cadence = Cadence.DAILY)
            schedule("open.schedule-capitalisation", tag = "CAPITALISATION", cadence = Cadence.MONTHLY)
            schedule("open.schedule-fee", tag = "FEE_MAINT", cadence = Cadence.MONTHLY)
            notify("open.welcome", "savings-welcome", mapOf("productCode" to "SAVINGS-FIXED"))
        }
        prePosting {
            reject(
                "posting.no-overdraft",
                postingComparison(PostingAttribute.PROJECTED_BALANCE, WaiveOperator.LT, "0.00"),
                reasonCode = "INSUFFICIENT_FUNDS",
            )
        }
        scheduled {
            accrueInterest("scheduled.accrue", trigger = "ACCRUAL")
            capitaliseInterest("scheduled.capitalise", trigger = "CAPITALISATION")
            chargeFee("scheduled.fee", trigger = "FEE_MAINT", feeRuleId = "fee.maintenance")
        }
        onParameterChange {
            notify("reprice.notify", "rate-changed", mapOf("productCode" to "SAVINGS-FIXED"))
        }
    }

    /** Non-interest-bearing current account with an arranged overdraft and a balance-waivable fee. */
    val currentAccount: ProductDefinition = product("current-account", "CURRENT-ACCOUNT", "EUR") {
        fee(
            "fee.maintenance",
            feeCode = "MAINT",
            amount = "4.90",
            cadence = Cadence.MONTHLY,
            waiver = comparison(WaiveAttribute.BALANCE, WaiveOperator.GTE, threshold = "1000.00", currency = "EUR"),
        )
        onOpen {
            schedule("open.schedule-fee", tag = "FEE_MAINT", cadence = Cadence.MONTHLY)
            notify("open.welcome", "current-account-welcome", mapOf("productCode" to "CURRENT-ACCOUNT"))
        }
        prePosting {
            // Arranged overdraft of 500.00: a posting may take the balance to -500.00, not below.
            reject(
                "posting.overdraft-limit",
                postingComparison(PostingAttribute.PROJECTED_BALANCE, WaiveOperator.LT, "-500.00"),
                reasonCode = "OVERDRAFT_LIMIT_EXCEEDED",
            )
        }
        scheduled {
            chargeFee("scheduled.fee", trigger = "FEE_MAINT", feeRuleId = "fee.maintenance")
        }
        onClose {
            notify("close.notify", "account-closed", mapOf("productCode" to "CURRENT-ACCOUNT"))
        }
    }

    /** Twelve-month tiered term deposit, capitalising at maturity, with a rate-based early-withdrawal penalty. */
    val termDeposit: ProductDefinition = product("term-deposit", "TERM-DEPOSIT-12M", "EUR") {
        interest("interest.base") {
            tier(from = "0.00", to = "10000.00", rate = "0.0200")
            tier(from = "10000.00", to = null, rate = "0.0300")
            dayCount = IrDayCount.ACT_360
            accrualCadence = Cadence.DAILY
            capitalisationCadence = Cadence.AT_MATURITY
        }
        termDeposit("term.main", termMonths = 12, maturityAction = MaturityAction.CAPITALISE) {
            penaltyRate("term.early-withdrawal", "0.0100")
        }
        onOpen {
            schedule("open.schedule-accrual", tag = "ACCRUAL", cadence = Cadence.DAILY)
            schedule("open.schedule-maturity", tag = "MATURITY", cadence = Cadence.AT_MATURITY)
        }
        prePosting {
            // Checked before the penalty, so a refused withdrawal is never charged one.
            reject(
                "posting.no-overdraft",
                postingComparison(PostingAttribute.PROJECTED_BALANCE, WaiveOperator.LT, "0.00"),
                reasonCode = "INSUFFICIENT_FUNDS",
            )
            earlyWithdrawalPenalty("posting.early-withdrawal")
        }
        scheduled {
            accrueInterest("scheduled.accrue", trigger = "ACCRUAL")
            mature("scheduled.mature", trigger = "MATURITY")
        }
    }

    val all: List<ProductDefinition> = listOf(savingsFixed, currentAccount, termDeposit)

    fun byName(name: String): ProductDefinition? = all.firstOrNull { it.name == name }
}
