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
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
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
 */
@ApplicationScoped
class TieOutScheduler(
    private val ledgerUseCase: LedgerUseCase,
    private val glAccountRepository: GlAccountRepository,
    private val runRepository: TieOutRunRepository,
    private val clock: Clock,
    registry: MeterRegistry,
) {
    private val log: Logger = Logger.getLogger(TieOutScheduler::class.java)
    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    private val breakCounter: Counter = Counter.builder("openbank.subledger.tieout.break")
        .description("Number of sub-ledger tie-out breaks detected (ADR-0039 Phase B). Non-zero = incident.")
        .register(registry)

    @Scheduled(
        cron = "0 0 6 * * ?",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun runTieOut(): Unit = runBlocking {
        val asOf = LocalDate.now(zone).minusDays(1)
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
}
