// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.GetControlAccountTieOutQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.domain.model.GlAccount
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily sub-ledger tie-out check (ADR-0039 Phase B). Runs at 06:00 CET after the previous
 * business day's postings (including the FX revaluation at 15:00) are fully settled.
 *
 * For each deposit-control account (2100–2103) asserts:
 *   Σ per-customer sub-ledger net == GL control-account net
 *
 * A non-zero delta increments `openbank.subledger.tieout.break` (fires SubledgerTieOutBreak
 * alert) and logs ERROR. The scheduler never crashes — failures are caught and logged.
 */
@ApplicationScoped
class TieOutScheduler(
    private val ledgerUseCase: LedgerUseCase,
    private val glAccountRepository: GlAccountRepository,
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
    @Suppress("TooGenericExceptionCaught") // scheduler must survive any infra failure (DB, Kafka, serialization)
    fun runTieOut(): Unit = runBlocking {
        val asOf = LocalDate.now(zone).minusDays(1)
        log.infof("Sub-ledger tie-out check for %s", asOf)
        var breaks = 0
        GlAccount.DEPOSIT_CONTROL_CODES.forEach { code ->
            try {
                val account = glAccountRepository.findByCode(code) ?: run {
                    log.infof("Tie-out: deposit-control account code=%s not yet seeded — skipping", code)
                    return@forEach
                }
                val tieOuts = ledgerUseCase.getControlAccountTieOut(
                    GetControlAccountTieOutQuery(controlAccountId = account.id, asOf = asOf),
                )
                if (tieOuts.isEmpty()) {
                    log.infof("Tie-out: control account code=%s has no activity as of %s — OK", code, asOf)
                    return@forEach
                }
                tieOuts.filter { !it.isTiedOut }.forEach { tieOut ->
                    breakCounter.increment()
                    breaks++
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
            } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                log.errorf(ex, "Tie-out check failed for control account code=%s: %s", code, ex.message)
            }
        }
        if (breaks == 0) {
            log.infof("Sub-ledger tie-out OK for %s", asOf)
        }
    }
}
