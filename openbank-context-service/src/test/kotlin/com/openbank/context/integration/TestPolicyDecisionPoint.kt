// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

@ApplicationScoped
@Alternative
@Priority(1)
class TestPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery) = AuthzDecision(
        allow = query.action.startsWith("context."),
        policyVersion = "test-policy-v1",
    )
}
