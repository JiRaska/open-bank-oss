// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.time.Instant
import java.time.LocalDate

/** ADR-0344 D7: the steps that close a treasury accounting day, in the order the close reads them. */
enum class DayStep { FIXINGS, ACCRUALS, PAYMENTS, REVALUATION, CORRECTIONS, MATURITIES }

/**
 * REFUSED: the ledger answered `409 ACCOUNTING_DAY_CLOSED` to at least one posting (ADR-0342 D4) —
 * a close outcome, reported to the ledger. FAILED is any other failure: the day is not finished and
 * is NOT reported, so the ledger keeps waiting and pages.
 */
enum class DayStepStatus { DONE, SKIPPED, FAILED, REFUSED, PENDING }

/** One step's last recorded outcome for one bank day; [at] null = never ran that day. */
data class DayStepRecord(
    val date: LocalDate,
    val step: DayStep,
    val status: DayStepStatus,
    val count: Int?,
    val detail: String?,
    val at: Instant?,
    /** Ledger journals the step posted (ADR-0342 report). */
    val journals: Int = 0,
    /** Postings the ledger refused because the day was closed. */
    val refused: Int = 0,
) {
    init {
        require(count == null || count >= 0) { "a step count is never negative" }
        require(journals >= 0 && refused >= 0) { "journal and refusal counts are never negative" }
        require(detail == null || detail.length <= MAX_DETAIL) { "a step detail has at most $MAX_DETAIL characters" }
    }

    companion object {
        const val MAX_DETAIL = 500

        /** The full day: every step, a recorded one as recorded, every other one PENDING. */
        fun day(date: LocalDate, recorded: List<DayStepRecord>): List<DayStepRecord> = DayStep.entries.map { step ->
            recorded.firstOrNull { it.step == step && it.date == date }
                ?: DayStepRecord(date, step, DayStepStatus.PENDING, null, "no run recorded for this day", null)
        }

        /**
         * The record a run writes: DONE with its count; REFUSED when the only failures are the
         * ledger refusing a closed day; FAILED (any other failure wins) naming how many failed.
         * [done] items processed, [journals] journals posted (defaults to [done]).
         */
        @Suppress("LongParameterList")
        fun of(
            date: LocalDate,
            step: DayStep,
            done: Int,
            failures: Int,
            at: Instant,
            refused: Int = 0,
            journals: Int = done,
        ): DayStepRecord {
            require(refused in 0..failures) { "refusals are a subset of failures" }
            val status = when {
                failures == 0 -> DayStepStatus.DONE
                failures == refused -> DayStepStatus.REFUSED
                else -> DayStepStatus.FAILED
            }
            val detail = when (status) {
                DayStepStatus.DONE -> null
                DayStepStatus.REFUSED -> "$refused posting(s) refused: the ledger accounting day is closed"
                else -> "$failures item(s) failed ($refused refused by a closed day); see the service log"
            }
            return DayStepRecord(date, step, status, done, detail, at, journals, refused)
        }
    }
}
