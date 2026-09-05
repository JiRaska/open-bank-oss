// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.port.out

import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot

/**
 * Direct repo-checkout read of the fleet's OPA/Rego authorization sources — the `.rego` policies
 * under `openbank-infra/opa/policies` and `openbank-libs/governance/policies`, and
 * `openbank-libs-runtime`'s `AuthorizeInterceptor.kt` (`read.governance`, ADR-0167 checks 1-4) —
 * and the `agents.yaml` charter registry that feeds them. Grep/text-scan based, not a Rego AST
 * parser (ADR-0167 Decision) — this agent runs from within the monorepo, so a full
 * re-implementation of a code-intelligence index is not needed for these four checks; mirrors
 * docs-truth-agent's `RepoScanPort` / release-steward's `RepoStateReadPort` bootstrap-phase
 * precedent of real, best-effort file/grep logic rather than a stub.
 */
interface PolicyScanPort {
    /** A single repo-checkout pass gathering every raw signal the four implemented checks need —
     * batched rather than one call per check so the whole policy surface is walked once per run. */
    suspend fun scan(): AuthzPolicySnapshot
}

interface LlmDiagnosisPort {
    suspend fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): String

    /** Always returns null in v1 (ADR-0167 Decision): this agent never auto-proposes a fix diff for
     * an authorization-policy defect. A wrong auto-fix on a rego rule or a charter is a live
     * security exposure, not a reviewable convenience — every finding stays `draft.ticket`-only so
     * a human reads the rule change and the code path it gates before anything is touched. The port
     * stays wired (mirrors every sibling agent's shape) for a possible, deliberately re-evaluated
     * future narrowing, not as dead scaffolding. */
    suspend fun proposeFixDiff(finding: AuthzPolicyFinding, diagnosis: String): String?
}

/**
 * Both methods return the URL of a proposal that was actually created, or `null` when none was —
 * an unwired write path, a missing token, or a refused finding. `null` is the ONLY way to say
 * "nothing was created": there is deliberately no placeholder-URL return, because a well-formed
 * string is indistinguishable from a delivered proposal to every consumer (#5897, and the
 * `UnwiredProposalPort` precedent in `openbank-mcp-service`, #3900).
 */
interface GitHubProposalPort {
    /** Not called in v1 — see [LlmDiagnosisPort.proposeFixDiff]. Kept for interface parity with
     * every sibling agent's [GitHubProposalPort] shape. Always returns `null` for this agent:
     * ADR-0167 forbids an auto-fix PR on an authorization policy outright. */
    suspend fun openProposalPr(finding: AuthzPolicyFinding, fixDiff: String): String?

    /** The only disposition path in v1: every finding is a ticket a human triages (ADR-0167
     * Decision) — authorization-policy findings are security-adjacent, so this agent errs toward
     * ticket-only rather than any auto-fix, even the fleet's usual "one narrow mechanical case."
     * Returns `null` when no ticket was opened. */
    suspend fun openTicket(finding: AuthzPolicyFinding, diagnosis: String): String?
}

interface FindingRepository {
    suspend fun save(finding: AuthzPolicyFinding): AuthzPolicyFinding
    suspend fun findActive(): List<AuthzPolicyFinding>
    suspend fun findById(id: String): AuthzPolicyFinding?
    suspend fun update(finding: AuthzPolicyFinding): AuthzPolicyFinding
}
