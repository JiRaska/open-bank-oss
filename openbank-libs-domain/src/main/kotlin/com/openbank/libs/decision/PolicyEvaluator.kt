// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.decision

import java.security.MessageDigest
import java.time.LocalDate

/** The application as the evaluator sees it: typed facts plus the decision date. */
data class PolicyApplication(val attributes: Map<PolicyAttribute, PolicyValue>, val asOf: LocalDate)

/**
 * Pure, deterministic credit policy evaluator (ADR-0213). Zero framework imports,
 * zero I/O — policy tables arrive compiled in memory (ADR-0218 D3) and evaluation is
 * O(rules). Table order is fixed: EXCLUSION (first match declines) → ELIGIBILITY and
 * AFFORDABILITY (all rules must pass) → PRICING_BAND (first matching band, never
 * flips a decline). Everything fail-closed: a missing input, an unevaluable rule or
 * a missing/expired table yields REFER — the only path to APPROVE is every hard rule
 * evaluated and passed.
 */
object PolicyEvaluator {

    fun evaluate(application: PolicyApplication, bundle: PolicyBundle): PolicyDecision {
        val hash = inputSnapshotHash(application.attributes)
        val matched = mutableListOf<String>()
        val versions = mutableMapOf<PolicyTableKind, Int>()
        var band: String? = null

        for (kind in PolicyTableKind.entries) {
            val table = bundle.active(kind, application.asOf)
                ?: return refer(
                    versions,
                    matched,
                    hash,
                    DecisionReason(PolicyReasonCode.POLICY_TABLE_MISSING, detail = kind.name),
                )
            versions[kind] = table.version
            when (val outcome = evaluateTable(kind, table, application, matched)) {
                is TableOutcome.Referred -> return refer(versions, matched, hash, outcome.reason)
                is TableOutcome.Declined ->
                    return PolicyDecision.Decline(
                        PolicyEvaluation(versions.toMap(), matched.toList(), outcome.reasons, hash),
                    )
                is TableOutcome.Passed -> if (outcome.band != null) band = outcome.band
            }
        }

        return band?.let {
            PolicyDecision.Approve(
                priceBand = it,
                evaluation = PolicyEvaluation(versions.toMap(), matched.toList(), emptyList(), hash),
            )
        } ?: refer(versions, matched, hash, DecisionReason(PolicyReasonCode.PRICING_BAND_UNMATCHED))
    }

    /** Deterministic SHA-256 over the canonical attribute rendering (ADR-0214 D2). */
    fun inputSnapshotHash(attributes: Map<PolicyAttribute, PolicyValue>): String {
        val canonical = attributes.entries
            .sortedBy { it.key.name }
            .joinToString("|") { "${it.key.name}=${it.value.render()}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private sealed interface TableOutcome {
        data class Passed(val band: String? = null) : TableOutcome

        data class Referred(val reason: DecisionReason) : TableOutcome

        data class Declined(val reasons: List<DecisionReason>) : TableOutcome
    }

    private fun evaluateTable(
        kind: PolicyTableKind,
        table: PolicyTable,
        application: PolicyApplication,
        matched: MutableList<String>,
    ): TableOutcome {
        val failures = mutableListOf<DecisionReason>()
        for (rule in table.rules) {
            when (val outcome = evaluateRule(rule, application.attributes[rule.attribute])) {
                is RuleOutcome.NotEvaluable -> return TableOutcome.Referred(outcome.reason)
                is RuleOutcome.Matched -> {
                    matched += rule.id
                    when (kind) {
                        PolicyTableKind.EXCLUSION ->
                            return TableOutcome.Declined(
                                listOf(DecisionReason(PolicyReasonCode.EXCLUSION_MATCHED, rule.id, rule.detail)),
                            )
                        PolicyTableKind.PRICING_BAND -> return TableOutcome.Passed(band = rule.band)
                        else -> Unit
                    }
                }
                is RuleOutcome.NotMatched ->
                    if (kind == PolicyTableKind.ELIGIBILITY || kind == PolicyTableKind.AFFORDABILITY) {
                        failures += DecisionReason(
                            if (kind == PolicyTableKind.ELIGIBILITY) {
                                PolicyReasonCode.ELIGIBILITY_FAILED
                            } else {
                                PolicyReasonCode.AFFORDABILITY_FAILED
                            },
                            rule.id,
                            rule.detail,
                        )
                    }
            }
        }
        return if (failures.isNotEmpty()) TableOutcome.Declined(failures) else TableOutcome.Passed()
    }

    private sealed interface RuleOutcome {
        data object Matched : RuleOutcome

        data object NotMatched : RuleOutcome

        data class NotEvaluable(val reason: DecisionReason) : RuleOutcome
    }

    private fun evaluateRule(rule: PolicyRule, value: PolicyValue?): RuleOutcome {
        if (value == null) {
            return RuleOutcome.NotEvaluable(
                DecisionReason(PolicyReasonCode.INPUT_MISSING, rule.id, rule.attribute.name),
            )
        }
        return when (rule.operator) {
            PolicyOperator.IN, PolicyOperator.NOT_IN -> evaluateMembership(rule, value)
            else -> evaluateComparison(rule, value)
        }
    }

    private fun evaluateMembership(rule: PolicyRule, value: PolicyValue): RuleOutcome {
        if (value !is PolicyValue.Text || rule.values.isEmpty()) return notEvaluable(rule)
        val member = rule.values.any { it.equals(value.value, ignoreCase = true) }
        val matched = if (rule.operator == PolicyOperator.IN) member else !member
        return if (matched) RuleOutcome.Matched else RuleOutcome.NotMatched
    }

    private fun evaluateComparison(rule: PolicyRule, value: PolicyValue): RuleOutcome = when (value) {
        is PolicyValue.Numeric -> evaluateNumeric(rule, value.value)
        is PolicyValue.Text -> evaluateText(rule, value.value)
    }

    private fun evaluateNumeric(rule: PolicyRule, actual: java.math.BigDecimal): RuleOutcome {
        val threshold = rule.threshold ?: return notEvaluable(rule)
        val matched = when (rule.operator) {
            PolicyOperator.GT -> actual > threshold
            PolicyOperator.GTE -> actual >= threshold
            PolicyOperator.LT -> actual < threshold
            PolicyOperator.LTE -> actual <= threshold
            PolicyOperator.EQ -> actual.compareTo(threshold) == 0
            PolicyOperator.NEQ -> actual.compareTo(threshold) != 0
            else -> return notEvaluable(rule)
        }
        return if (matched) RuleOutcome.Matched else RuleOutcome.NotMatched
    }

    private fun evaluateText(rule: PolicyRule, actual: String): RuleOutcome {
        val expected = rule.values.singleOrNull() ?: return notEvaluable(rule)
        val equal = actual.equals(expected, ignoreCase = true)
        val matched = when (rule.operator) {
            PolicyOperator.EQ -> equal
            PolicyOperator.NEQ -> !equal
            else -> return notEvaluable(rule)
        }
        return if (matched) RuleOutcome.Matched else RuleOutcome.NotMatched
    }

    private fun notEvaluable(rule: PolicyRule): RuleOutcome.NotEvaluable =
        RuleOutcome.NotEvaluable(DecisionReason(PolicyReasonCode.RULE_NOT_EVALUABLE, rule.id, rule.attribute.name))

    private fun refer(
        versions: Map<PolicyTableKind, Int>,
        matched: List<String>,
        hash: String,
        reason: DecisionReason,
    ): PolicyDecision.Refer =
        PolicyDecision.Refer(PolicyEvaluation(versions.toMap(), matched.toList(), listOf(reason), hash))
}
