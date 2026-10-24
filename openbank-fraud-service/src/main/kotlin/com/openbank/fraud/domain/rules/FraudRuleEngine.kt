// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.rules

import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.ScoreRequest

/**
 * A single deterministic, versioned fraud rule (ADR-0084 §3 — rules first, ML later). Rule LOGIC is
 * code-reviewed, never a UI-editable engine, so every change to it is a money-path PR. The money
 * thresholds the amount rules compare against are NOT code: they arrive as the active
 * [FraudThresholdSet] (ADR-0347), versioned data activated four-eyes at runtime.
 */
fun interface FraudRule {
    /**
     * Returns a partial contribution to the score, or null when the rule does not fire. [thresholds]
     * is null when no threshold set is active; a rule that needs one then fails closed.
     */
    fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit?
}

/** The outcome of one rule firing: a score delta, the implied verdict and a human-readable reason. */
data class RuleHit(val scoreDelta: Int, val verdict: FraudVerdict, val reason: String)

/**
 * Phase 1 baseline rule: always ALLOW, contributing nothing.
 *
 * Phase 2 (ADR-0084 §3) adds velocity cap rules that read rolling window counters populated by the
 * async Kafka signal plane ([VelocityH1ReviewRule], [VelocityH24ReviewRule]); v3 adds amount-based
 * rules ([LargeSingleTransactionReviewRule], [VelocityH1HighValueReviewRule]); v4 adds the
 * new-payee combination rule ([NewPayeeHighAmountReviewRule]) originally deferred from the roadmap.
 * Each new rule is its own [FraudRule] added to [FraudRuleEngine.RULES] and bumps
 * [FraudRuleEngine.RULE_VERSION].
 */
object BaselineAllowRule : FraudRule {
    override fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit =
        RuleHit(scoreDelta = 0, verdict = FraudVerdict.ALLOW, reason = "baseline-allow")
}

/**
 * Phase 2 — ADR-0084 §3: REVIEW when the rolling 1-hour transaction count for this account reaches
 * 10 or more (inclusive). Fires only when the velocity counter is populated (non-zero); a missing
 * aggregate (counter = 0) means no signal has arrived yet — the rule is silent, not mis-firing.
 */
object VelocityH1ReviewRule : FraudRule {
    private const val H1_CAP = 10L

    override fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit? {
        if (request.velocityH1Count < H1_CAP) return null
        return RuleHit(scoreDelta = 30, verdict = FraudVerdict.REVIEW, reason = "velocity-h1-cap")
    }
}

/**
 * Phase 2 — ADR-0084 §3: REVIEW when the rolling 24-hour transaction count for this account reaches
 * 50 or more (inclusive). Same silent-on-zero contract as [VelocityH1ReviewRule].
 */
object VelocityH24ReviewRule : FraudRule {
    private const val H24_CAP = 50L

    override fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit? {
        if (request.velocityH24Count < H24_CAP) return null
        return RuleHit(scoreDelta = 20, verdict = FraudVerdict.REVIEW, reason = "velocity-h24-cap")
    }
}

/**
 * Phase 2 v3 — ADR-0084 §3 ("high-amount" single-transaction heuristic from the roadmap).
 * REVIEW when a single transaction's amount reaches the large-transaction threshold for its
 * currency, regardless of account velocity history. Uses only [ScoreRequest.amount]/[ScoreRequest.currency],
 * present on every request since Phase 1 — no new signal needed.
 *
 * **Per-currency, not a single global figure** (fixed after adversarial review of the initial PR:
 * a currency-neutral raw-amount threshold let a large EUR payment sail under a CZK-calibrated cap —
 * CZK and EUR differ by roughly 25x). The figures live in the active [FraudThresholdSet] under
 * [FraudThresholdSet.LARGE_SINGLE_TRANSACTION] (ADR-0347); version 1 of that set is the first-pass
 * calibration these rules shipped with as code constants, seeded unchanged by `V9`.
 *
 * **Fails CLOSED for an unmapped currency** (REVIEW fires unconditionally), matching the existing
 * fail-closed convention elsewhere in the repo (e.g. `WaiverEvaluator`): what the rule cannot safely
 * evaluate is flagged for a human, never silently waived through. No active threshold set at all is
 * the same case for every currency.
 */
