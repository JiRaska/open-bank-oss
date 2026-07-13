// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.domain.model.AuthzPolicyCheckType
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot
import com.openbank.authzaudit.domain.model.FindingSeverity
import com.openbank.authzaudit.domain.model.FindingStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
open class DetectDriftActivityImpl : DetectDriftActivity {

    override fun detect(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> = buildList {
        addAll(checkUnreachablePrincipalTypeRules(snapshot))
        addAll(checkUnwrappedAgentIdComparisons(snapshot))
        addAll(checkCharterToolTierDrift(snapshot))
        addAll(checkRestBypassesAgentsAllow(snapshot))
        // Check 5 (charter-vs-deployed-runtime-grant drift, issue #743) is a bootstrap-phase stub —
        // it needs a live-cluster or fleet-wide-repo-scan correlation (every service's
        // application.yaml grant vs. this charter's tools.allow) this agent does not yet perform.
        // Deliberately not implemented as a fake "always empty" check function; tracked as a
        // known gap (docs/agents/authz-policy-auditor.md) rather than silently pretended-covered.
    }

    // Check 1 (ADR-0167, generalizes .github/scripts/check-no-service-principal-type.sh's single
    // literal pattern): a rego rule gated on a principal.type value AuthorizeInterceptor.
    // principalType() cannot actually emit is structurally unreachable dead code — it can never
    // fire, silently denying whatever caller it was meant to authorize (issue #266).
    private fun checkUnreachablePrincipalTypeRules(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> {
        if (snapshot.emittedPrincipalTypes.isEmpty()) return emptyList()
        return snapshot.principalTypeComparisons
            .filter { it.literalValue !in snapshot.emittedPrincipalTypes }
            .map { usage ->
                newFinding(
                    checkType = AuthzPolicyCheckType.UNREACHABLE_PRINCIPAL_TYPE_RULE,
                    severity = FindingSeverity.CRITICAL,
                    title =
                    "${usage.file}:${usage.line} gates a rule on principal.type == \"${usage.literalValue}\", " +
                        "but AuthorizeInterceptor.principalType() only ever emits " +
                        "${snapshot.emittedPrincipalTypes.sorted()} — this rule is structurally " +
                        "unreachable dead code (the same shape as the SERVICE-principal defect, issue #266)",
                    component = usage.file,
                    filePath = usage.file,
                    rawMetricValue = BigDecimal.ONE,
                    threshold = BigDecimal.ZERO,
                )
            }
    }

    // Check 2 (ADR-0167): input.agent participating in an equality comparison without a nearby
    // trim_prefix(input.agent, "agent:") wrap silently never matches on the REST bridge path, where
    // the agent id carries an "agent:" prefix (AuthorizeInterceptor.principalType()'s own
    // convention) that the bare MCP /tools/call path never adds.
    private fun checkUnwrappedAgentIdComparisons(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> =
        snapshot.unwrappedAgentIdComparisons.map { usage ->
            newFinding(
                checkType = AuthzPolicyCheckType.AGENT_ID_PREFIX_MISMATCH,
                severity = FindingSeverity.CRITICAL,
                title = "${usage.file}:${usage.line} compares input.agent directly (\"${usage.snippet}\") without a " +
                    "nearby trim_prefix(input.agent, \"agent:\") — silently never matches on the REST bridge path, " +
                    "where AuthorizeInterceptor prefixes an AI_AGENT's id with \"agent:\" but the raw MCP " +
                    "/tools/call path does not",
                component = usage.file,
                filePath = usage.file,
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
            )
        }

    // Check 3 (ADR-0167): agents.yaml charter/tool_tiers drift, two sub-checks over flat-list
    // charters only (the tier/resources object shape every control-plane agent charter uses
    // references a different, adjacent vocabulary and is out of scope here — ADR-0167 Decision).
    private fun checkCharterToolTierDrift(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> = buildList {
        addAll(checkAllowTokenDrift(snapshot))
        addAll(checkDeadDenyGlobs(snapshot))
    }

    // 3a: an allow token whose namespace (the segment before the first '.') is already a known
    // tool_tiers namespace, but the full token matches no tool_tiers entry — most likely a typo
    // within a shared vocabulary family (e.g. "query.ledger.readony"). A token in a namespace
    // tool_tiers has never used at all (e.g. a charter-local action-proposal verb) is deliberately
    // NOT flagged: that is a different, legitimate vocabulary this shallow check cannot yet tell
    // apart from a typo (docs/agents/authz-policy-auditor.md: Known gaps).
    private fun checkAllowTokenDrift(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> {
        val knownNamespaces = snapshot.toolTiersVocabulary.mapNotNull { it.substringBefore('.', "").ifBlank { null } }
            .toSet()
        return snapshot.charterAllowTokens
            .filter { it.token !in snapshot.toolTiersVocabulary }
            .filter { it.token.substringBefore('.', "") in knownNamespaces }
            .map { entry ->
                newFinding(
                    checkType = AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT,
                    severity = FindingSeverity.WARNING,
                    title =
                    "agents.yaml charter '${entry.agentId}' tools.allow references '${entry.token}', which is " +
                        "not a registered tool_tiers entry despite sharing a known namespace prefix — likely a typo " +
                        "or a renamed/removed tool_tiers entry",
                    component = entry.agentId,
                    filePath = "openbank-libs/governance/agents.yaml",
                    rawMetricValue = BigDecimal.ONE,
                    threshold = BigDecimal.ZERO,
                )
            }
    }

    // 3b: a charter's tools.deny glob that matches NOTHING in the fleet's known tool vocabulary
    // (tool_tiers ∪ every charter's own literal allow tokens) can never fire against any real tool
    // call — a typo'd or entirely stale deny pattern giving a false sense of restriction.
    private fun checkDeadDenyGlobs(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> {
        val vocabulary = snapshot.toolTiersVocabulary + snapshot.charterAllowTokens.map { it.token }
        if (vocabulary.isEmpty()) return emptyList()
        return snapshot.charterDenyPatterns
            .filter { pattern -> vocabulary.none { globMatches(pattern.pattern, it) } }
            .map { pattern ->
                newFinding(
                    checkType = AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT,
                    severity = FindingSeverity.WARNING,
                    title =
                    "agents.yaml charter '${pattern.agentId}' tools.deny pattern '${pattern.pattern}' matches " +
                        "no tool in the fleet's known tool_tiers/allow-token vocabulary — a typo'd or stale glob " +
                        "that can never fire, giving a false sense of restriction",
                    component = pattern.agentId,
                    filePath = "openbank-libs/governance/agents.yaml",
                    rawMetricValue = BigDecimal.ONE,
                    threshold = BigDecimal.ZERO,
                )
            }
    }

    // Mirrors agents.rego's own glob_match: equality, or a single embedded/trailing '*'.
    private fun globMatches(pattern: String, value: String): Boolean {
        if (pattern == value) return true
        if ('*' !in pattern) return false
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$")
        return regex.matches(value)
    }

    // Check 4 (ADR-0167, CLAUDE.md "OPA / Rego policies"): only agents.allow applies hard_denied /
    // charter_denied / skill_ok — a REST rule that calls charter_allowed directly lets a
    // fleet-wide hard-denied tool tier, or a charter's own tools.deny glob, silently reach a REST
    // action anyway.
    private fun checkRestBypassesAgentsAllow(snapshot: AuthzPolicySnapshot): List<AuthzPolicyFinding> =
        snapshot.restBypassReferences.map { usage ->
            newFinding(
                checkType = AuthzPolicyCheckType.REST_BYPASSES_AGENTS_ALLOW,
                severity = FindingSeverity.CRITICAL,
                title =
                "${usage.file}:${usage.line} references charter_allowed outside agents.rego (\"${usage.snippet}\") " +
                    "— a REST/MCP bridge rule must delegate to agents.allow, which additionally applies " +
                    "hard_denied / charter_denied / skill_ok; charter_allowed alone skips all three",
                component = usage.file,
                filePath = usage.file,
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
            )
        }

    private fun newFinding(
        checkType: AuthzPolicyCheckType,
        severity: FindingSeverity,
        title: String,
        component: String,
        filePath: String,
        rawMetricValue: BigDecimal,
        threshold: BigDecimal,
    ) = AuthzPolicyFinding(
        id = Ids.newId().toString(),
        checkType = checkType,
        severity = severity,
        detectedAt = Instant.now(),
        title = title,
        component = component,
        filePath = filePath,
        rawMetricValue = rawMetricValue,
        threshold = threshold,
        status = FindingStatus.OPEN,
    )
}
