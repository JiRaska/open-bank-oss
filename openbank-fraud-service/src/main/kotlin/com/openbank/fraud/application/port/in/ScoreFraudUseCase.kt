// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fraud.application.port.`in`

import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest

/**
 * Inbound port (ADR-0002 hexagonal): score a payment intent and return a [FraudScore] verdict.
 * Implemented by [com.openbank.fraud.application.usecase.FraudScoringService] and driven by the
 * REST adapter. Phase 1 is inert — no payment surface calls it yet (ADR-0084 rollout: shadow first).
 */
interface ScoreFraudUseCase {
    suspend fun score(request: ScoreRequest): FraudScore
}
