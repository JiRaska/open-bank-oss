// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A priced position held by the fund at its depositary. */
data class PricedPosition(val instrumentId: String, val quantity: BigDecimal, val price: BigDecimal) {
    init {
        require(instrumentId.isNotBlank()) { "instrumentId must not be blank" }
        require(quantity.signum() >= 0) { "quantity must not be negative" }
        require(price.signum() >= 0) { "price must not be negative" }
    }

    val marketValue: BigDecimal get() = quantity.multiply(price)
}

data class NavInput(
    val positions: List<PricedPosition>,
    val cash: BigDecimal,
    val otherLiabilities: BigDecimal,
    val unitsOutstanding: BigDecimal,
    val managementFeeRate: BigDecimal,
    /** Days since the previous valuation; the fee accrues for exactly this many days. */
    val accrualDays: Int,
    val launchNavPerUnit: BigDecimal,
) {
    init {
        require(otherLiabilities.signum() >= 0) { "otherLiabilities must not be negative" }
        require(unitsOutstanding.signum() >= 0) { "unitsOutstanding must not be negative" }
        require(accrualDays >= 0) { "accrualDays must not be negative" }
    }
}

data class NavFigures(
    val grossAssets: BigDecimal,
    val accruedManagementFee: BigDecimal,
    val otherLiabilities: BigDecimal,
    val netAssets: BigDecimal,
    val unitsOutstanding: BigDecimal,
    val navPerUnit: BigDecimal,
)

/**
 * NAV = (positions at market + cash - accrued management fee - other liabilities) / units outstanding.
 *
 * The fee accrues on GROSS assets for the days since the previous valuation (Actual/365), and is
 * rounded to money before it is subtracted, so the figure the fund books and the figure the NAV
 * reflects are the same number. While no units are outstanding the NAV is the fund's launch price.
 */
object NavCalculator {
    fun calculate(input: NavInput): NavFigures {
        val positions = input.positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue }
        val gross = Precision.money(positions + input.cash)
        val fee = Precision.money(
            gross.multiply(input.managementFeeRate).multiply(BigDecimal(input.accrualDays))
                .divide(BigDecimal(Precision.DAY_COUNT_BASIS), Precision.INTERMEDIATE_SCALE, Precision.MONEY_ROUNDING),
        )
        val liabilities = Precision.money(input.otherLiabilities)
        val net = gross - fee - liabilities
        check(net.signum() >= 0) { "net assets would be negative ($net); the inputs are inconsistent" }
        val navPerUnit = if (input.unitsOutstanding.signum() == 0) {
            Precision.nav(input.launchNavPerUnit)
        } else {
            net.divide(input.unitsOutstanding, Precision.NAV_SCALE, Precision.NAV_ROUNDING)
        }
        check(navPerUnit.signum() > 0) { "NAV per unit must be positive" }
        return NavFigures(gross, fee, liabilities, net, Precision.units(input.unitsOutstanding), navPerUnit)
    }
}

enum class NavStatus { CALCULATED, PUBLISHED, REJECTED, SUPERSEDED }

/**
 * A calculated NAV awaiting, or carrying, its four-eyes publication. A correction is a NEW record
 * pointing at the published one it replaces via [correctsNavId]; the original is never edited in
 * place except to be marked SUPERSEDED.
 */
data class NavRecord(
    val id: UUID,
    val fundId: UUID,
    val valuationDate: LocalDate,
    val figures: NavFigures,
    val status: NavStatus,
    val calculatedBy: String,
    val calculatedAt: Instant,
    val correctsNavId: UUID? = null,
    val approvedBy: String? = null,
    val publishedAt: Instant? = null,
) {
    val navPerUnit: BigDecimal get() = figures.navPerUnit

    val isCorrection: Boolean get() = correctsNavId != null

    fun publish(approver: String, now: Instant): NavRecord {
        check(status == NavStatus.CALCULATED) { "NAV $id is $status, only a calculated NAV can be published" }
        if (approver == calculatedBy) throw FourEyesViolationException("the calculator of NAV $id cannot publish it")
        return copy(status = NavStatus.PUBLISHED, approvedBy = approver, publishedAt = now)
    }

    fun reject(approver: String): NavRecord {
        check(status == NavStatus.CALCULATED) { "NAV $id is $status, only a calculated NAV can be rejected" }
        if (approver == calculatedBy) throw FourEyesViolationException("the calculator of NAV $id cannot reject it")
        return copy(status = NavStatus.REJECTED, approvedBy = approver)
    }

    fun supersede(): NavRecord {
        check(status == NavStatus.PUBLISHED) { "only a published NAV can be superseded" }
        return copy(status = NavStatus.SUPERSEDED)
    }
}
