// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure

import com.openbank.kyb.application.port.out.KybMonitoringMetricsPort
import com.openbank.kyb.application.port.out.MonitoringStats
import com.openbank.kyb.application.usecase.MonitoringFindingOutcome
import com.openbank.kyb.application.usecase.MonitoringRegistrationService
import com.openbank.kyb.application.usecase.MonitoringSettings
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.atomic.AtomicReference

/**
 * Meters of ongoing-monitoring registration and findings, each named for what this service can
 * establish:
 *
 *  - [ENABLED] — 1 or 0, the switch itself.
 *  - [REGISTRATIONS] — relay attempts by `outcome` (`registered` | `withdrawn` | `failed` |
 *    `unauthorised` | `refused`) and, unless delivered, `reason`. `registered` means the registry ACCEPTED the subject, not that it
 *    has been evaluated: the first evaluation is the registry's sweep.
 *  - [PENDING] / [PENDING_AGE] — rows still owed to the registry and the age of the oldest.
 *  - [REGISTERED] — rows the registry has confirmed.
 *  - [FINDINGS] — records on the findings channel by what became of them.
 *  - [REFUSED] — subjects the registry refuses as they are: approved and NOT monitored.
 *  - [EXITS_REQUIRED] — approved cases whose relationship an operator decided has to end.
 *  - [REVIEWS_OPEN] / [REVIEW_AGE] — approved cases under a monitoring review, and the oldest.
 *  - [UNSCREENED] — approved cases with no screening at all, so nobody on them can be registered.
 *
 * The gauges are read from stored timestamps by the scheduled run, so they survive a restart, and
 * they read NaN until the first run has looked: unknown is not zero, and a fresh pod reports
 * neither a backlog nor its absence.
 *
 * The counters an alert reads are registered at ZERO here, at boot. A counter created by its
 * first increment is born positive, and `increase()` cannot see a series whose first sample is
 * its first increment — the first failure after every restart would be invisible.
 *
 * `@Startup`: the meters must exist from boot, and nothing else would create this lazy bean.
 */
@Startup
@ApplicationScoped
class MicrometerKybMonitoringMetrics(private val registry: MeterRegistry, private val settings: MonitoringSettings) :
    KybMonitoringMetricsPort {

    private val observed = AtomicReference<MonitoringStats?>(null)

    @PostConstruct
    fun register() {
        gauge(ENABLED) { if (settings.enabled) 1.0 else 0.0 }
        gauge(REGISTERED) { observed.get()?.registered?.toDouble() ?: Double.NaN }
        gauge(PENDING) { observed.get()?.pending?.toDouble() ?: Double.NaN }
        gauge(PENDING_AGE) { observed.get()?.oldestPendingAgeSeconds?.toDouble() ?: Double.NaN }
        gauge(REVIEWS_OPEN) { observed.get()?.openReviews?.toDouble() ?: Double.NaN }
        gauge(REVIEW_AGE) { observed.get()?.oldestOpenReviewAgeSeconds?.toDouble() ?: Double.NaN }
        gauge(REFUSED) { observed.get()?.refused?.toDouble() ?: Double.NaN }
        gauge(EXITS_REQUIRED) { observed.get()?.exitsRequired?.toDouble() ?: Double.NaN }
        gauge(UNSCREENED) { observed.get()?.unscreenedApprovedCases?.toDouble() ?: Double.NaN }
        listOf(
            MonitoringRegistrationService.REGISTERED,
            MonitoringRegistrationService.WITHDRAWN,
        ).forEach { registry.counter(REGISTRATIONS, "outcome", it, "reason", NO_REASON) }
        // The registry's own answers to a wrong caller: the two an alert reads by increase.
        listOf("http_401", "http_403").forEach {
            registry.counter(REGISTRATIONS, "outcome", MonitoringRegistrationService.UNAUTHORISED, "reason", it)
        }
        MonitoringFindingOutcome.entries.forEach { registry.counter(FINDINGS, "outcome", it.tag) }
        listOf(OUTCOME_FOREIGN_OWNER, OUTCOME_IGNORED, OUTCOME_MALFORMED, OUTCOME_UNRECOGNISED)
            .forEach { registry.counter(FINDINGS, "outcome", it) }
    }

    private fun gauge(name: String, value: () -> Double) {
        Gauge.builder(name, value).strongReference(true).register(registry)
    }

    override fun registrationAttempted(outcome: String, reason: String) {
        registry.counter(REGISTRATIONS, "outcome", outcome, "reason", reason).increment()
    }

    override fun findingHandled(outcome: String) {
        registry.counter(FINDINGS, "outcome", outcome).increment()
    }

    override fun runFinished(outcome: String) {
        registry.counter(RUNS, "outcome", outcome).increment()
    }

    override fun observed(stats: MonitoringStats) {
        observed.set(stats)
    }

    companion object {
        const val ENABLED = "openbank.kyb.monitoring.enabled"
        const val REGISTRATIONS = "openbank.kyb.monitoring.registrations"
        const val REGISTERED = "openbank.kyb.monitoring.subjects.registered"
        const val PENDING = "openbank.kyb.monitoring.registrations.pending"
        const val PENDING_AGE = "openbank.kyb.monitoring.registrations.oldest_pending_age_seconds"
        const val FINDINGS = "openbank.kyb.monitoring.findings"
        const val REVIEWS_OPEN = "openbank.kyb.monitoring.reviews.open"
        const val REVIEW_AGE = "openbank.kyb.monitoring.reviews.oldest_open_age_seconds"
        const val REFUSED = "openbank.kyb.monitoring.registrations.refused"
        const val EXITS_REQUIRED = "openbank.kyb.monitoring.exits.required"
        const val UNSCREENED = "openbank.kyb.monitoring.cases.unscreened"
        const val RUNS = "openbank.kyb.monitoring.runs"

        const val NO_REASON = "none"
        const val OUTCOME_FOREIGN_OWNER = "foreign_owner"
        const val OUTCOME_IGNORED = "ignored"
        const val OUTCOME_MALFORMED = "malformed"
        const val OUTCOME_UNRECOGNISED = "unrecognised"
    }
}
