// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.model.FraudVerdict

/**
 * Outbound observability port (ADR-0002 / ADR-0084 §1). Scoring emits a verdict-tagged metric so the
 * shadow → challenge → enforce rollout is measurable from day one (what fraction would CHALLENGE /
 * DECLINE before any surface honours the verdict). Kept as a port — not a direct `MeterRegistry`
 * dependency in the use case — so the application layer stays free of the metrics framework and the
 * counter is exercised through a fake in unit tests.
 *
 * Implemented by [com.openbank.fraud.infrastructure.observability.FraudMetricsAdapter].
 */
interface FraudMetricsPort {

    /** Record one scoring decision, tagged by its [verdict] and the payment [rail]. */
    fun recordVerdict(verdict: FraudVerdict, rail: String)

    /**
     * Record one ADR-0139 phase-1 **shadow** ML score (`[0,1]`). This is the series that proves the
     * model is calibrated and safe before any enforce phase — it never reflects a honoured decision.
     */
    fun recordShadowScore(score: Double)

    /**
     * Record that a transaction signal was recognised as ALREADY APPLIED to [aggregate] and was
     * therefore not applied again (issue #5789). This is the only series that makes redelivery
     * visible at all: a suppressed replay writes no row, logs nothing and changes no counter, so
     * without this the guard working and the guard being absent look identical from outside the
     * database. Read it as "how often is the dedupe guard load-bearing" — a sustained non-zero rate
     * is normal after a rebalance or a DLQ replay; a permanently flat zero while redeliveries are
     * known to happen means the guard is not being reached.
     */
    fun recordSignalReplaySuppressed(aggregate: String)

    /**
     * Record that a transaction signal arrived with **no `occurredAt`** and that processing time was
     * substituted for it (issue #6044). Every velocity bucket and payee-history row written from
     * such a signal asserts a business time nobody measured, and — because the bucket is part of the
     * primary key — a replay of that same signal will not land in the same row, so the redelivery
     * guard cannot reach it. The substitution is otherwise completely silent: it writes a normal row
     * and logs nothing, exactly like the ingest-time substitution #3883 found in the audit
     * consumer. This counter is the only thing that distinguishes "every producer sends an event
     * time" from "we have been inventing them". `openbank.transactions.transaction.initiated`
     * declares `occurredAt` as required, so the expected value is a flat ZERO; any non-zero rate is
     * a producer defect, not a tolerable degradation.
     */
    fun recordSignalMissingEventTime()
}
