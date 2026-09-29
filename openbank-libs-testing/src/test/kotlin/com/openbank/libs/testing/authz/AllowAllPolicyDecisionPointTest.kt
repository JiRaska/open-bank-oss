// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.authz

import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.libs.authz.ResourceRef
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the [AllowAllPolicyDecisionPoint] contract now that it lives here
 * rather than in `openbank-libs-domain` (moved out of `src/main` there so
 * the class can never end up on a service's production classpath — see
 * `check-no-allow-all-pdp-in-main.py` and the `PolicyDecisionPoint` kdoc).
 */
class AllowAllPolicyDecisionPointTest {

    private val sampleQuery = AuthzQuery(
        principal = Principal(id = "u-7", type = "HUMAN", roles = listOf("ROLE_OPERATOR")),
        action = "party.update",
        resource = ResourceRef(type = "party", id = "p-123"),
        attributes = mapOf("client-ip" to "10.0.0.1"),
    )

    @Test
    fun `allow-all returns allow with grep-able test-stub reason`(): Unit = runBlocking {
        val pdp: PolicyDecisionPoint = AllowAllPolicyDecisionPoint()
        val decision = pdp.allow(sampleQuery)
        assertThat(decision.allow).isTrue()
        assertThat(decision.reason).isEqualTo("test-stub")
        assertThat(decision.policyVersion).isEqualTo("allow-all")
    }

    @Test
    fun `resource is optional for non-scoped actions`(): Unit = runBlocking {
        val pdp = AllowAllPolicyDecisionPoint()
        val nonScoped = AuthzQuery(
            principal = Principal(id = "ops", type = "HUMAN"),
            action = "system.snapshot",
            resource = null,
        )
        // Must not throw — null resource is legitimate.
        val decision = pdp.allow(nonScoped)
        assertThat(decision.allow).isTrue()
    }
}
