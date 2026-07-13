// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.domain.model

import java.math.BigDecimal
import java.time.Instant

// One value per static-policy-drift check this agent re-verifies fleet-wide (ADR-0167). Checks 1,
// 2 and 4 generalize/re-derive two incidents that were both found manually and both live in
// shipped code before being caught (issue #266, PR #402); check 3 is a proactive drift check with
// no known live incident yet; check 5 (charter-vs-deployed-runtime-grant drift) is a bootstrap-
// phase stub — see PolicyScanPort.
enum class AuthzPolicyCheckType {
    UNREACHABLE_PRINCIPAL_TYPE_RULE,
    AGENT_ID_PREFIX_MISMATCH,
    CHARTER_TOOL_TIER_DRIFT,
    REST_BYPASSES_AGENTS_ALLOW,
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

/** One `principal.type == "X"` (or similar) equality comparison found in a live .rego rule body
 * (comment lines and test-fixture object literals are excluded upstream by the scan adapter). */
data class PrincipalTypeComparison(val file: String, val line: Int, val literalValue: String, val snippet: String)

/** One `input.agent` occurrence participating in an equality comparison WITHOUT a nearby
 * `trim_prefix(input.agent, ...)` wrap on the same line — the exact shape of a charter lookup that
 * would silently never match on the REST bridge path (agent-id carries an `agent:` prefix there,
 * but not on the raw MCP `/tools/call` path). */
data class UnwrappedAgentIdComparison(val file: String, val line: Int, val snippet: String)

/** One literal (non-glob) token in a flat-list charter's `tools.allow`, tagged with the charter it
 * belongs to. Only charters using the flat glob-list `tools.allow: [...]` shape are scanned this
 * way — the tier/resources object shape (`- tier: read, resources: [...]`) used by every
 * control-plane agent charter (including this one) references a different, adjacent vocabulary and
 * is out of scope for this check (ADR-0167 Decision). */
data class CharterAllowToken(val agentId: String, val token: String)

/** One glob pattern in a flat-list charter's `tools.deny`. */
data class CharterDenyPattern(val agentId: String, val pattern: String)

/** One `charter_allowed` reference found outside `agents.rego`/`agents_test.rego` — the one place
 * that predicate is meant to be defined and consumed internally. `rest.rego` (and any future REST
 * bridge) must delegate to `agents.allow`, which additionally applies `hard_denied` /
 * `charter_denied` / `skill_ok`; calling `charter_allowed` directly skips all three. */
data class RestBypassReference(val file: String, val line: Int, val snippet: String)

/**
 * One collect-phase snapshot: every raw signal the four implemented checks need, gathered in a
 * single repo-checkout pass (PolicyScanPort). The judging (turning a raw signal into a finding, or
 * deciding it is benign) happens in DetectDriftActivityImpl, not here — mirrors every sibling
 * agent's collect/detect split.
 */
data class AuthzPolicySnapshot(
    val regoFilesScanned: Int,
    // Check 1: the set of principal-type values AuthorizeInterceptor.principalType() can actually
    // emit today (parsed from openbank-libs-runtime, not hardcoded), and every rule-body comparison
    // found across the canonical .rego sources.
    val emittedPrincipalTypes: Set<String>,
    val principalTypeComparisons: List<PrincipalTypeComparison>,
    // Check 2.
    val unwrappedAgentIdComparisons: List<UnwrappedAgentIdComparison>,
    // Check 3: agents.yaml's tool_tiers vocabulary, plus every flat-list charter's allow/deny.
    val toolTiersVocabulary: Set<String>,
    val charterAllowTokens: List<CharterAllowToken>,
    val charterDenyPatterns: List<CharterDenyPattern>,
    // Check 4.
    val restBypassReferences: List<RestBypassReference>,
)

data class AuthzPolicyFinding(
    val id: String,
    val checkType: AuthzPolicyCheckType,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    // The rego file, or the charter agent id, this finding is about.
    val component: String,
    val filePath: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val rootCause: String? = null,
    val proposalUrl: String? = null,
    val proposedFixDiff: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class AuthzPolicyReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val regoFilesScanned: Int,
    val findingsDetected: List<AuthzPolicyFinding>,
    val findingsProposed: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, POLICY_SOURCE_CHANGED_WEBHOOK, OPERATOR_MANUAL }
