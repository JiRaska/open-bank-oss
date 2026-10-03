// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Base for a service's `openbank_outbox_oldest_age_seconds` gauge (ADR-0327 D10); the sibling of
 * [AbstractOutboxBacklogGauge] with the same shape: register once from `@PostConstruct`, refresh
 * from a `@Scheduled suspend` method so the reactive read runs on a Vert.x context.
 *
 * ```
 * @ApplicationScoped
 * class LedgerOutboxOldestAgeGauge(private val repo: LedgerOutboxRepositoryImpl, metrics: DomainMetrics) :
 *     AbstractOutboxOldestAgeGauge(metrics) {
 *     override val service = "ledger"
 *     override val repository: OutboxRepositoryV2 get() = repo
 *     @PostConstruct fun init() = registerOldestAgeGauge()
 *     @Scheduled(every = "30s", identity = "ledger-outbox-oldest-age") suspend fun refresh() = refreshOldestAge()
 * }
 * ```
 *
 * **t = 0 on a cold pod reads 0.** The cache starts at zero and is only ever set from
 * [OutboxRepositoryV2.oldestProcessableAge] — `null` (nothing eligible) is 0, a real age is that
 * age. There is no timestamp sentinel anywhere in the path, so the ADR-0237 `EPOCH` failure
 * (an age of decades on every fresh pod, firing the alert 15 minutes after every deploy) is
 * structurally impossible here, not merely avoided.
 */
abstract class AbstractOutboxOldestAgeGauge {
    private lateinit var metrics: DomainMetrics
    private val cachedSeconds = AtomicLong(0)

    constructor(metrics: DomainMetrics) {
        this.metrics = metrics
    }

    // Required by Quarkus CDI for proxy subclass generation — never called at runtime
    protected constructor()

    /** The `service` tag value for the gauge (the short service name, e.g. `"ledger"`). */
    protected abstract val service: String

    /** The v2 repository whose [OutboxRepositoryV2.oldestProcessableAge] is sampled. */
    protected abstract val repository: OutboxRepositoryV2

    /** Register the lock-free gauge supplier. Call from the concrete bean's `@PostConstruct`. */
    protected fun registerOldestAgeGauge() {
        metrics.registerOutboxOldestAge(service) { cachedSeconds.get() }
    }

    /** Refresh the cache from the repository. Call from the concrete bean's `@Scheduled` `suspend` method. */
    protected suspend fun refreshOldestAge(now: Instant = Instant.now()) {
        cachedSeconds.set((repository.oldestProcessableAge(now) ?: Duration.ZERO).seconds)
    }

    /** What the gauge currently reports — exposed for the conformance kit and unit tests. */
    fun currentAgeSeconds(): Long = cachedSeconds.get()
}
