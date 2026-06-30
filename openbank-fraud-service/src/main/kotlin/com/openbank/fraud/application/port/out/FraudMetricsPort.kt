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
}
