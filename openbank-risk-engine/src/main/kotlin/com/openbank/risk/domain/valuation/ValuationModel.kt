// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.valuation

import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.risk.domain.curve.CurveIndex
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * The valuation, sensitivity and VaR model of the bank's own deals (ADR-0344 D8). Every figure is
 * computed by libs-finance — schedules by `ScheduleGenerator`, flows by `CashFlowProjector`, present
 * values by `LegPricer`, PV01 and key-rate DV01 by `Sensitivities` — over the engine's curve sets,
 * so this package holds market-state assembly and aggregation only, never a pricer of its own.
 *
 * **Conventions, stated once.**
 * - Amounts are signed from the BANK's side (libs-finance `PayReceive`): an asset is positive PV.
 * - Discounting: each currency's overnight curve (CZK on CZEONIA, EUR on €STR); projection: a
 *   floating leg's own index curve (PRIBOR / EURIBOR), falling back to nothing — an index with no
 *   curve leaves the deal UNPRICED, never projected off another index.
 * - **PV01 = PV(all zero rates +1bp) − PV(base)**, the libs-finance sign: a payer of fixed (who
 *   gains when rates rise) has POSITIVE PV01, a receiver of fixed or a long bond NEGATIVE. A shift
 *   moves the discount curve AND, by the same factor, every projection curve of that currency.
 * - Key-rate DV01: libs-finance triangular buckets at the bucket-set pillars; they sum to PV01 to
 *   second order in the bump.
 * - **FX delta of currency c = 1 % × PV of the c legs in the reporting currency**: the reporting-
 *   currency gain if c appreciates by 1 % against it.
 * - Accrued interest is linear in the leg's own day count within the running accrual period;
 *   clean = dirty − accrued, dirty = PV.
 */
object ValuationModel {
    const val VERSION =
        "valuation-v1:libs-finance;ois-discount+index-projection;pv01=+1bp-parallel;fx-delta=1pct;" +
            "hs-var=full-revaluation,abs-zero-shift,rel-fx-shift,order-statistic-ceil"

    const val MONEY_SCALE = 2
    val ONE_PERCENT: BigDecimal = BigDecimal("0.01")

    fun money(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
}

/** ADR-0344 D2: only ACTUAL deals post, pay and enter regulatory measures. Owned by `openbank-libs-finance`. */
typealias DealKind = com.openbank.libs.finance.deal.DealKind

/** Where a deal's flows came from (ADR-0344 D4): treasury's stored ledger, or derived here. */
enum class FlowSource { STORED, DERIVED }

/** The provenance badge every report carries (ADR-0313 D13, ADR-0344 functional baseline). */
enum class ReportProvenance(val wire: String) {
    SYNTHETIC("synthetic"),
    INTRADAY("intraday"),
    REGULATORY("regulatory"),
}

enum class FlowDirection { IN, OUT }

/** One row of treasury's stored cash-flow ledger, as `treasury.cashflow.generated` carries it. */
data class StoredCashFlow(
    val legIndex: Int,
    val periodIndex: Int,
    val kind: CashFlowKind,
    val direction: FlowDirection,
    val currency: String,
    val paymentDate: LocalDate,
    val accrualStart: LocalDate?,
    val accrualEnd: LocalDate?,
    val amount: BigDecimal?,
    val rateSource: String?,
    val state: String,
) {
    /** Bank-side sign: IN positive, OUT negative. */
    val signedAmount: BigDecimal? get() = amount?.let {
        if (direction ==
            FlowDirection.IN
        ) {
            it.abs()
        } else {
            it.abs().negate()
        }
    }

    val cancelled: Boolean get() = state == "CANCELLED"

    /**
     * A FACT the engine must not re-derive: a fixed rate, an observed fixing, the contractual first
     * rate, or a flow that has fixed / fallen due / settled / posted. A PROJECTED or MISSING row is
     * treasury's projection, and the engine re-projects it from its own curves.
     */
    val isFact: Boolean get() = amount != null && (state in FACT_STATES || rateSource in FACT_RATE_SOURCES)

    private companion object {
        val FACT_STATES = setOf("FIXED", "DUE", "SETTLED", "POSTED")
        val FACT_RATE_SOURCES = setOf("FIXED", "OBSERVED", "RATE_UNTIL_FIRST_FIXING")
    }
}

/** The latest stored flow set of one deal; a newer [dealVersion] replaces it. */
data class StoredFlowSet(
    val dealId: UUID,
    val dealVersion: Int,
    val scheduleHash: String,
    val flows: List<StoredCashFlow>,
) {
    /**
     * ADR-0344 D5 (`treasury.cashflow.fixed`): the same deal version with the [fixed] flows replacing
     * the rows of the same slot (leg, period, kind). A fixed flow whose slot is not in the set is
     * refused: the set it was fixed against is not the one stored.
     */
    fun withFixed(fixed: List<StoredCashFlow>): StoredFlowSet {
        val bySlot = fixed.associateBy { Triple(it.legIndex, it.periodIndex, it.kind) }
        val slots = flows.map { Triple(it.legIndex, it.periodIndex, it.kind) }.toSet()
        require(bySlot.keys.all { it in slots }) { "a fixed flow names a slot the stored set of $dealId does not hold" }
        return copy(flows = flows.map { bySlot[Triple(it.legIndex, it.periodIndex, it.kind)] ?: it })
    }
}

/** A deal as the valuation reads it: its legs (published or derived) and any stored flows. */
data class ValuableDeal(
    val dealId: UUID,
    val bookId: String?,
    val kind: DealKind,
    val legs: List<LegSpec>,
    val stored: StoredFlowSet? = null,
) {
    init {
        require(legs.isNotEmpty() || stored != null) { "deal $dealId has neither legs nor stored flows" }
    }
}

/**
 * A named key-rate bucket set. The default is the IRRBB repricing ladder's upper bounds
 * (`TimeBucket`: overnight, 1M, 3M, 6M, 1Y, 2Y, 3Y, 5Y, 10Y, with the last bucket flat beyond).
 */
data class BucketSet(val id: String, val tenors: List<Period>) {
    init {
        require(tenors.isNotEmpty()) { "bucket set '$id' has no tenors" }
    }

