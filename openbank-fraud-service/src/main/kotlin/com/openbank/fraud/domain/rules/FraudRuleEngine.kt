// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.domain.rules

import com.openbank.fraud.domain.model.FraudScore
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.ScoreRequest

/**
 * A single deterministic, versioned fraud rule (ADR-0084 §3 — rules first, ML later). Rules are
 * code-reviewed config, never a UI-editable engine, so every change is a money-path PR.
 */
fun interface FraudRule {
    /** Returns a partial contribution to the score, or null when the rule does not fire. */
    fun evaluate(request: ScoreRequest): RuleHit?
}

/** The outcome of one rule firing: a score delta, the implied verdict and a human-readable reason. */
data class RuleHit(val scoreDelta: Int, val verdict: FraudVerdict, val reason: String)

/**
 * Phase 1 baseline rule: always ALLOW, contributing nothing.
 *
 * Phase 2 (ADR-0084 §3) adds velocity cap rules that read rolling window counters populated by the
 * async Kafka signal plane ([VelocityH1ReviewRule], [VelocityH24ReviewRule]). Each new rule is its
 * own [FraudRule] added to [FraudRuleEngine.RULES] and bumps [FraudRuleEngine.RULE_VERSION].
 */
object BaselineAllowRule : FraudRule {
    override fun evaluate(request: ScoreRequest): RuleHit =
        RuleHit(scoreDelta = 0, verdict = FraudVerdict.ALLOW, reason = "baseline-allow")
}

/**
 * Phase 2 — ADR-0084 §3: REVIEW when the rolling 1-hour transaction count for this account reaches
 * 10 or more (inclusive). Fires only when the velocity counter is populated (non-zero); a missing
 * aggregate (counter = 0) means no signal has arrived yet — the rule is silent, not mis-firing.
 */
object VelocityH1ReviewRule : FraudRule {
    private const val H1_CAP = 10L

    override fun evaluate(request: ScoreRequest): RuleHit? {
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

    override fun evaluate(request: ScoreRequest): RuleHit? {
        if (request.velocityH24Count < H24_CAP) return null
        return RuleHit(scoreDelta = 20, verdict = FraudVerdict.REVIEW, reason = "velocity-h24-cap")
    }
}

/**
 * Deterministic, versioned rule engine. Evaluates every rule in [RULES] over the request, sums the
 * score, and takes the most severe verdict any rule returned. Pure — no framework imports, fully
 * unit-testable in isolation from transport/persistence (ADR-0002).
 */
object FraudRuleEngine {

    /** Pin the rule-set version into every [FraudScore] for the audit trail. Bump on any rule change. */
    const val RULE_VERSION: String = "v2"

    /**
     * Ordered rule set. Phase 1 had the single [BaselineAllowRule] stub; Phase 2 adds velocity cap
     * rules that read rolling window counters from the async Kafka signal plane (ADR-0084 §2).
     */
    private val RULES: List<FraudRule> = listOf(BaselineAllowRule, VelocityH1ReviewRule, VelocityH24ReviewRule)

    /** Verdict severity ordering — the engine returns the most severe verdict any rule produced. */
    private val SEVERITY: List<FraudVerdict> =
        listOf(FraudVerdict.ALLOW, FraudVerdict.CHALLENGE, FraudVerdict.REVIEW, FraudVerdict.DECLINE)

    fun score(request: ScoreRequest): FraudScore {
        val hits = RULES.mapNotNull { it.evaluate(request) }
        val totalScore = hits.sumOf { it.scoreDelta }
        val verdict = hits.map { it.verdict }.maxByOrNull { SEVERITY.indexOf(it) } ?: FraudVerdict.ALLOW
        val reasons = hits.map { it.reason }
        return FraudScore(
            verdict = verdict,
            score = totalScore,
            reasons = reasons,
            ruleVersion = RULE_VERSION,
        )
    }
}
