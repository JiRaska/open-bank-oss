// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.authz

/**
 * Always allows. Wire as a `@Produces` in test scope so unit tests never
 * have to stand up an OPA sidecar to exercise an `@Authorize`-decorated
 * method. Production wiring is `OpaSidecarPolicyDecisionPoint` (Phase 1
 * of ADR-0034 D5).
 *
 * Decision carries `reason = "test-stub"` so an accidentally-shipped
 * AllowAll instance is grep-able in audit logs.
 */
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}

/**
 * Always denies. Used as the kill-switch alternative (`@Alternative
 * @Priority(High)`) — flipping this on via a Quarkus profile property
 * blocks every `@Authorize` call without redeploying the service. Mirrors
 * the per-agent kill switch in `agents.yaml` `limits.kill_switch`.
 */
class DenyAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = false, reason = "kill-switch-engaged", policyVersion = "deny-all")
}
