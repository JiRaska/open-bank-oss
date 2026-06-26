// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fraud.application.port.out

import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.ScoreRequest
import java.util.UUID

/**
 * Outbound persistence port (ADR-0002). Each scoring decision is persisted as an immutable audit
 * row — the reference fraud-rate dataset RTS Art. 18 needs and the evidence trail for every verdict.
 * Implemented by [com.openbank.fraud.infrastructure.persistence.FraudScoreRepositoryImpl].
 */
interface FraudScoreRepository {

    /** Persist one scoring decision (request context + verdict) and return its audit-row id. */
    suspend fun save(request: ScoreRequest, result: FraudScore): UUID
}
