// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.time.Instant
import java.time.LocalDate

/** ADR-0344 D7: the steps that close a treasury accounting day, in the order the close reads them. */
enum class DayStep { FIXINGS, ACCRUALS, PAYMENTS, REVALUATION, CORRECTIONS, MATURITIES }

enum class DayStepStatus { DONE, SKIPPED, FAILED, PENDING }

/** One step's last recorded outcome for one bank day; [at] null = never ran that day. */
data class DayStepRecord(
    val date: LocalDate,
    val step: DayStep,
    val status: DayStepStatus,
    val count: Int?,
    val detail: String?,
    val at: Instant?,
) {
    init {
        require(count == null || count >= 0) { "a step count is never negative" }
        require(detail == null || detail.length <= MAX_DETAIL) { "a step detail has at most $MAX_DETAIL characters" }
    }

    companion object {
        const val MAX_DETAIL = 500

        /** The full day: every step, a recorded one as recorded, every other one PENDING. */
        fun day(date: LocalDate, recorded: List<DayStepRecord>): List<DayStepRecord> = DayStep.entries.map { step ->
            recorded.firstOrNull { it.step == step && it.date == date }
                ?: DayStepRecord(date, step, DayStepStatus.PENDING, null, "no run recorded for this day", null)
        }

        /** The record a run writes: DONE with its count, or FAILED naming how many items failed. */
        fun of(date: LocalDate, step: DayStep, done: Int, failures: Int, at: Instant): DayStepRecord = DayStepRecord(
            date = date,
            step = step,
            status = if (failures == 0) DayStepStatus.DONE else DayStepStatus.FAILED,
            count = done,
            detail = if (failures == 0) null else "$failures item(s) failed; see the service log",
            at = at,
        )
    }
}
