// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.contract

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Stands in for the OPA sidecar, which does not exist in a test JVM. Without it `AuthzProducer`
 * builds a client against `localhost:8181`, [com.openbank.ap2.infrastructure.rest.Ap2VerifyEndpoint]
 * fails **closed** with 503 on every call, and the 200 branch — the only branch `openapi.yaml` gives
 * a response schema for — is unreachable. Fail-closed is correct behaviour; it just means the wire
 * contract cannot be observed without a PDP.
 *
 * Deterministic rather than a mock, so one running container serves both documented outcomes without
 * per-test stubbing: a `PAYMENT` mandate is allowed, every other kind denied. The decision is keyed
 * on the `mandateKind` attribute the endpoint really passes, so this alternative also fails if the
 * endpoint stops sending it.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class TestPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        if (query.attributes["mandateKind"] == ALLOWED_KIND) {
            AuthzDecision(allow = true)
        } else {
            AuthzDecision(allow = false, reason = "no matching allow rule")
        }

    companion object {
        const val ALLOWED_KIND = "PAYMENT"
        const val DENIED_KIND = "INTENT"
    }
}
