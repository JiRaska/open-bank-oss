// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.decision

/** Machine-readable reason codes — the adverse-action contract (ADR-0213 D1/D2, ADR-0142). */
enum class PolicyReasonCode {
    INPUT_MISSING,
    RULE_NOT_EVALUABLE,
    POLICY_TABLE_MISSING,
    EXCLUSION_MATCHED,
    ELIGIBILITY_FAILED,
    AFFORDABILITY_FAILED,
    PRICING_BAND_UNMATCHED,
}

/** A single machine-readable reason; [ruleId] links it to the exact rule that fired. */
data class DecisionReason(val code: PolicyReasonCode, val ruleId: String? = null, val detail: String = "")

/** The evidentiary metadata of one evaluation (ADR-0214): versions, matched rules, input hash. */
data class PolicyEvaluation(
    val policyVersions: Map<PolicyTableKind, Int>,
    val matchedRuleIds: List<String>,
    val reasons: List<DecisionReason>,
    val inputSnapshotHash: String,
)

/**
 * The decision output contract (ADR-0213 D1): never a bare score — always an outcome
 * plus reasons, matched rule ids, policy versions and the input snapshot hash, so a
 * historic decision replays deterministically against its pinned policy.
 */
sealed interface PolicyDecision {
    val evaluation: PolicyEvaluation

    data class Approve(val priceBand: String, override val evaluation: PolicyEvaluation) : PolicyDecision

    data class Refer(override val evaluation: PolicyEvaluation) : PolicyDecision

    data class Decline(override val evaluation: PolicyEvaluation) : PolicyDecision
}
