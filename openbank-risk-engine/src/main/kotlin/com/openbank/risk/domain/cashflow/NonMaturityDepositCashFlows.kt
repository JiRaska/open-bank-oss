// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.risk.domain.curve.BigMath
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A named, versioned behavioural model (ADR-0313 treats models as versioned; ADR-0314 D2 records
 * model versions on the run). Every output that used it carries [id] and [version], so a figure
 * can always say which assumptions produced it. Changing any parameter is a new [version].
 *
 * @property coreRatio      share of a non-maturity balance that is stable core (0..1).
 * @property coreRunoffYears years over which the core runs off, linearly, in monthly slices.
 * @property annualDepositRate interest the bank pays on the core, as a fraction (default 0: the
 *                          sandbox's current accounts pay nothing).
 */
data class BehaviouralModel(
    val id: String,
    val version: String,
    val coreRatio: BigDecimal,
    val coreRunoffYears: Int,
    val annualDepositRate: BigDecimal,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "a behavioural model needs an id and a version" }
        require(coreRatio.signum() >= 0 && coreRatio <= BigDecimal.ONE) { "coreRatio must be in [0, 1]: $coreRatio" }
        require(coreRunoffYears >= 1) { "coreRunoffYears must be at least 1: $coreRunoffYears" }
        require(annualDepositRate.signum() >= 0) { "annualDepositRate cannot be negative: $annualDepositRate" }
    }

    companion object {
        /** The phase-0 default: 70 % core over 5 years, no deposit interest. Expert judgement, not fitted. */
        val NMD_PHASE0 = BehaviouralModel(
            id = "nmd-linear-core",
            version = "1.0.0",
            coreRatio = BigDecimal("0.7"),
            coreRunoffYears = 5,
            annualDepositRate = BigDecimal.ZERO,
        )
    }
}

/**
 * Behavioural flows of a non-maturity deposit (ADR-0314 D6, ADR-0313 "deposit decay").
 *
 * Assumptions, all carried by the [BehaviouralModel] passed in:
 *  - the VOLATILE part `(1 − coreRatio) · balance` leaves on the first business day after as-of —
 *    a weekday; there is no holiday calendar in phase 0, so a public holiday is treated as open;
 *  - the CORE part runs off linearly in `12 · coreRunoffYears` equal monthly slices dated
 *    `asOf + k months` (not rolled to business days), the last slice absorbing rounding, so the
 *    run-off ends exactly `coreRunoffYears` after as-of;
 *  - interest, when the model pays any, accrues monthly on the core outstanding at the start of
 *    each month at `annualDepositRate / 12`; the volatile part earns none for its one night.
 *
 * Signs: [balance] is what the bank OWES the customer (positive for a normal credit balance), and
 * every flow is its negation — money leaving the bank. An overdrawn account (negative balance)
 * therefore projects inflows by the same arithmetic. Principal flows always sum to `−balance`.
 */
object NonMaturityDepositCashFlows {

    private const val MONTHS_PER_YEAR = 12

    fun expand(balance: BigDecimal, currency: String, asOf: LocalDate, model: BehaviouralModel): List<CashFlow> {
        if (balance.signum() == 0) return emptyList()
        val scale = minorUnits(currency)
        val total = balance.setScale(scale, RoundingMode.HALF_EVEN)
        require(total.compareTo(balance) == 0) { "balance $balance has more decimals than $currency allows" }
        val core = total.multiply(model.coreRatio, BigMath.MC).setScale(scale, RoundingMode.HALF_EVEN)
        val volatile = total.subtract(core)

        val out = ArrayList<CashFlow>()
        if (volatile.signum() != 0) {
            out += CashFlow(nextBusinessDay(asOf), currency, CashFlowKind.PRINCIPAL, volatile.negate())
        }
        val slices = model.coreRunoffYears * MONTHS_PER_YEAR
        val slice = core.divide(BigDecimal(slices), BigMath.MC).setScale(scale, RoundingMode.HALF_EVEN)
        val monthlyRate = model.annualDepositRate.divide(BigDecimal(MONTHS_PER_YEAR), BigMath.MC)
        var outstanding = core
        for (k in 1..slices) {
            val date = asOf.plusMonths(k.toLong())
            if (monthlyRate.signum() != 0) {
                val interest = outstanding.multiply(monthlyRate, BigMath.MC).setScale(scale, RoundingMode.HALF_EVEN)
                out += CashFlow(date, currency, CashFlowKind.INTEREST, interest.negate())
            }
            val principal = if (k == slices) outstanding else slice
            if (principal.signum() != 0) out += CashFlow(date, currency, CashFlowKind.PRINCIPAL, principal.negate())
            outstanding = outstanding.subtract(principal)
        }
        return out
    }

    fun nextBusinessDay(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }
}
