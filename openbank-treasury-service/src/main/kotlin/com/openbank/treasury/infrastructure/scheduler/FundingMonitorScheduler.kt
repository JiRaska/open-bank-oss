// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.out.LedgerReadPort
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * #12115: the funding check's backstop. The booking-time guard refuses a placement the contra
 * account cannot fund; this monitor reads every contra (nostro) account's ledger balance on an
 * interval and publishes `openbank_treasury_funding_contra_balance{currency,gl}` for the
 * `TreasuryFundingExceeded` alert — the signal for a contra account driven negative by a flow that
 * bypassed the check (a payment rail, a manual ledger journal, a settlement after the balance moved).
 *
 * A currency's gauge reads NaN until its first successful read: a cold pod does not KNOW the
 * balance, and a 0 would assert "exactly funded" it never measured — NaN makes `< 0` false without
 * claiming it. A failed read resets the gauge to NaN rather than leaving a stale figure standing.
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method has no Vert.x context and a
 * `runBlocking` around reactive Panache throws HR000068 (#2148; check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class FundingMonitorScheduler(
    private val ledger: LedgerReadPort,
    private val domainMetrics: DomainMetrics,
    private val registry: MeterRegistry,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.funding-monitor.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(FundingMonitorScheduler::class.java)

    private val balances = ConcurrentHashMap<String, AtomicReference<Double>>()

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registered at boot, not lazily on first fire: an absent gauge is not a stale one (ADR-0237). */
    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (!enabled) return
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.treasury.funding-monitor.interval:15m}",
        delayed = "\${openbank.treasury.funding-monitor.initial-delay:120s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-funding-monitor",
    )
    suspend fun run() {
        if (!enabled) return
        val today = AccountingClock.bank(clock).today()
        val accounts = try {
            ledger.nostroAccounts()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // logged; gauges keep their last measured state
        ) {
            log.error("funding monitor could not read the ledger's accounting-class map", e)
            return
        }
        var failures = 0
        accounts.forEach { (currency, account) ->
            val ref = balances.computeIfAbsent(currency) {
                AtomicReference(Double.NaN).also { r ->
                    Gauge.builder(GAUGE, r) { it.get() }
                        .tags("currency", currency, "gl", account.glCode)
                        .description("Ledger balance of the $currency contra (nostro) account (#12115)")
                        .register(registry)
                }
            }
            try {
                val balance = ledger.accountBalance(account.glCode, currency, today)
                if (balance == null) {
                    ref.set(Double.NaN)
                    failures++
                    log.errorf("funding monitor: the ledger holds no %s contra account %s", currency, account.glCode)
                } else {
                    ref.set(balance.toDouble())
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // one account's outage must not blank the rest
            ) {
                ref.set(Double.NaN)
                failures++
                log.errorf(e, "funding monitor could not read the %s contra account %s", currency, account.glCode)
            }
        }
        if (failures == 0) liveness?.recordSuccess()
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-funding-monitor"
        const val GAUGE = "openbank.treasury.funding.contra_balance"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
