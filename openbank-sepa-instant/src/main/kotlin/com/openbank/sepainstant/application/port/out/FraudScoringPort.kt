// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
)

// Uni<> variant: sepa-instant is fully reactive (Mutiny), so this port follows the same
// async contract as SanctionsScreeningPort rather than a suspend function.
interface FraudScoringPort {
    fun score(command: FraudScoreCommand): Uni<FraudScoreOutcome>
}