    fun label(tenor: Period): String = when {
        tenor.toTotalMonths() == 0L -> "${tenor.days}D"
        tenor.toTotalMonths() % MONTHS_PER_YEAR == 0L -> "${tenor.toTotalMonths() / MONTHS_PER_YEAR}Y"
        else -> "${tenor.toTotalMonths()}M"
    }

    companion object {
        private const val MONTHS_PER_YEAR = 12L
        const val DEFAULT_ID = "irrbb-standard"
        const val MAX_BUCKETS = 30
        const val MAX_TENOR_YEARS = 50L

        val IRRBB_STANDARD = BucketSet(
            DEFAULT_ID,
            listOf(
                Period.ofDays(1),
                Period.ofMonths(1),
                Period.ofMonths(3),
                Period.ofMonths(6),
                Period.ofYears(1),
                Period.ofYears(2),
                Period.ofYears(3),
                Period.ofYears(5),
                Period.ofYears(10),
            ),
        )

        private val TENOR = Regex("^([1-9][0-9]{0,2})([DWMY])$")

        /** A bucket set by id, or an explicit comma-separated tenor list (`1M,3M,1Y,5Y`). */
        fun resolve(bucketSetId: String?, buckets: String?): BucketSet {
            if (!buckets.isNullOrBlank()) {
                val tenors = buckets.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.map { raw ->
                    val m = requireNotNull(TENOR.matchEntire(raw)) { "bucket '$raw' is not <n>D|W|M|Y" }
                    val n = m.groupValues[1].toInt()
                    when (m.groupValues[2]) {
                        "D" -> Period.ofDays(n)
                        "W" -> Period.ofWeeks(n)
                        "M" -> Period.ofMonths(n)
                        else -> Period.ofYears(n)
                    }
                }
                val asOf = LocalDate.EPOCH
                require(tenors.size <= MAX_BUCKETS) { "at most $MAX_BUCKETS buckets are accepted, got ${tenors.size}" }
                require(tenors.zipWithNext().all { (a, b) -> asOf.plus(b).isAfter(asOf.plus(a)) }) {
                    "buckets must be unique and strictly increasing: $buckets"
                }
                require(!asOf.plus(tenors.last()).isAfter(asOf.plusYears(MAX_TENOR_YEARS))) {
                    "the longest bucket may be at most ${MAX_TENOR_YEARS}Y: $buckets"
                }
                return BucketSet("explicit", tenors)
            }
            val id = bucketSetId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ID
            require(id == DEFAULT_ID) {
                "unknown bucketSetId '$id'; known: $DEFAULT_ID (or pass an explicit 'buckets' list)"
            }
            return IRRBB_STANDARD
        }
    }
}

/** The curve a floating index projects from: `PRIBOR-3M`, `PRIBOR_3M` and `pribor 3m` all name one. */
fun CurveIndex.Companion.forIndexName(name: String): CurveIndex? {
    val key = name.trim().uppercase().replace('-', '_').replace(' ', '_')
    return CurveIndex.entries.firstOrNull { it.name == key }
}