object LargeSingleTransactionReviewRule : FraudRule {
    override fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit? {
        val threshold = thresholds?.thresholdFor(FraudThresholdSet.LARGE_SINGLE_TRANSACTION, request.currency)
            ?: return RuleHit(
                scoreDelta = 25,
                verdict = FraudVerdict.REVIEW,
                reason = "large-single-transaction-unmapped-currency",
            )
        if (request.amount < threshold) return null
        return RuleHit(scoreDelta = 25, verdict = FraudVerdict.REVIEW, reason = "large-single-transaction")
    }
}

/**
 * Phase 2 v3 — ADR-0084 §3: REVIEW when the rolling 1-hour *transacted amount* (not just count) for
 * this account/currency bucket reaches the threshold for its currency. Complements
 * [VelocityH1ReviewRule] (count-based): a burst of many small transactions is caught by the count
 * rule, while a smaller number of high-value transactions within the hour — which would not trip the
 * count cap — is caught here. Reads [ScoreRequest.velocityH1TotalAmount], sourced from the same
 * `velocity_aggregates` row the H1 count comes from (no new signal or migration).
 *
 * **Per-currency, not a single global figure** — same cross-currency fix and disclosure as
 * [LargeSingleTransactionReviewRule]; see that rule's doc for the full rationale.
 *
 * Silent when the total is zero (no signal yet) for a *mapped* currency — same contract as the
 * count-based velocity rules. **Fails CLOSED for an unmapped currency** (REVIEW fires
 * unconditionally, even at zero) — an unmapped currency means the rule cannot tell whether a real
 * velocity signal is small or simply absent, so it does not get the silent-on-zero benefit of the
 * doubt.
 */
object VelocityH1HighValueReviewRule : FraudRule {
    override fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit? {
        val threshold = thresholds?.thresholdFor(FraudThresholdSet.VELOCITY_H1_AMOUNT, request.currency)
            ?: return RuleHit(
                scoreDelta = 35,
                verdict = FraudVerdict.REVIEW,
                reason = "velocity-h1-amount-cap-unmapped-currency",
            )
        if (request.velocityH1TotalAmount < threshold) return null
        return RuleHit(scoreDelta = 35, verdict = FraudVerdict.REVIEW, reason = "velocity-h1-amount-cap")
    }
}

/**
 * Phase 2 v4 — ADR-0084 §3 ("new-payee + high-amount combination" from the original roadmap,
 * deferred at Phase-1 launch for lack of a payee-history signal). REVIEW when [ScoreRequest.isNewPayee]
 * is true (accountId has never paid counterpartyId before, per the payee_history signal plane) AND
 * the amount exceeds a threshold for its currency — deliberately **notably lower** than
 * [LargeSingleTransactionReviewRule]'s: a first-ever payment to a never-seen payee is inherently
 * higher-risk than the same amount going to an established payee, which is the entire point of this
 * rule existing separately from the plain amount rule.
 *
 * **Per-currency, not a single global figure** — same cross-currency rationale and disclosure as
 * [LargeSingleTransactionReviewRule]; see that rule's doc for the full history.
 *
 * Silent when `isNewPayee` is false (established payee) regardless of amount — that case is already
 * covered by [LargeSingleTransactionReviewRule] at its own (higher) threshold. **Fails CLOSED for an
 * unmapped currency** whenever `isNewPayee` is true, matching the fail-closed convention of the
 * other amount-based rules: an unmapped currency means the rule cannot safely evaluate the amount,
 * so a genuinely new payee is flagged for a human rather than silently waived through.
 *
 * The figures live in the active [FraudThresholdSet] under [FraudThresholdSet.NEW_PAYEE_HIGH_AMOUNT]
 * (ADR-0347). Version 1 seeds them at roughly half of [LargeSingleTransactionReviewRule]'s, the
 * starting point these rules shipped with as code constants.
 */
