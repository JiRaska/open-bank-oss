// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import java.math.BigDecimal
import java.time.LocalDate

/**
 * #12115: the verified available funding on a currency's contra (nostro) account, read from the
 * ledger's own balance — never cached, never guessed. A failure to establish the figure propagates
 * (`LedgerUnavailableException`, 502): a placement is never booked against an unverified balance.
 */
fun interface FundingPort {
    /** Net (debit − credit) of [currency]'s contra account as of [asOf], in the native currency. */
    suspend fun balance(currency: String, asOf: LocalDate): BigDecimal
}

/**
 * #12115: counts a booking refused because the contra account would not hold the money (the
 * pre-trade check, or the locked re-measure when a concurrent booking committed in between).
 */
fun interface FundingMeter {
    fun exceeded(currency: String)

    companion object {
        val NONE = FundingMeter { }
    }
}

/**
 * The funding headroom a booking was vetted against: the contra account's [balance] as measured
 * before the write and the [required] principal of the deal being booked. EVERY other committed,
 * not-yet-settled outflow counts against it, whatever its value date: the balance is today's and no
 * future inflow is projected, so an outflow valued later than this deal still needs the same cash
 * (otherwise A for 800 at D+5 then B for 800 at D+1 against 1000 both pass and both settle). Inside the booking's transaction the (currency) funding line is locked and the
 * committed outflows re-measured, so two bookings on one line serialise and cannot jointly overdraw
 * (mirrors [ExposureGuard] for the counterparty line). A shortfall refuses the booking
 * ([FundingExceededException], 422) instead of committing an overdrawn nostro.
 */
data class FundingGuard(val currency: String, val balance: BigDecimal, val required: BigDecimal) {
    /** True when the balance covers the already-committed [pendingOutflows] plus this deal. */
    fun fits(pendingOutflows: BigDecimal): Boolean = balance.subtract(pendingOutflows).subtract(required).signum() >= 0

    fun describe(pendingOutflows: BigDecimal): String =
        "booking needs $required $currency on the contra account; balance $balance, " +
            "already committed (unsettled, any value date) $pendingOutflows"
}

/** The contra account would go negative: the booking is refused, nothing is written (422 FUNDING_EXCEEDED). */
class FundingExceededException(message: String) : RuntimeException(message)
