// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.authz

import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.testing.authz.AllowAllPolicyDecisionPoint
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Test-scope replacement for the production OPA-backed PolicyDecisionPoint.
 *
 * The production producer wires an [com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint]
 * that calls an OPA sidecar which does not exist in CI or local unit-test runs — every
 * `@Authorize`-decorated endpoint that reached the PDP answered 503 (PolicyDecisionExceptionMapper),
 * so no integration test could ever exercise a successful authorized call (the trace contract
 * for opsmessage.compose is the first that needs one). This `@Mock` producer overrides the
 * production bean with [AllowAllPolicyDecisionPoint] so authorized paths run in `@QuarkusTest`.
 * Role checks happen before the PDP, so 401 paths are unaffected; tests asserting policy denies
 * do not exist in this module (verified 2026-10-15).
 *
 * `reason = "test-stub"` is grep-able in logs — if it ever appears in a
 * non-test context it signals a misconfiguration (ADR-0034 D3).
 */
@Mock
@ApplicationScoped
class MockAuthzProducer {

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint = AllowAllPolicyDecisionPoint()
}
