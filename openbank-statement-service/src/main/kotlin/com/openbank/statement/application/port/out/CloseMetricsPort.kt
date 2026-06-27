// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.port.out

import com.openbank.statement.domain.model.CloseFailureReason
import com.openbank.statement.domain.model.CloseRunStatus

/**
 * Emits close-cadence observability signals (ADR-0069 D3 / issue #470). Kept as a port so the
 * application layer stays framework-free; the Micrometer adapter lives in infrastructure. These
 * counters drive the ServiceMonitor/PrometheusRule alerting that gates enabling the monthly cron.
 */
interface CloseMetricsPort {
    /** One per finished run, tagged by terminal status and trigger. */
    fun runFinished(status: CloseRunStatus)

    /** One per pocket-month closed. */
    fun pocketClosed()

    /** One per pocket-month skipped (already closed — idempotent no-op). */
    fun pocketSkipped()

    /** One per pocket-month failure, tagged by reason. */
    fun pocketFailed(reason: CloseFailureReason)
}
