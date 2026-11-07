// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.contract

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * `authz.enforce=false` for the pact provider verifications — there is no OPA sidecar in a bare
 * `@QuarkusTest` and the `@Authorize` interceptor on `DisputeResource` fails closed (503) without
 * one. Mirrors `ComplaintRevisionIT.AuthzOffProfile`; shared by both provider classes so the
 * folder and broker verifications boot the same profile.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class DisputePactAuthzOffProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
}