object NewPayeeHighAmountReviewRule : FraudRule {
    override fun evaluate(request: ScoreRequest, thresholds: FraudThresholdSet?): RuleHit? {
        if (!request.isNewPayee) return null
        val threshold = thresholds?.thresholdFor(FraudThresholdSet.NEW_PAYEE_HIGH_AMOUNT, request.currency)
            ?: return RuleHit(
                scoreDelta = 30,
                verdict = FraudVerdict.REVIEW,
                reason = "new-payee-high-amount-unmapped-currency",
            )
        if (request.amount < threshold) return null
        return RuleHit(scoreDelta = 30, verdict = FraudVerdict.REVIEW, reason = "new-payee-high-amount")
    }
}

/**
 * Deterministic, versioned rule engine. Evaluates every rule in [RULES] over the request, sums the
 * score, and takes the most severe verdict any rule returned. Pure — no framework imports, fully
 * unit-testable in isolation from transport/persistence (ADR-0002).
 */
object FraudRuleEngine {

    /** Pin the rule-set version into every [FraudScore] for the audit trail. Bump on any rule change. */
    const val RULE_VERSION: String = "v4"

    /**
     * Ordered rule set. Phase 1 had the single [BaselineAllowRule] stub; Phase 2 added velocity cap
     * rules reading rolling window counters from the async Kafka signal plane (ADR-0084 §2); v3 added
     * amount-based rules ([LargeSingleTransactionReviewRule], [VelocityH1HighValueReviewRule]); v4
     * adds the new-payee combination rule ([NewPayeeHighAmountReviewRule]).
     */
    private val RULES: List<FraudRule> = listOf(
        BaselineAllowRule,
        VelocityH1ReviewRule,
        VelocityH24ReviewRule,
        LargeSingleTransactionReviewRule,
        VelocityH1HighValueReviewRule,
        NewPayeeHighAmountReviewRule,
    )

    /** Verdict severity ordering — the engine returns the most severe verdict any rule produced. */
    private val SEVERITY: List<FraudVerdict> =
        listOf(FraudVerdict.ALLOW, FraudVerdict.CHALLENGE, FraudVerdict.REVIEW, FraudVerdict.DECLINE)

    /**
     * Score [request] against the rules, reading money thresholds from [thresholds] — the set active
     * at the moment of scoring, resolved by the caller (ADR-0347).
     *
     * A null [thresholds] means no set is active, which no healthy deployment reaches (`V9` seeds
     * version 1). It fails CLOSED as REVIEW, never DECLINE: every amount rule sees an unmapped
     * currency and flags for a human, and [THRESHOLDS_UNAVAILABLE] names the actual cause on the
     * audit row so an analyst is not left reading "unmapped currency" for CZK.
     */
    fun score(request: ScoreRequest, thresholds: FraudThresholdSet?): FraudScore {
        val ruleHits = RULES.mapNotNull { it.evaluate(request, thresholds) }
        val hits = if (thresholds == null) ruleHits + THRESHOLDS_UNAVAILABLE_HIT else ruleHits
        val totalScore = hits.sumOf { it.scoreDelta }
        val verdict = hits.map { it.verdict }.maxByOrNull { SEVERITY.indexOf(it) } ?: FraudVerdict.ALLOW
        val reasons = hits.map { it.reason }
        return FraudScore(
            verdict = verdict,
            score = totalScore,
            reasons = reasons,
            ruleVersion = RULE_VERSION,
            thresholdSetVersion = thresholds?.version,
        )
    }

    /** Reason recorded when no threshold set is active (see [score]). */
    const val THRESHOLDS_UNAVAILABLE: String = "threshold-set-unavailable"

    private val THRESHOLDS_UNAVAILABLE_HIT =
        RuleHit(scoreDelta = 0, verdict = FraudVerdict.REVIEW, reason = THRESHOLDS_UNAVAILABLE)
}
