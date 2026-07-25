// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.GetControlAccountTieOutQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.lock.ClusterLock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily sub-ledger tie-out check (ADR-0039 Phase B). Runs at 06:00 CET after the previous
 * business day's postings (including the FX revaluation at 15:00) are fully settled.
 *
 * For each deposit-control account (2100–2103) asserts:
 *   Σ per-customer sub-ledger net == GL control-account net
 *
 * A non-zero delta increments `openbank.subledger.tieout.break` (the SubledgerTieOutBreak
 * PrometheusRule pages on any increase) and logs ERROR. Every run — OK, BREAK or ERROR —
 * persists a [TieOutRunRecord] so the control is provable from data, and so
 * [TieOutFreshnessWatchdog] can escalate a MISSING run (the #855 failure mode: a control
 * that silently stops running is invisible in metrics that only fire on breaks).
 * The scheduler never crashes — failures are caught, logged and recorded as ERROR.
 *
 * **Catch-up (issue #1378).** A single JVM instance owns this `@Scheduled` trigger with no
 * cross-pod coordination (ADR-0039 Phase B pre-dates that work); a deploy — canary rollout or
 * plain restart — that straddles the exact 06:00 fire time skips the tick with nothing to
 * retry it. That happened for real on 2026-07-17: PR #1316's rollout landed on the trigger
 * minute and the day was never checked (confirmed clean only by a manual query against
 * [LedgerUseCase.getControlAccountTieOut] after the fact). So every run first asks
 * [TieOutRunRepository.findLatest] for the last `as_of` it has a row for and walks forward from
 * there to yesterday, oldest day first — the same healing shape as `CloseCalendar`'s
 * oldest-first catch-up in statement-service, for the same reason: capping to the *newest* N
 * days would strand the oldest gap forever, since the cursor here only ever moves forward too.
 *
 * **Cross-pod exclusion (#1201).** `concurrentExecution = SKIP` only stops in-JVM overlap; an
 * Argo Rollouts canary window runs the old and new pod simultaneously for the whole rollout, and
 * both fire this trigger on their own tick. Without coordination, two pods would both walk the
 * same catch-up gap and both double-increment `openbank.subledger.tieout.break` plus write two
 * run records for the same `as_of`. [ClusterLock.tryRunExclusively] wraps the whole run in a
 * transaction-scoped advisory lock so only one pod's tick actually executes; the losing pod's
 * tick is a no-op — not a missed day, since the winning pod still covers the full catch-up gap
 * above.
 */
@ApplicationScoped
class TieOutScheduler(
    private val ledgerUseCase: LedgerUseCase,
    private val glAccountRepository: GlAccountRepository,
    private val runRepository: TieOutRunRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.ledger.tieout.max-catchup-days", defaultValue = "7")
    private val maxCatchUpDays: Int,
    private val clusterLock: ClusterLock,
    registry: MeterRegistry,
) {
    private val log: Logger = Logger.getLogger(TieOutScheduler::class.java)
    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    private val breakCounter: Counter = Counter.builder("openbank.subledger.tieout.break")
        .description("Number of sub-ledger tie-out breaks detected (ADR-0039 Phase B). Non-zero = incident.")
        .register(registry)

    // `suspend`, never `runBlocking` (#2187, the fleet sweep of #2148). Quarkus invokes a plain
    // @Scheduled method on a bare `executor-thread`, which carries no Vert.x context, so
    // `runBlocking { clusterLock.tryRunExclusively(…) }` ran [PostgresClusterLock]'s
    // `Panache.withTransaction` — the FIRST reactive call, and it sits *outside* every try/catch
    // below — off the event loop and threw `HR000068: This method should exclusively be invoked
    // from a Vert.x EventLoop thread`. Every daily tick aborted there, so the sub-ledger tie-out
    // control (ADR-0039 Phase B) never checked a single account and never recorded a run. A
    // suspending @Scheduled method is dispatched by Quarkus on a proper (duplicated) Vert.x
    // context instead.
    //
    // The cron is a config expression (same default as before) purely so an IT can shrink it and
    // drive the *real* scheduler dispatch — calling this method directly supplies a context the
    // scheduler does not, and would pass against the broken code.
    @Scheduled(
        cron = "{openbank.ledger.tieout.cron:0 0 6 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runTieOut() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            // clock.withZone(zone), NOT LocalDate.now(zone): the latter is `Clock.system(zone)` —
            // it silently ignores the injected `clock` and reads the JVM's real wall clock.
            // Harmless in production (the CDI clock is `Clock.systemUTC()`, so both give the same
            // instant and therefore the same Prague date), but it makes this method untestable
            // with a fixed clock — exactly the class of clock-injection violation flagged
            // fleet-wide in the closing audit (docs/audits/2026-07-16-closing-audit.md, systemic
            // root cause 1).
            val through = LocalDate.now(clock.withZone(zone)).minusDays(1)
            val dates = catchUpDates(through)
            if (dates.isEmpty()) {
                log.infof("Sub-ledger tie-out: already checked through %s — nothing to do", through)
            }
            dates.forEach { asOf -> runTieOutFor(asOf) }
        }
        if (ran == null) {
            log.infof("Sub-ledger tie-out: another pod already holds this tick's lock — skipping")
        }
    }

    /**
     * The dates this run must (re-)check, oldest first: everything after the latest recorded
     * `as_of` up to and including [through]. No prior run at all means this is the very first
     * tick ever — there is no history to anchor a backlog from, so only [through] is checked,
     * exactly the pre-catch-up behaviour.
     *
     * Bounded to [maxCatchUpDays] so a long-dormant scheduler can't enqueue an unbounded backlog
     * in one run; the *oldest* days are kept (not the newest — see the class KDoc) so later runs
     * keep making forward progress on the gap instead of re-checking the same recent window
     * forever.
     */
    private suspend fun catchUpDates(through: LocalDate): List<LocalDate> {
        val latestAsOf = runRepository.findLatest()?.asOf ?: return listOf(through)
        val from = latestAsOf.plusDays(1)
        if (from > through) return emptyList()
        val gap = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= through }.toList()
        if (gap.size > maxCatchUpDays) {
            log.warnf(
                "Sub-ledger tie-out: %d-day gap since %s exceeds the %d-day catch-up cap — " +
                    "healing oldest-first; %d day(s) will remain after this run",
                gap.size,
                latestAsOf,
                maxCatchUpDays,
                gap.size - maxCatchUpDays,
            )
        }
        return gap.take(maxCatchUpDays)
    }

    private suspend fun runTieOutFor(asOf: LocalDate) {
        log.infof("Sub-ledger tie-out check for %s", asOf)
        // Aggregate per-account outcomes rather than mutating counters inside the loop lambda:
        // the accumulation is the same, but it reads as one expression and CodeQL can actually
        // follow it (mutable captures across a lambda boundary read to it as never-written).
        val outcomes = GlAccount.DEPOSIT_CONTROL_CODES.map { code -> checkControlAccount(code, asOf) }
        val checked = outcomes.count { it.checked }
        val breaks = outcomes.sumOf { it.breaks }
        val errors = outcomes.count { it.failed }
        if (breaks == 0 && errors == 0) {
            log.infof("Sub-ledger tie-out OK for %s", asOf)
        }
        recordRun(asOf, checked, breaks, errors)
    }

    /**
     * Checks one deposit-control account. A missing (not yet seeded) account is neither checked
     * nor an error; an account with no activity as of the date IS checked and ties out trivially.
     */
    @Suppress("TooGenericExceptionCaught") // scheduler must survive any infra failure (DB, Kafka, serialization)
    private suspend fun checkControlAccount(code: String, asOf: LocalDate): AccountOutcome = try {
        val account = glAccountRepository.findByCode(code)
        if (account == null) {
            log.infof("Tie-out: deposit-control account code=%s not yet seeded — skipping", code)
            AccountOutcome.SKIPPED
        } else {
            val tieOuts = ledgerUseCase.getControlAccountTieOut(
                GetControlAccountTieOutQuery(controlAccountId = account.id, asOf = asOf),
            )
            if (tieOuts.isEmpty()) {
                log.infof("Tie-out: control account code=%s has no activity as of %s — OK", code, asOf)
            }
            val broken = tieOuts.filter { !it.isTiedOut }
            broken.forEach { tieOut ->
                breakCounter.increment()
                log.errorf(
                    "Sub-ledger tie-out BREAK: control account code=%s currency=%s glNet=%s subLedgerNet=%s delta=%s asOf=%s",
                    code,
                    tieOut.currency,
                    tieOut.glNet,
                    tieOut.subLedgerNet,
                    tieOut.delta,
                    asOf,
                )
            }
            AccountOutcome(checked = true, breaks = broken.size, failed = false)
        }
    } catch (ex: Exception) {
        log.errorf(ex, "Tie-out check failed for control account code=%s: %s", code, ex.message)
        AccountOutcome.FAILED
    }

    /** One control account's contribution to the run totals. */
    private data class AccountOutcome(val checked: Boolean, val breaks: Int, val failed: Boolean) {
        companion object {
            val SKIPPED = AccountOutcome(checked = false, breaks = 0, failed = false)
            val FAILED = AccountOutcome(checked = false, breaks = 0, failed = true)
        }
    }

    private suspend fun recordRun(asOf: LocalDate, checked: Int, breaks: Int, errors: Int) {
        // BREAK outranks ERROR: a confirmed integrity incident beats an incomplete check.
        val status = when {
            breaks > 0 -> TieOutRunStatus.BREAK
            errors > 0 -> TieOutRunStatus.ERROR
            else -> TieOutRunStatus.OK
        }
        try {
            runRepository.save(
                TieOutRunRecord(
                    // Durable, time-ordered run id (ADR-0106): rows are written chronologically
                    // and read newest-first, so UUIDv7 keeps the B-tree insert local.
                    id = Ids.newId(),
                    asOf = asOf,
                    runAt = Instant.now(clock),
                    status = status,
                    accountsChecked = checked,
                    breaks = breaks,
                    errors = errors,
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
            // The run itself completed; an unpersisted record must not crash the scheduler.
            // The freshness watchdog will surface the missing row within its SLA.
            log.errorf(ex, "Tie-out run record persist failed (status=%s asOf=%s): %s", status, asOf, ex.message)
        }
    }

    private companion object {
        const val JOB_NAME = "ledger.tieout"
    }
}
