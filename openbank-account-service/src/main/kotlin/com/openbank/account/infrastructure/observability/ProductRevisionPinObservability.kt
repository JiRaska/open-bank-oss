// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.observability

import com.openbank.account.application.port.out.PinOutcome
import com.openbank.account.application.port.out.ProductRevisionPinMetrics
import com.openbank.account.infrastructure.persistence.repository.AccountRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** `openbank.account.product_revision.pin{outcome}` — one counter per [PinOutcome], registered eagerly. */
@ApplicationScoped
class MicrometerProductRevisionPinMetrics(registry: MeterRegistry) : ProductRevisionPinMetrics {
    private val counters: Map<PinOutcome, Counter> = PinOutcome.entries.associateWith { outcome ->
        Counter.builder(PIN_METRIC)
            .description("Account openings by product-revision pin outcome (ADR-0341 D1)")
            .tag("service", "account")
            .tag("outcome", outcome.tag)
            .register(registry)
    }

    override fun record(outcome: PinOutcome) {
        counters.getValue(outcome).increment()
    }

    companion object {
        const val PIN_METRIC = "openbank.account.product_revision.pin"
    }
}

/**
 * `openbank.account.product_revision.unpinned` — accounts not CLOSED that carry no product-revision
 * pin (ADR-0341 D1). This is the read-only report of the backfill debt: every account opened before
 * V33 counts here until the documented backfill pins it, and a NEW account counting here would mean
 * an opening path that skipped the pin. Read-only; it never writes a pin.
 *
 * Until the first refresh succeeds the gauge reads -1, not 0: "not measured yet" must not look like
 * "nothing to backfill". `@Startup` so the gauge and the liveness heartbeat exist from boot.
 */
@Startup
@ApplicationScoped
class UnpinnedAccountsGauge(
    private val accounts: AccountRepositoryImpl,
    private val registry: MeterRegistry,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(UnpinnedAccountsGauge::class.java)
    private val unpinned = AtomicLong(NOT_MEASURED)
    private lateinit var liveness: WorkflowLivenessRecorder

    @PostConstruct
    fun register() {
        Gauge.builder(UNPINNED_METRIC, unpinned) { it.get().toDouble() }
            .description("Accounts not CLOSED without a product-revision pin; -1 until first measured")
            .tag("service", "account")
            .register(registry)
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "{openbank.account.product-revision.unpinned-refresh:5m}",
        delayed = "30s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "account-unpinned-product-revision-gauge",
    )
    @Suppress("TooGenericExceptionCaught") // any failure is the same "not counted this tick"
    suspend fun refresh() {
        // observed-by: the liveness heartbeat (no recordSuccess on a failed count) and the -1 value.
        try {
            unpinned.set(accounts.countUnpinnedNotClosed())
            liveness.recordSuccess()
        } catch (e: Exception) {
            log.warnf("unpinned-account count failed; gauge keeps its last value: %s", e.toString())
        }
    }

    fun current(): Long = unpinned.get()

    companion object {
        const val UNPINNED_METRIC = "openbank.account.product_revision.unpinned"
        const val WORKFLOW_NAME = "account-unpinned-product-revision-gauge"
        const val NOT_MEASURED = -1L
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
