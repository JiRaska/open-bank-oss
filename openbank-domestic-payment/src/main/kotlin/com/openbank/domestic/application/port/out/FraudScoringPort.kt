// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

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

interface FraudScoringPort {
    suspend fun score(command: FraudScoreCommand): FraudScoreOutcome
}
