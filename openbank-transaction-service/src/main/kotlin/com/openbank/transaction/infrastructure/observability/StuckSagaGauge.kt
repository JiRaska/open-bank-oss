// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.transaction.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.transaction.application.port.out.TransactionRepository
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes `openbank.transaction.sagas.stuck` — payment sagas wedged in a non-terminal state
 * (`PENDING` / `PROCESSING`) for longer than [stuckAfter] (issue #5733).
 *
 * `TransactionSagaStuck` (severity `critical`, money path) has always read this series, and until
 * this bean existed nothing emitted it: the rule loaded, `promtool` accepted it and Prometheus
 * listed it `inactive` forever, which is indistinguishable from a correctly quiet alert.
 *
 * ### Why a cached value rather than a query on scrape
 * Micrometer samples a gauge supplier synchronously on the Prometheus scrape thread, and the count
 * is a reactive Panache query that needs a Vert.x context. So a scheduled `suspend` tick refreshes
 * an [AtomicLong] on the right context and the supplier reads that cache lock-free — the same
 * split [com.openbank.libs.persistence.outbox.AbstractOutboxBacklogGauge] uses. The `@Scheduled`
 * method is a `suspend fun` deliberately: a plain one carries no Vert.x context and the reactive
 * call would abort with `HR000068` on every tick, silently.
 *
 * ### What a fresh pod reports
 * `0`, from registration until the first refresh completes. That is the healthy reading, and the
 * error direction is one refresh interval of under-reporting — never a spurious page. The
 * registration is eager (`@Startup` + `@PostConstruct`, not first-use) precisely so the series
 * exists while the value is zero: an absent series makes a rule's comparison match nothing at all,
 * so a lazily created meter would leave the alert silent in the very case it exists for.
 */
@Startup
@ApplicationScoped
class StuckSagaGauge {
    @Inject
    lateinit var repository: TransactionRepository

    @Inject
    lateinit var metrics: DomainMetrics

    @Inject
    lateinit var clock: Clock

    /** How long a saga may stay non-terminal before it counts as stuck. */
    @ConfigProperty(name = "openbank.transaction.saga.stuck-after", defaultValue = "PT5M")
    lateinit var stuckAfter: Duration

    private val cached = AtomicLong(0)

    /** Current cached count — the value the gauge publishes. Visible for tests. */
    val current: Long get() = cached.get()

    @PostConstruct
    fun register() {
        metrics.registerStuckPaymentSagas { cached.get() }
    }

    @Scheduled(
        every = "30s",
        delayed = "30s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        cached.set(repository.countStuckSagas(clock.instant().minus(stuckAfter)))
    }
}
