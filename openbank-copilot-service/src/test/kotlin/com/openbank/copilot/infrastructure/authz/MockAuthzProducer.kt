// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.authz

import com.openbank.libs.authz.AllowAllPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Test-scope replacement for [AuthzProducer].
 *
 * The production [AuthzProducer] wires an [com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint]
 * that calls an OPA sidecar which does not exist in CI or local unit-test runs.
 * This `@Mock` producer overrides the production bean and provides
 * [AllowAllPolicyDecisionPoint] so that `@Authorize`-decorated endpoints
 * can be exercised in `@QuarkusTest` tests without a running OPA instance.
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
