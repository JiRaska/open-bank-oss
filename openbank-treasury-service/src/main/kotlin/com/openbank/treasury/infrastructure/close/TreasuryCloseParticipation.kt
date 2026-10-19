// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.DayStepStatus
import java.time.LocalDate

/**
 * Treasury as an ADR-0342 day-close participant. The day's report is folded from treasury's own
 * day-completion record (ADR-0344 D7, `GET /day-completion/{date}`), one step per job:
 *
 * - a DONE or SKIPPED step is a finished job with its counts;
 * - a REFUSED step (the ledger answered 409 ACCOUNTING_DAY_CLOSED to a posting) is a finished job
 *   whose refusals make the day REFUSED;
 * - a FAILED step is NOT a finished job: the day stays unreported, the ledger keeps it in CUTOFF and
 *   pages. Treasury failing for any other reason holds the close; it never lets it pass without us.
 *
 * [dueSteps] are the steps scheduled on every accounting day; none due = SKIPPED.
 */
object TreasuryCloseParticipation {
    const val PARTICIPANT = "treasury"

    fun report(day: LocalDate, dueSteps: Set<DayStep>, records: Collection<DayStepRecord>): CloseParticipationReport? {
        val runs = records.filter { it.date == day && it.at != null && it.status != DayStepStatus.FAILED }.map {
            CloseJobRun(
                businessDate = it.date,
                job = it.step.name,
                itemsProcessed = it.count ?: 0,
                journalsPosted = it.journals,
                refused = if (it.status == DayStepStatus.REFUSED) maxOf(it.refused, 1) else 0,
                refusalReason = it.detail,
                completedAt = checkNotNull(it.at),
            )
        }
        return CloseParticipationAssembler.assemble(
            day,
            dueSteps.map { it.name }.toSet(),
            runs,
            skipReason = "no treasury day step is scheduled (openbank.treasury.close.due-steps is empty)",
        )
    }

    /** Parse the configured due-step list (comma separated, case-insensitive); unknown names fail the boot. */
    fun dueSteps(config: String): Set<DayStep> = config.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { name ->
            requireNotNull(DayStep.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }) {
                "openbank.treasury.close.due-steps names an unknown step '$name' (one of ${DayStep.entries})"
            }
        }.toSet()
}
