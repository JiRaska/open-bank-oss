// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import java.time.Instant

/**
 * Outbound port for publishing **how old the ČNB fixing used by the last revaluation attempt is**
 * (#3921, ADR-0237 point 2 — "the job ran" and "the feed delivered" must be separate signals).
 *
 * ### Why this is not covered by the workflow-liveness gauge
 *
 * `FxRevaluationScheduler` already registers ADR-0160 mechanism 3, which answers *did the job run*.
 * A run that marked every position at a three-day-old fixing calls `recordSuccess()` and is
 * indistinguishable from a healthy one — as is a run where no rate was available at all, which
 * returns `posted = false` after a single WARN. That is the exact shape of the ČNB-404 history: a
 * successful-looking run of a job that revalued nothing, whose only evidence anywhere was a table
 * that stopped growing, and nothing alerts on a table not growing.
 *
 * A **counter** would not close it either. "Revaluations attempted" rises identically whether the
 * rate was minutes or a week old, and a counter of stale runs only moves once someone has already
 * chosen a staleness threshold in code. The signal that carries the information is the *age of the
 * last value actually used*, sampled at scrape time — the same primitive shape as
 * `openbank_workflow_last_success_age_seconds`, one level down: not "when did the job last
 * succeed" but "how old is the number it succeeded with".
 */
interface FxFixingFreshnessPort {

    /**
     * Report the outcome of resolving the fixing for [currency] on one revaluation attempt.
     *
     * Call on **every** attempt, for **every** currency in scope — including the ones that could
     * not be resolved. That is what makes a feed going quiet visible: passing `null` deliberately
     * leaves the currency's last known fixing instant in place, so its published age keeps growing
     * rather than the series vanishing. An absent series reads as "nothing to see" on every
     * dashboard and in most alert expressions; a growing age reads as the problem it is.
     *
     * @param currency  ISO-4217 code of the position's currency — low cardinality, three values
     * @param validFrom the instant the fixing became valid, or `null` when no fixing (or no fixing
     *                  date) was available for this attempt
     */
    fun fixingObserved(currency: String, validFrom: Instant?)
}
