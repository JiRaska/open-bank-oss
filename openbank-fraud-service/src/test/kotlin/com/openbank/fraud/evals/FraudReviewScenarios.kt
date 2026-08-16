// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.evals

import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.ScoreRequest
import java.math.BigDecimal
import java.util.UUID

/**
 * Fraud review scenario pack (issue #4463; ADR-0148 evals gate; ADR-0175 §5 synthetic data).
 *
 * Synthetic transactions with **known ground truth**, asserted against the real
 * [com.openbank.fraud.domain.rules.FraudRuleEngine] — the exact deterministic domain logic that
 * decides both the score's verdict and, via `verdict == REVIEW`, whether a case is surfaced in
 * `GET /api/v1/fraud/review-queue` (`ScoreFraudUseCase.reviewQueue` reads `FraudScoreRepository
 * .findRecentByVerdict("REVIEW", …)` — no separate scoring happens at the queue boundary).
 *
 * **Never a live model.** ADR-0148/ADR-0235's record/replay rationale for the LLM evals gate
 * applies here even more directly: `FraudRuleEngine` is pure, versioned, framework-free Kotlin
 * (ADR-0002) with zero model dependency, so this pack asserts against that deterministic logic
 * directly — there is nothing to record/replay, and nothing that could drift with a model swap.
 *
 * **"Evidence" scoping note.** The issue that requested this pack (#4463) also asked to assert a
 * "compliance-officer draft [that] contains the right evidence links." That artifact does not
 * exist in this codebase today (confirmed by a full-repo search for draft/narrative generation
 * around the review queue) — the admin UI's fraud page is explicitly read-only, and case
 * resolution/narrative-writing is a human compliance decision outside this service (ADR-0227
 * maker-checker). What DOES exist, and is what a reviewer actually sees for a surfaced case, is
 * [com.openbank.fraud.domain.model.FraudScore.reasons] — the ordered list of rule ids that fired.
 * `expectedReasons` below asserts against that real evidence surface instead of a fictional one;
 * if/when a draft-generation feature ships, its evidence-link assembly extends this pack rather
 * than replacing it.
 *
 * **Synthetic and seeded (ADR-0175 §5 class 3 — synthetic/non-personal, never production PII).**
 * Every account/counterparty id here is a *name-based* UUID ([UUID.nameUUIDFromBytes]) derived
 * deterministically from a fixed seed string and the scenario id — not [UUID.randomUUID]. Two runs
 * of this file always produce byte-identical fixtures; there is no random draw anywhere in this
 * pack, so "seeded" here means reproducible-by-construction rather than merely non-flaky-in-practice.
 */
private const val SEED = "openbank-evals-fraud-review-v1"

private fun syntheticId(label: String): UUID = UUID.nameUUIDFromBytes("$SEED:$label".toByteArray())

/** One scenario: a synthetic [ScoreRequest] plus the ground truth the harness checks it against. */
data class FraudReviewScenario(
    val id: String,
    val description: String,
    val request: ScoreRequest,
    val expectedVerdict: FraudVerdict,
    /** Substrings that must all appear in [com.openbank.fraud.domain.model.FraudScore.reasons]. */
    val expectedReasons: List<String>,
)

/** `verdict == REVIEW` is exactly the review-queue surfacing rule — see the class doc above. */
val FraudReviewScenario.expectedSurfacedInQueue: Boolean
    get() = expectedVerdict == FraudVerdict.REVIEW

private fun request(
    label: String,
    amount: String,
    currency: String = "CZK",
    velocityH1Count: Long = 0,
    velocityH24Count: Long = 0,
    velocityH1TotalAmount: String = "0",
    isNewPayee: Boolean = false,
) = ScoreRequest(
    amount = BigDecimal(amount),
    currency = currency,
    rail = "SEPA_INSTANT",
    accountId = syntheticId("account-$label"),
    counterpartyId = syntheticId("counterparty-$label"),
    velocityH1Count = velocityH1Count,
    velocityH24Count = velocityH24Count,
    velocityH1TotalAmount = BigDecimal(velocityH1TotalAmount),
    isNewPayee = isNewPayee,
)

