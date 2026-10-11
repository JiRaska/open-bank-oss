// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.testing.authz.AllowAllPolicyDecisionPoint
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Test-scope replacement for [AuthzProducer].
 *
 * The production [AuthzProducer] wires an [com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint]
 * that calls an OPA sidecar which does not exist in CI or local unit-test runs.
 * This `@Mock` producer overrides the production bean and provides
 * [AllowAllPolicyDecisionPoint] so that `@Authorize`-decorated endpoints
 * can be exercised in `@QuarkusTest` tests without a running OPA instance.
 * The isolated pension OIDC profile opts into the real HTTP decision client with
 * `test.authz.real-opa=true`, so its allow/deny assertions cannot pass on this mock.
 *
 * `reason = "test-stub"` is grep-able in logs — if it ever appears in a
 * non-test context it signals a misconfiguration (ADR-0034 D3).
 */
@Mock
@ApplicationScoped
class MockAuthzProducer {

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint {
        val config = ConfigProvider.getConfig()
        if (config.getOptionalValue("test.authz.real-opa", Boolean::class.java).orElse(false)) {
            return OpaSidecarPolicyDecisionPoint(
                baseUrl = config.getValue("opa.url", String::class.java),
            )
        }
        return AllowAllPolicyDecisionPoint()
    }
}
