// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.port.out

import java.math.BigDecimal
import java.util.UUID

/**
 * [UNKNOWN] is what the adapter reports when fraud-service answered with a verdict this rail does
 * not recognise, or with none at all (#4403). It is deliberately **not** [ALLOW]: a response that
 * could not be read says nothing about the payment, and an enforcing caller must never be able to
 * read it as a clean score. It is also not a synthetic outcome — fraud-service was reachable.
 */
enum class FraudVerdict { ALLOW, CHALLENGE, REVIEW, DECLINE, UNKNOWN }

data class FraudScoreCommand(
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID?,
    val counterpartyId: UUID?,
)

data class FraudScoreOutcome(
    val verdict: FraudVerdict,
    val score: Int,
    val ruleVersion: String,
    val reasons: List<String>,
    /**
     * `true` when this outcome was **invented by the adapter** because fraud-service could not be
     * reached, rather than returned by it (#4221). The fail-open fallback is an `ALLOW`, so without
     * this flag a total scoring outage is byte-for-byte identical to a clean payment at every layer
     * that reads the outcome. `ruleVersion == "unavailable"` was the only hint and is a magic string
     * no caller checked; this is the same claim as a field the type system can see.
     *
     * A caller must never treat a synthetic outcome as evidence of anything about the payment.
     */
    val synthetic: Boolean = false,
)

interface FraudScoringPort {
    suspend fun score(command: FraudScoreCommand): FraudScoreOutcome
}
