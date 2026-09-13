// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.port.out

import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.util.UUID

enum class FraudVerdict { ALLOW, CHALLENGE, REVIEW, DECLINE }

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

// Uni<> variant: sepa-instant is fully reactive (Mutiny), so this port follows the same
// async contract as SanctionsScreeningPort rather than a suspend function.
interface FraudScoringPort {
    fun score(command: FraudScoreCommand): Uni<FraudScoreOutcome>
}
