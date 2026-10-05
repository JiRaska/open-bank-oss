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
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** End-of-day backstop for funding accounts, including flows outside treasury's booking guard. */
@ApplicationScoped
class FundingBalanceScheduler(
    private val ledger: LedgerReadPort,
    private val domainMetrics: DomainMetrics,
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(FundingBalanceScheduler::class.java)
    private val overdrafts = ACCOUNTS.associateWith { AtomicReference(Double.NaN) }
    private var liveness: WorkflowLivenessRecorder? = null

    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        overdrafts.forEach { (account, value) ->
            Gauge.builder(GAUGE, value) { it.get() }
                .tag("account", account.code)
                .tag("currency", account.currency)
                .description("Negative end-of-day funding balance in native currency; NaN when unobserved")
                .register(registry)
        }
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, Duration.ofHours(1))
    }

    @Scheduled(
        every = "1h",
        delayed = "90s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-funding-balance",
    )
    @Suppress("TooGenericExceptionCaught") // Every failed ledger read must invalidate that account's gauge.
    suspend fun run() {
        val day = AccountingClock.bank(clock).today().minusDays(1)
        var complete = true
        overdrafts.forEach { (account, value) ->
            try {
                val balance = ledger.accountBalance(account.code, account.currency, day)
                // 1010 is absent until ADR-0332's ledger migration. Missing != zero.
                value.set(balance?.negate()?.max(java.math.BigDecimal.ZERO)?.toDouble() ?: Double.NaN)
                if (balance == null && account.code != "1010") complete = false
            } catch (failure: Exception) {
                complete = false
                value.set(Double.NaN)
                log.errorf(failure, "funding balance unavailable for %s/%s on %s", account.code, account.currency, day)
            }
        }
        if (complete) liveness?.recordSuccess()
    }

    private data class Account(val code: String, val currency: String)

    private companion object {
        const val GAUGE = "openbank.treasury.funding.overdraft"
        const val WORKFLOW = "treasury-funding-balance"
        val ACCOUNTS = listOf(Account("1001", "CZK"), Account("1002", "EUR"), Account("1010", "CZK"))
    }
}
