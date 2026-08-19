// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import com.openbank.settlement.domain.model.SettlementStatus

/**
 * Outbound observability port (ADR-0002 / ADR-0084 §1) for the settlement saga.
 *
 * WHY IT EXISTS: settlement-service is money-path and emitted **nothing** — 20 Kotlin files, not one
 * reference to `MeterRegistry`, `DomainMetrics`, `@Counted` or `@Timed`, and no PrometheusRule of
 * its own. The one alert whose name contains "Settlement", `ClearingSettlementWindowMissed`, belongs
 * to clearing-service and queries `openbank_clearing_settlements_completed_total`. So there was no
 * signal anywhere that could distinguish "settlement is running normally" from "settlement has
 * booked nothing for six hours" — the pod is Ready, the namespace is scraped, and the series simply
 * did not exist (#5705).
 *
 * Kept as a port, not a direct `MeterRegistry` dependency in the saga, so the application layer
 * stays free of the metrics framework and the counters are exercised through a fake in unit tests.
 */
interface SettlementMetricsPort {

    /**
     * Record one settlement state transition. Every transition the saga performs passes through
     * here, compensations included, so `REVERSED` / `CREDITED_REVERSED` / `LEDGER_REVERSED` are
     * visible as their own series rather than folded into a failure count.
     */
    fun recordTransition(status: SettlementStatus)

    /** Record the outcome of an originate request. */
    fun recordOriginated(outcome: OriginateOutcome)

    /** Record the outcome of starting the settlement workflow. */
    fun recordWorkflowStart(outcome: WorkflowStartOutcome)
}

/**
 * What an originate request actually did.
 *
 * These are three distinct values on purpose, not a boolean. A "successful no-op" and a real
 * success sharing one flag is how a disabled push adapter reported every notification as delivered
 * (ADR-0252 phase 0): the row committed SENT, the outcome event announced a delivery that never
 * left the process, and no signal anywhere disagreed. An idempotent hit here is a *correct* outcome
 * and a *different* one — a sudden shift from CREATED to IDEMPOTENT_HIT means callers are retrying,
 * which is worth seeing and which a success rate cannot show.
 */
enum class OriginateOutcome {
    /** A new PENDING settlement row was written. */
    CREATED,

    /** The idempotency key resolved to an existing row; nothing was written. */
    IDEMPOTENT_HIT,

    /** A concurrent same-key create lost the primary-key race and adopted the winner's row. */
    CONCURRENT_RACE,
}

/** What starting the settlement workflow actually did. */
enum class WorkflowStartOutcome {
    /** A workflow run was started. */
    STARTED,

    /** A run for this settlement id was already in flight; the call was an idempotent no-op. */
    ALREADY_RUNNING,

    /** The row was already terminal, so no workflow was started. */
    NOT_STARTED_TERMINAL,
}
