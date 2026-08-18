// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import java.math.BigDecimal
import java.util.UUID

/** Fraud verdict contract (ADR-0084 §1). In SHADOW the use-case logs it and proceeds regardless. */
enum class FraudVerdict { ALLOW, CHALLENGE, REVIEW, DECLINE }

/** What the payment boundary knows when it asks the fraud scorer for a verdict. */
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

/**
 * Outbound port to the fraud-service synchronous scorer (`POST /api/v1/fraud/score`, ADR-0084 §1),
 * called alongside the ADR-0032 sanctions/AML screening.
 *
 * SHADOW phase (ADR-0084 §4.1): the adapter is **fail-OPEN** — it never blocks or holds the payment.
 * On any outage or fault it returns an [FraudVerdict.ALLOW] outcome so there is zero customer impact
 * while the reference fraud-rate baseline (PSD2 RTS Art. 18) is established. The verdict is logged +
 * metered (fraud-service records its own `openbank.fraud.scores` counter + an immutable audit row);
 * the use-case does NOT act on it until a later challenge/enforce phase.
 */
interface FraudScoringPort {
    suspend fun score(command: FraudScoreCommand): FraudScoreOutcome
}
