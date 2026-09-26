// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.authz

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint

/**
 * Always allows. Wire as a `@Produces` in test scope (via a per-service
 * `MockAuthzProducer`) so unit tests never have to stand up an OPA sidecar
 * to exercise an `@Authorize`-decorated method. Production wiring is
 * `OpaSidecarPolicyDecisionPoint` (Phase 1 of ADR-0034 D5).
 *
 * Lives in `openbank-libs-testing`, not `openbank-libs-domain`, so it can
 * never end up on a service's `src/main` classpath by accident — the class
 * that unconditionally returns `AuthzDecision(allow = true, ...)` is the
 * exact shape `check-no-allow-all-pdp-in-main.py` refuses to let ship in
 * production code (`rules.yaml: authz_policy`).
 *
 * Decision still carries `reason = "test-stub"` so an accidentally-shipped
 * AllowAll instance would remain grep-able in audit logs even if it did
 * reach a running service.
 */
class AllowAllPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision =
        AuthzDecision(allow = true, reason = "test-stub", policyVersion = "allow-all")
}
