// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.domain.cnb

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A ČNB monetary-policy rate or minimum-reserve parameter, each a step function of its effective
 * date. [feedHeader] is the exact header line of the instrument's official history file; an
 * instrument without one is read from the ČNB minimum-reserve workbook instead
 * ([CnbMinimumReserveParser]).
 */
enum class CnbPolicyInstrument(val feedHeader: String?) {
    /** 2-week repo rate — the ČNB's main monetary-policy rate. */
    REPO_2W("PLATNA_OD|CNB_REPO_SAZBA_V_%"),

    /** Discount rate — the deposit facility rate. */
    DISCOUNT("PLATNA_OD|CNB_DISKONTNI_SAZBA_V_%"),

    /** Lombard rate — the marginal lending facility rate. */
    LOMBARD("PLATNA_OD|CNB_LOMBARDNI_SAZBA_V_%"),

    /** Minimum reserve (PMR) ratio applied to the reserve base. From `PMR_historie_zmen.xlsx`. */
    MIN_RESERVE_RATIO(null),

    /** Rate the ČNB pays on required reserves. From `PMR_historie_zmen.xlsx`. */
    MIN_RESERVE_REMUNERATION(null),
    ;

    companion object {
        val FEED_BACKED: List<CnbPolicyInstrument> = entries.filter { it.feedHeader != null }

        fun parse(raw: String): CnbPolicyInstrument = entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw IllegalArgumentException(
                "unknown ČNB policy-rate instrument '$raw'; one of ${entries.joinToString { it.name }}",
            )
    }
}

/** One parsed history row: [rate] is a FRACTION (the feed's 3,75 % is 0.0375). */
data class CnbPolicyRateObservation(val effectiveFrom: LocalDate, val rate: BigDecimal)

/** A stored fact with its provenance — what the REST read and the published event carry. */
data class CnbPolicyRateFact(
    val instrument: CnbPolicyInstrument,
    val effectiveFrom: LocalDate,
    val rate: BigDecimal,
    val sourceUrl: String,
    val fetchedAt: Instant,
    val contentSha256: String,
    val note: String?,
    val previousRate: BigDecimal?,
    val revisedAt: Instant?,
)

/** What one upsert did to the stored history of one instrument. */
data class CnbPolicyRateUpsert(val inserted: Int, val unchanged: Int, val revised: Int)
