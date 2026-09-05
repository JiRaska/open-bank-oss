// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.authz

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Test-scope replacement for [AuthzProducer], which wires an `OpaSidecarPolicyDecisionPoint`
 * pointing at a sidecar that does not exist in CI or a local run.
 *
 * This matters more here than the "so tests can run" of the account-service equivalent: this
 * service's `authz.enforce` resolves to **true** in the test profile (`%test` does not override
 * `${AUTHZ_ENFORCE:true}`), and an unreachable PDP under enforcement is a `PolicyDecisionException`
 * → 503. So without this producer an `@Authorize` endpoint answers 503 in every test, and a test
 * asserting a *denial* would pass against a service that never consulted a policy at all.
 *
 * The verdict is read from config on every call rather than baked in, so one producer serves both
 * directions: the allow path (paging can be exercised) and the deny path (the decision is proven
 * to be reached). Config, not a shared Kotlin object, because a `QuarkusTestProfile` loads in a
 * different classloader from the test class and a companion/singleton would initialise twice.
 */
@Mock
@ApplicationScoped
class MockAuthzProducer {

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint = ConfigDrivenPolicyDecisionPoint()

    /** Allows unless `test.authz.allow` is explicitly `false`. */
    class ConfigDrivenPolicyDecisionPoint : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            val allowed = ConfigProvider.getConfig()
                .getOptionalValue("test.authz.allow", Boolean::class.javaObjectType)
                .orElse(true)
            return AuthzDecision(
                allow = allowed,
                reason = if (allowed) "test-stub" else "test-stub-deny",
                policyVersion = "test-stub",
            )
        }
    }
}
