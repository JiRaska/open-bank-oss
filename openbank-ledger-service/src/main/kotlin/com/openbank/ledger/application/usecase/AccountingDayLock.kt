// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.model.DayLockDecision
import com.openbank.libs.domain.calendar.AccountingClock
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.LocalDate

/**
 * The day-granular period lock (ADR-0207 D3), and the staged rollout that makes it safe to turn on.
 *
 * Before this existed the only period lock was `requireOpenPeriod(entryDate.year)` against an
 * ATTESTED fiscal year. `entryDate` is caller-supplied and had no floor, so a journal could be
 * booked into any day of the current fiscal year — including a day already tied out, reconciled
 * and reported. That is not hypothetical: this repo runs an end-of-day tie-out which surfaced a
 * real ledger-versus-sub-ledger drift, and a backdated posting silently invalidates a tie-out
 * computed before it. The figures were right when produced and become wrong afterwards, with no
 * event recording that they did.
 *
 * ## Modes
 *
 * - [MODE_OFF]     — the check does not run. Escape hatch only.
 * - [MODE_SHADOW]  — **the default, and the ship state.** The check runs and records what it
 *                    *would* have refused, without refusing. This measures the volume of
 *                    currently-legal backdated postings before any of them start failing.
 * - [MODE_ENFORCE] — the check refuses. Flip only once the shadow counter shows what breaks.
 *
 * Turning on a new refusal blind, on the money path, is how #1197 killed five workloads for four
 * days. The shadow counter `openbank.ledger.day_lock.decisions{outcome="would_refuse"}` is the
 * evidence that must be zero (or explained) before [MODE_ENFORCE].
 *
 * A day with no row is **not** refused in any mode: an unopened day is not evidence, and failing
 * closed on absence would brick every posting the moment this ships, before a single day has been
 * opened. Absence is counted separately so it is visible rather than assumed.
 */
@ApplicationScoped
class AccountingDayLock(
    private val accountingDayRepository: AccountingDayRepository,
    private val accountingClock: AccountingClock,
    private val meterRegistry: MeterRegistry,
    @ConfigProperty(name = "openbank.ledger.day-lock.mode", defaultValue = MODE_SHADOW)
    private val mode: String,
) {
    private val log = Logger.getLogger(AccountingDayLock::class.java)

    /** True when a refusal is actually raised rather than only recorded. */
    val enforcing: Boolean get() = mode == MODE_ENFORCE

    /**
     * Evaluate the day lock for [entryDate]. **Never throws** — it reports, and the caller decides.
     *
     * This asymmetry is deliberate. A *posting* into a closed day has no correct outcome but
     * refusal ([requireOpen]). A *reversal* of an entry in a closed day is exactly the operation
     * that situation calls for, so it must not be refused; it is routed forward instead
     * ([forwardCorrectionDate]). Both paths need the same measurement, only one needs the refusal.
     */
    suspend fun evaluate(entryDate: LocalDate, operation: String): DayLockDecision {
        if (mode == MODE_OFF) return DayLockDecision.unknownDay(entryDate)

        val day = accountingDayRepository.findByDate(entryDate)
        val decision = when {
            day == null -> DayLockDecision.unknownDay(entryDate)
            day.acceptsPostings -> DayLockDecision.allowed(day)
            else -> DayLockDecision.refused(day)
        }
        record(decision, day == null, operation)
        return decision
    }

    /**
     * Evaluate and, in [MODE_ENFORCE] only, refuse. The posting path's guard: a new journal dated
     * into a day that is no longer OPEN is the operation being removed.
     */
    suspend fun requireOpen(entryDate: LocalDate, operation: String) {
        val decision = evaluate(entryDate, operation)
        if (decision.wouldRefuse && enforcing) {
            throw ClosedAccountingDayException(decision.reason ?: "Accounting day $entryDate is closed")
        }
    }

    /**
     * Where a correction out of a closed day must land (ADR-0207 D3): the latest day still OPEN,
     * falling back to the current accounting day when no day has been opened yet (the state every
     * environment is in until an operator opens the first one — the fallback keeps reversals
     * working exactly as they do today rather than making them depend on a rollout step).
     */
    suspend fun forwardCorrectionDate(): LocalDate =
        accountingDayRepository.findLatestOpen()?.businessDate ?: accountingClock.today()

    private fun record(decision: DayLockDecision, dayMissing: Boolean, operation: String) {
        val outcome = when {
            dayMissing -> OUTCOME_NO_DAY
            !decision.wouldRefuse -> OUTCOME_ALLOWED
            enforcing -> OUTCOME_REFUSED
            else -> OUTCOME_WOULD_REFUSE
        }
        meterRegistry.counter(
            "openbank.ledger.day_lock.decisions",
            "mode",
            mode,
            "outcome",
            outcome,
            "operation",
            operation,
            "day_status",
            decision.status?.name ?: "NONE",
        ).increment()

        // Shadow mode's whole purpose is to be readable before enforcement: log the entry date so
        // the backdated postings can be attributed, not just counted.
        if (outcome == OUTCOME_WOULD_REFUSE) {
            log.warnf(
                "Day lock SHADOW: %s into %s (status=%s) would be refused under enforce mode",
                operation,
                decision.entryDate,
                decision.status,
            )
        }
    }

    companion object {
        const val MODE_OFF = "off"
        const val MODE_SHADOW = "shadow"
        const val MODE_ENFORCE = "enforce"

        const val OPERATION_POSTING = "posting"
        const val OPERATION_REVERSAL = "reversal"

        private const val OUTCOME_ALLOWED = "allowed"
        private const val OUTCOME_NO_DAY = "no_day_record"
        private const val OUTCOME_WOULD_REFUSE = "would_refuse"
        private const val OUTCOME_REFUSED = "refused"
    }
}

/**
 * A posting targeted an accounting day that is no longer OPEN (ADR-0207 D3). Mapped to 409.
 * Distinct from [ClosedFiscalPeriodException] (the year-granular lock, #869) so the response says
 * which of the two locks refused and at what granularity — they have different remedies.
 */
class ClosedAccountingDayException(message: String) : RuntimeException(message)
