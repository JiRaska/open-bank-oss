// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.metrics

import com.openbank.statement.application.port.out.CloseMetricsPort
import com.openbank.statement.domain.model.CloseFailureReason
import com.openbank.statement.domain.model.CloseRunStatus
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Micrometer adapter for the close cadence (ADR-0069 D3 / issue #470). Emits Prometheus counters the
 * ServiceMonitor scrapes and the PrometheusRule alerts on:
 *  - `openbank_statement_close_runs_total{status}`     — a run finished
 *  - `openbank_statement_close_pockets_total{outcome}` — a pocket-month closed/skipped
 *  - `openbank_statement_close_failures_total{reason}` — a pocket-month failed
 *
 * Counters are registered lazily on first use (Micrometer dedupes by name+tags). The cadence-stalled
 * *gauge* (`openbank_statement_close_last_run_timestamp_seconds`) lives in [CloseLastRunGauge], which
 * derives it from the persisted run log so it is retention- and restart-independent.
 */
@ApplicationScoped
class CloseMetricsAdapter @Inject constructor(private val registry: MeterRegistry) : CloseMetricsPort {

    override fun runFinished(status: CloseRunStatus) =
        registry.counter("openbank_statement_close_runs_total", "status", status.name).increment()

    override fun pocketClosed() =
        registry.counter("openbank_statement_close_pockets_total", "outcome", "closed").increment()

    override fun pocketSkipped() =
        registry.counter("openbank_statement_close_pockets_total", "outcome", "skipped").increment()

    override fun pocketFailed(reason: CloseFailureReason) =
        registry.counter("openbank_statement_close_failures_total", "reason", reason.name).increment()
}
