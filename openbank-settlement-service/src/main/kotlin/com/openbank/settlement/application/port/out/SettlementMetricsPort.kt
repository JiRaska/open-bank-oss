// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.port.out

import java.math.BigDecimal
import java.time.Duration

/**
 * Outbound observability port (ADR-0002 / ADR-0077, issue #5705). settlement-service is a declared
 * money-path service that emitted **no application metric of any kind** — the pod was scraped, the
 * workload was Ready, and there was simply no series to query. Nothing could distinguish
 * "settlement is running normally" from "settlement has booked nothing for six hours".
 *
 * The failure modes these meters make visible are all silent by construction:
 *
 *  - the saga is driven by Temporal, which **retries** a failing activity and only then compensates;
 *    a debit that keeps failing shows up as neither an HTTP error nor a latency change on this
 *    service, because the originating request already returned 202 with the PENDING row;
 *  - a compensated saga *moves customer money and moves it back*, ending in REJECTED — a perfectly
 *    healthy-looking terminal state that no error rate reports;
 *  - a worker that never picks work up at all (no Temporal worker registered, wrong task queue)
 *    leaves every settlement in PENDING forever, and produces no errors whatsoever.
 *
 * Kept as a port rather than a direct `MeterRegistry` dependency so the application layer stays free
 * of the metrics framework, and so the counters are exercised through the real adapter (over a real
 * Prometheus registry) in unit tests.
 *
 * Implemented by [com.openbank.settlement.infrastructure.observability.SettlementMetricsAdapter].
 */
interface SettlementMetricsPort {

    /**
     * Record one origination request. [outcome] separates a genuinely new PENDING row from an
     * idempotent replay of a key already seen — the name says *accepted*, not settled: at this
     * point no money has moved and the saga has only just been asked to start.
     */
    fun settlementOriginated(currency: String, outcome: OriginationOutcome)

    /**
     * Record one settlement-saga activity attempt. [outcome] is `FAILED` for an attempt that threw —
     * Temporal will retry it, so a non-zero failure rate is not by itself an incident, but a
     * sustained one is the only warning before compensation runs.
     */
    fun sagaStep(step: SettlementStep, outcome: SettlementStepOutcome)

    /**
     * Record a settlement that reached BOOKED. [cycleDuration] is measured from origination to the
     * ledger booking, i.e. the whole customer-visible settlement latency, not one activity's.
     */
    fun settlementBooked(currency: String, amount: BigDecimal, cycleDuration: Duration)

    /** Record a settlement that reached REJECTED after its compensations ran. */
    fun settlementRejected(currency: String, cycleDuration: Duration)
}

/** What an origination request actually did. A bounded set — safe as a metric tag. */
enum class OriginationOutcome {
    /** The idempotency key was not yet stored when the request arrived, so a row was created. */
    CREATED,

    /** The idempotency key resolved to an existing settlement; nothing new was created. */
    REPLAYED,
}

/**
 * The settlement-saga activities, forward legs and compensations alike. A bounded set — safe as a
 * metric tag, and deliberately mirrors the method names on
 * [com.openbank.settlement.application.workflow.SettlementActivities] so a series can be traced
 * back to exactly one call site.
 */
enum class SettlementStep {
    DEBIT,
    CREDIT,
    LEDGER_BOOK,
    REVERSE_DEBIT,
    REVERSE_CREDIT,
    REVERSE_LEDGER_BOOK,
    REJECT,
}

/** Terminal outcome of one activity attempt. */
enum class SettlementStepOutcome {
    /** The activity completed and the status transition was persisted. */
    COMPLETED,

    /** The activity threw. Temporal will retry it, and compensate once the attempts are exhausted. */
    FAILED,
}
