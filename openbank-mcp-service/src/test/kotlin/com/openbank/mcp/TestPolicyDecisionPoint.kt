// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Stands in for the OPA sidecar, which does not exist in a test JVM (`AuthzProducer` would produce
 * a client against `localhost:8181` and every call would fail closed — a real posture, but it makes
 * the ALLOW branch unreachable).
 *
 * Deliberately deterministic rather than a mock: `list_accounts` is allowed, everything else is
 * denied, so one running container serves both the allow and the deny assertion without
 * per-test stubbing.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class TestPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision = if (query.attributes["tool"] == ALLOWED_TOOL) {
        AuthzDecision(allow = true)
    } else {
        AuthzDecision(allow = false, reason = "no matching allow rule")
    }

    companion object {
        const val ALLOWED_TOOL = "list_accounts"
        const val DENIED_TOOL = "list_consents"
    }
}
