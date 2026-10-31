// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.reporting

import java.math.BigDecimal
import java.time.LocalDate

/*
 * Participant aggregates for the ČNB statutory returns (#12425, ADR-0336 D4).
 *
 * Counts and sums only. Nothing in this file can carry a contract id, a party id, a name or a
 * birth date: the age dimension arrives already banded, and every figure is a number.
 */

/** Age bands as at the period end. Closed list so a report's keys never depend on the data. */
// The boundaries ARE the definition; naming each one as a constant would only restate it.
@Suppress("MagicNumber")
enum class AgeBand(val label: String, val minAge: Int, val maxAge: Int?) {
    UNDER_18("0-17", 0, 17),
    FROM_18_TO_34("18-34", 18, 34),
    FROM_35_TO_49("35-49", 35, 49),
    FROM_50_TO_59("50-59", 50, 59),
    FROM_60_TO_64("60-64", 60, 64),
    FROM_65("65+", 65, null),
    ;

    companion object {
        fun of(age: Int): AgeBand = entries.first { age >= it.minAge && (it.maxAge == null || age <= it.maxAge) }
    }
}

/** Status a contract held at the period end; [CHANGED_AFTER_PERIOD_END] when that is no longer knowable. */
object StatusBuckets {
    const val CHANGED_AFTER_PERIOD_END = "CHANGED_AFTER_PERIOD_END"
}

/** One GROUP BY row of in-force contracts: no identifier, only the dimensions and a count. */
data class InForceGroup(val productLine: String, val ageYears: Int, val statusAtEnd: String, val count: Long)

data class ParticipantCounts(
    val inForce: Long,
    val newInPeriod: Long,
    val exitedInPeriod: Long,
    val contributing: Long,
    val pensioners: Long,
    val byProductLine: Map<String, Long>,
    val byAgeBand: Map<String, Long>,
    val byStatusAtPeriodEnd: Map<String, Long>,
)

data class ContributionTotals(
    val participant: BigDecimal,
    val employer: BigDecimal,
    val state: BigDecimal,
    val transferIn: BigDecimal,
) {
    /** Participant + employer + state. Transfers in are savings moved, not contributed, so excluded. */
    val total: BigDecimal get() = participant + employer + state
}

data class StateContributionFigures(val claimed: BigDecimal, val received: BigDecimal, val returned: BigDecimal)

data class PayoutLine(val amount: BigDecimal, val count: Long)

data class PayoutTotals(
    val byForm: Map<String, PayoutLine>,
    val taxWithheld: BigDecimal,
    /** Distinct contracts that received a payout. */
    val cases: Long,
) {
    val total: BigDecimal get() = byForm.values.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
}

data class TransferTotals(val inCount: Long, val inAmount: BigDecimal, val outCount: Long, val outAmount: BigDecimal)

data class ParticipantPeriodAggregates(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currency: String,
    val participants: ParticipantCounts,
    val contributions: ContributionTotals,
    val contributionsYtd: ContributionTotals,
    val stateContributions: StateContributionFigures,
    val payouts: PayoutTotals,
    val payoutsYtd: PayoutTotals,
    val transfers: TransferTotals,
)

/** The period spans currencies; summing them would be wrong and splitting them is not asked for. */
class MixedCurrencyException(message: String) : IllegalStateException(message)

class UnknownSettlementTimeException(message: String) : IllegalStateException(message)

object ParticipantBanding {
    fun countsFrom(
        groups: List<InForceGroup>,
        newInPeriod: Long,
        exitedInPeriod: Long,
        contributing: Long,
        pensioners: Long,
    ): ParticipantCounts {
        fun <K> sumBy(key: (InForceGroup) -> K): Map<K, Long> =
            groups.groupBy(key).mapValues { (_, g) -> g.sumOf { it.count } }
        val byBand = sumBy { AgeBand.of(it.ageYears).label }
        return ParticipantCounts(
            inForce = groups.sumOf { it.count },
            newInPeriod = newInPeriod,
            exitedInPeriod = exitedInPeriod,
            contributing = contributing,
            pensioners = pensioners,
            byProductLine = sumBy { it.productLine }.toSortedMap(),
            // Every band is present, zero or not, so a missing key never reads as "not reported".
            byAgeBand = AgeBand.entries.associate { it.label to (byBand[it.label] ?: 0L) },
            byStatusAtPeriodEnd = sumBy { it.statusAtEnd }.toSortedMap(),
        )
    }
}
