// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters and a gauge for capitalization-claim recovery.
 *
 * **Why this exists rather than log lines.** The wedge this instruments was invisible for roughly
 * seven weeks precisely because the only evidence was a WARN nobody reads: every tick refused all
 * seven stuck pairs and then reported `capitalized 0 pair(s)`, which is indistinguishable from "no
 * work to do" (#10404). Shipping the self-heal with the same log-only observability would rebuild
 * the identical trap one level up — a silent recovery, and a silent FAILURE to recover, with
 * nothing to alert on. The repo has the general form of this lesson already: a no-op that reports
 * like success is the thing no signal disagrees with.
 *
 * **What to alert on.** `openbank_interest_capitalization_claims_outstanding` > 0 sustained across
 * more than one sweep is the actionable state — recovery is supposed to drive it back to zero, so a
 * value that persists means recovery is failing, not that a claim merely exists.
 * `..._claims_recovery_failed_total` rising is the same fact stated as a rate.
 *
 * Deliberately service-local rather than a `DomainMetrics` method: that class lives in
 * `openbank-libs-runtime`, so a new method there rebuilds and redeploys the whole fleet for one
 * service's gauge (CLAUDE.md's ~11 h fleet-resolution note). Nothing here is shared vocabulary.
 *
 * [registryInstance] is resolved lazily and tolerates absence, matching `DomainMetrics.reg()` — a
 * hand-constructed `InterestService` in a unit test has no registry, and metrics must never be the
 * reason a money path throws.
 */
@ApplicationScoped
class InterestCapitalizationMetrics @Inject constructor(private val registryInstance: Instance<MeterRegistry>) {

    /**
     * Outstanding claims as of the last sweep. A gauge rather than a counter because the question an
     * operator asks is "is anything stuck right now", and it must be able to go back DOWN — the
     * whole point of the recovery pass. Held in an [AtomicLong] and read by the scrape thread, the
     * `registerOutboxBacklog` idiom, so a Prometheus scrape never runs a database query.
     */
    private val outstanding = AtomicLong(0)

    private var gaugeRegistered = false

    private fun reg(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    /** Records how many claims the sweep found outstanding BEFORE attempting recovery. */
    fun outstandingClaims(count: Int) {
        outstanding.set(count.toLong())
        if (!gaugeRegistered) {
            reg()?.let { r ->
                r.gauge(OUTSTANDING_GAUGE, outstanding) { it.get().toDouble() }
                gaugeRegistered = true
            }
        }
    }

    /** One stale claim completed at its own frozen period. */
    fun claimRecovered() {
        reg()?.counter(RECOVERED_COUNTER)?.increment()
    }

    /** One stale claim that could NOT be completed — money frozen mid-credit, and the alertable one. */
    fun claimRecoveryFailed() {
        reg()?.counter(FAILED_COUNTER)?.increment()
    }

    internal companion object {
        const val RECOVERED_COUNTER = "openbank.interest.capitalization.claims.recovered"
        const val FAILED_COUNTER = "openbank.interest.capitalization.claims.recovery.failed"
        const val OUTSTANDING_GAUGE = "openbank.interest.capitalization.claims.outstanding"
    }
}