val FRAUD_REVIEW_SCENARIOS: List<FraudReviewScenario> = listOf(
    FraudReviewScenario(
        id = "ordinary-payment-allows",
        description = "A routine low-value payment to an established payee never surfaces in the review queue.",
        request = request(label = "ordinary-payment-allows", amount = "1250.00"),
        expectedVerdict = FraudVerdict.ALLOW,
        expectedReasons = listOf("baseline-allow"),
    ),
    FraudReviewScenario(
        id = "velocity-h1-burst-surfaces",
        description = "10 transactions within the rolling 1h window trips the count-velocity rule and surfaces.",
        request = request(label = "velocity-h1-burst-surfaces", amount = "500.00", velocityH1Count = 10),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("velocity-h1-cap"),
    ),
    FraudReviewScenario(
        id = "velocity-h24-burst-surfaces",
        description = "50 transactions within the rolling 24h window trips the count-velocity rule and surfaces.",
        request = request(label = "velocity-h24-burst-surfaces", amount = "500.00", velocityH24Count = 50),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("velocity-h24-cap"),
    ),
    FraudReviewScenario(
        id = "large-single-transaction-czk-surfaces",
        description = "A single CZK transaction at the large-transaction threshold surfaces regardless of velocity.",
        request = request(label = "large-single-transaction-czk-surfaces", amount = "500000"),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("large-single-transaction"),
    ),
    FraudReviewScenario(
        id = "large-single-transaction-eur-surfaces",
        description = "The EUR large-transaction threshold is its own calibration, not a currency-blind copy of CZK.",
        request = request(label = "large-single-transaction-eur-surfaces", amount = "20000", currency = "EUR"),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("large-single-transaction"),
    ),
    FraudReviewScenario(
        id = "unmapped-currency-fails-closed",
        description = "An unmapped currency fails CLOSED to REVIEW even at a trivial amount (fail-closed convention).",
        request = request(label = "unmapped-currency-fails-closed", amount = "1.00", currency = "USD"),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("large-single-transaction-unmapped-currency"),
    ),
    FraudReviewScenario(
        id = "velocity-h1-high-value-surfaces",
        description = "A rolling-1h transacted amount at the CZK threshold surfaces even with a small single amount.",
        request = request(
            label = "velocity-h1-high-value-surfaces",
            amount = "5000.00",
            velocityH1Count = 3,
            velocityH1TotalAmount = "1000000",
        ),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("velocity-h1-amount-cap"),
    ),
    FraudReviewScenario(
        id = "new-payee-high-amount-surfaces",
        description = "A first-ever payment to a never-seen payee surfaces at a lower threshold than an " +
            "established payee.",
        request = request(label = "new-payee-high-amount-surfaces", amount = "250000", isNewPayee = true),
        expectedVerdict = FraudVerdict.REVIEW,
        expectedReasons = listOf("new-payee-high-amount"),
    ),
    FraudReviewScenario(
        id = "same-amount-established-payee-allows",
        description = "The exact amount that surfaces for a new payee does not surface for an established one.",
        request = request(label = "same-amount-established-payee-allows", amount = "250000", isNewPayee = false),
        expectedVerdict = FraudVerdict.ALLOW,
        expectedReasons = listOf("baseline-allow"),
    ),
    FraudReviewScenario(
        id = "just-below-large-transaction-threshold-allows",
        description = "One currency unit below the CZK large-transaction threshold does not surface (boundary proof).",
        request = request(label = "just-below-large-transaction-threshold-allows", amount = "499999.99"),
        expectedVerdict = FraudVerdict.ALLOW,
        expectedReasons = listOf("baseline-allow"),
    ),
)
