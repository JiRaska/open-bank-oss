// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

/**
 * Vocabulary shared by the PR-path policy gate: the action under evaluation (`gh.pr.open` on
 * resource `github-pr`, charter tier `write_proposal`) and the denial reasons. The tool→tier
 * binding lives here, in code — the OPA query re-validates the (tier, resource) grant against
 * the charter data served by the sidecar, it does not trust the caller's tool name.
 */
internal object PrPathGateVocabulary {
    const val AGENT_ID = "flaky-test-hunter"
    const val TOOL_GH_PR_OPEN = "gh.pr.open"
    const val RESOURCE_GITHUB_PR = "github-pr"
    const val TIER_WRITE_PROPOSAL = "write_proposal"

    /** The PR writer is disabled by configuration. Deny without consulting either policy layer. */
    const val REASON_POLICY_GATE_DISABLED = "policy_gate_disabled"

    /** The packaged charter resource could not be loaded or parsed. Deny (fail closed). */
    const val REASON_CHARTER_UNAVAILABLE = "charter_unavailable"

    /** The charter loaded but does not grant this agent the (tier, resource) pair. */
    const val REASON_CHARTER_DENIED = "charter_denied"

    /** Prefix of a policy-driven denial; the OPA reason follows after ": ". */
    const val REASON_OPA_DENIED_PREFIX = "opa_denied"

    /** OPA sidecar unreachable, timed out, or returned an unparseable response. Deny (fail closed). */
    const val REASON_PDP_UNREACHABLE = "pdp_unreachable"
}

/**
 * ADR-0031 D9 phase 3: the outcome of the policy pre-flight gate in front of flaky-test-hunter's
 * one GitHub write path. [Denied] MUST veto the entire PR-opening operation — no branch creation,
 * no commit, no PR — and the workflow records the disposition as policy-blocked in the audit
 * payload; [Allowed] carries the branch name the writer would create so the audit event can name
 * it without re-deriving it.
 */
sealed interface PrPathDecision {
    data class Allowed(val prBranch: String) : PrPathDecision

    data class Denied(val reason: String) : PrPathDecision
}
