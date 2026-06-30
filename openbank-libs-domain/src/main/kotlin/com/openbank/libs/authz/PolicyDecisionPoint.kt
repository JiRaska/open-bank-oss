// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

/**
 * Single port that every fine-grained authorization check goes through —
 * the hexagonal counterpart to the OPA sidecar deployed per service
 * (ADR-0034). Implementations:
 *
 *   - `OpaSidecarPolicyDecisionPoint`  (prod)  → HTTP to `localhost:8181`
 *   - `AllowAllPolicyDecisionPoint`    (tests) → unconditional allow
 *   - `DenyAllPolicyDecisionPoint`     (kill-switch) → unconditional deny
 *
 * Lives in libs so that every Quarkus service inherits the same call shape,
 * the same audit fields on the resulting [AuthzDecision], and the same OPA
 * query namespace (`data.openbank.rest.allow`).
 *
 * Conventions ([AuthzQuery.action]):
 *   - format: `<aggregate>.<verb>` — e.g. `party.update`, `account.freeze`,
 *     `consent.revoke`
 *   - the `<aggregate>` matches the dotted noun used by the existing
 *     `Roles` constants and `rules.yaml` money_path entries, so a single
 *     audit query reaches both the human-actor and AI-actor planes (cf.
 *     ADR-0031 `data.openbank.agents.allow`)
 */
interface PolicyDecisionPoint {
    suspend fun allow(query: AuthzQuery): AuthzDecision
}

/** Subject of the decision — the *thing* that wants to act. */
data class Principal(
    /** Stable identifier; `sub` claim for humans, agent id for AI agents (ADR-0031). */
    val id: String,
    /**
     * `HUMAN` | `AI_AGENT` | `SERVICE` — captured into [AuthzDecision.attributes]
     * and AuditEvent, so a single SQL query can ask "how many writes did agent
     * X perform last week?" without joining across two stores.
     */
    val type: String,
    /** Realm-level roles (`ROLE_COMPLIANCE`, `ROLE_OPERATOR`, …). Empty list for service-to-service. */
    val roles: List<String> = emptyList(),
    /** Free-form claims forwarded to OPA `input.principal.attributes`. */
    val attributes: Map<String, Any?> = emptyMap(),
)

/**
 * What is being acted on, when the action is resource-scoped. Null for
 * non-scoped actions (e.g. `system.shutdown`).
 *
 *   - `type` matches the libs typesafe-id family (`AccountId` → "account",
 *     `PartyId` → "party", …) so policies can look up ownership without
 *     guessing class names.
 *   - `id` is the textual form already returned by the typesafe ID's
 *     `toString()` — opaque to the policy.
 */
data class ResourceRef(val type: String, val id: String)

/** The decision-point query — serialized as `input` to OPA. */
data class AuthzQuery(
    val principal: Principal,
    /** Conventional `<aggregate>.<verb>` (see [PolicyDecisionPoint] kdoc). */
    val action: String,
    val resource: ResourceRef? = null,
    /**
     * Context attributes the policy may rely on but cannot derive from
     * principal+resource alone — request time, geo, IP class, amount
     * threshold from the request body, etc.
     */
    val attributes: Map<String, Any?> = emptyMap(),
)

/** What the decision-point returned. */
data class AuthzDecision(
    val allow: Boolean,
    /**
     * Free-text reason from the policy when [allow] is false. Used for the
     * `WWW-Authenticate` hint and the audit record; never echoed to
     * end-user input (privacy boundary).
     */
    val reason: String? = null,
    /**
     * Bundle version that produced this decision (commit-ish from the OCI
     * artefact, ADR-0034 D2). Allows post-incident replay: "the policy
     * that allowed this was v1.4.2, current bundle is v1.5.0".
     */
    val policyVersion: String? = null,
    /**
     * Pass-through attributes the policy chose to surface — e.g. a
     * four-eyes-required flag, or which rule matched. Stored in the audit
     * record alongside the decision.
     */
    val attributes: Map<String, Any?> = emptyMap(),
)
