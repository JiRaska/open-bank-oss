// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.model

import java.math.BigDecimal
import java.util.UUID

/**
 * The verdict contract (ADR-0084). Output of fraud scoring is a *verdict*, not a raw score the
 * caller must interpret. The four payment-execution surfaces honour the verdict per the rollout
 * phase (shadow → challenge → enforce); Phase 1 here is inert and not yet wired into any surface.
 *
 * - [ALLOW]     — proceed.
 * - [CHALLENGE] — require (re-)SCA / step-up before execution.
 * - [REVIEW]    — hold the payment, open an operator review case (four-eyes release, ADR-0068).
 * - [DECLINE]   — reject with a customer-safe reason code.
 */
enum class FraudVerdict {
    ALLOW,
    CHALLENGE,
    REVIEW,
    DECLINE,
}

/**
 * Payment context handed to the scorer. Pure value object — the four payment surfaces populate it
 * after SCA, alongside the ADR-0032 sanctions/AML screening call. Phase 2 enriches this with
 * per-account rolling velocity counters read from the async signal plane (ADR-0084 §2).
 */
data class ScoreRequest(
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID? = null,
    val counterpartyId: UUID? = null,
    // ADR-0084 §2: rolling velocity counters read from the async signal plane before scoring
    val velocityH1Count: Long = 0,
    val velocityH24Count: Long = 0,
)

/**
 * Scoring result. [score] is a deterministic numeric risk score (0 = no risk); [verdict] is the
 * mapped contract outcome; [reasons] explain which rules fired; [ruleVersion] pins the exact
 * versioned rule set that produced this verdict for the audit trail and RTS Art. 18 baseline.
 */
data class FraudScore(val verdict: FraudVerdict, val score: Int, val reasons: List<String>, val ruleVersion: String)
