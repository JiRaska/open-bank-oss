// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.domain.policy

/**
 * Who is calling a tool. In phase 1 (ADR-0031 D9) the identity is asserted by the
 * `X-Agent-Id` header; ADR-0031 D3 replaces this with a SPIFFE/SPIRE SVID. A null
 * identity is the unauthenticated case and is denied by default.
 *
 * [modelId] is the charter-declared LiteLLM model id (ADR-0031 D5, issue #3667). It is
 * carried here so AgentPolicyGate can record it in the audit payload without touching the
 * model gateway.
 */
data class AgentIdentity(
    val agentId: String,
    val plane: String? = null,
    val skill: String? = null,
    val modelId: String = "unknown",
)

/**
 * The question put to the policy engine, mirroring the OPA input contract of
 * ADR-0031 D2: `{ agent, tool, resource, plane, attributes }`.
 */
data class PolicyQuery(
    val agent: String,
    val tool: String,
    val resource: String?,
    val plane: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
)

/**
 * The engine's answer. `reason` is always populated so a DENY is auditable, never silent
 * (ADR-0031 D5).
 */
data class PolicyDecision(
    val allow: Boolean,
    val agent: String,
    val tool: String,
    val resource: String?,
    val reason: String,
    /**
     * True when this DENY was caused by the PDP engine itself being unreachable or erroring,
     * NOT by an explicit policy decision. Set by the PDP adapter ([PolicyDecisionPoint]) on a
     * connectivity/timeout failure. AgentPolicyGate uses this flag (not the `reason` string) to
     * decide whether to fall back from BLOCK to ADVISORY — avoids free-form string matching that
     * could be exploited by OPA rules that happen to embed "unavailable" in their reason text.
     */
    val pdpError: Boolean = false,
)

/**
 * Enforcement posture (agents.yaml `enforced`). Phase 1 default is ADVISORY: every decision
 * is audited but a DENY does not block the call yet — "no agent acts yet, audit only"
 * (ADR-0031 D9 phase 1). Flip to BLOCK to enforce.
 */
enum class EnforcementMode { ADVISORY, BLOCK }

/**
 * Result of running a query through the gate: the decision plus whether the call may proceed
 * given the active [EnforcementMode]. In ADVISORY mode `proceed` is always true; in BLOCK mode
 * it equals `decision.allow`.
 */
data class GateOutcome(val decision: PolicyDecision, val mode: EnforcementMode, val proceed: Boolean)
