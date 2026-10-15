// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

/**
 * ADR-0031 D9 phase 4: the per-agent binding that parameterises the shared policy gate.
 * Phase 3 hardcoded these four values for flaky-test-hunter inside its gate classes; the
 * generalised gate takes them from here so any agent — a development agent opening GitHub PRs
 * today, a money-path repair agent acting on `payment-instruction` tomorrow — runs the same
 * deny-by-default charter + OPA checks against its own identity and resource namespace.
 *
 * The values are deliberately plain strings, not an enum: the tier vocabulary lives in
 * `openbank-libs/governance/agents.yaml` (charter data) and `openbank-infra/opa/policies/agents.rego`
 * (decision logic), and this module must not need a release to admit a new tier. The constants
 * below carry phase-3's values so existing callers keep compiling unchanged.
 *
 * @property agentId the charter `id` this gate checks grants for (e.g. `"flaky-test-hunter"`).
 * @property resourceId the resource the write path touches (e.g. `"github-pr"`; money-path
 *   agents use their own namespace, e.g. `"payment-instruction"`).
 * @property tier the charter tier the action claims — the caller binds tool→tier in code and
 *   both the in-process verifier and the OPA query re-validate the (tier, resource) grant
 *   against the charter, they do not trust a tier name sent by a caller (phase-3 contract).
 * @property tool the action name under evaluation, sent to OPA as `input.tool` and usable in
 *   the consuming service's audit payload.
 */
data class AgentPolicyConfig(val agentId: String, val resourceId: String, val tier: String, val tool: String) {
    /** The default action under evaluation for this agent — the config's own (tool, resource, tier). */
    fun defaultInput(): PolicyDecisionInput = PolicyDecisionInput(tool = tool, resource = resourceId, tier = tier)

    companion object {
        /** Phase-3 binding: flaky-test-hunter's one GitHub write path. */
        const val AGENT_ID_FLAKY_TEST_HUNTER = "flaky-test-hunter"

        const val TOOL_GH_PR_OPEN = "gh.pr.open"
        const val RESOURCE_GITHUB_PR = "github-pr"
        const val TIER_WRITE_PROPOSAL = "write_proposal"

        /** Phase-3 config, kept so the first consumer's adapter stays a one-liner. */
        fun flakyTestHunter(): AgentPolicyConfig = AgentPolicyConfig(
            agentId = AGENT_ID_FLAKY_TEST_HUNTER,
            resourceId = RESOURCE_GITHUB_PR,
            tier = TIER_WRITE_PROPOSAL,
            tool = TOOL_GH_PR_OPEN,
        )
    }
}
