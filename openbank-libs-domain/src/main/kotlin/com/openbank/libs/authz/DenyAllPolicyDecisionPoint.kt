// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

/**
 * Always denies. Used as the kill-switch alternative (`@Alternative
 * @Priority(High)`) — flipping this on via a Quarkus profile property
 * blocks every `@Authorize` call without redeploying the service. Mirrors
 * the per-agent kill switch in `agents.yaml` `limits.kill_switch`.
 *
 * Stays in `openbank-libs-domain` (unlike its allow-all counterpart, which
 * moved to `openbank-libs-testing` — see the PolicyDecisionPoint kdoc):
 * this class is genuinely used in PRODUCTION as a kill switch, so it must
 * remain reachable from a service's `src/main` classpath.
 */
class DenyAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = false, reason = "kill-switch-engaged", policyVersion = "deny-all")
}
