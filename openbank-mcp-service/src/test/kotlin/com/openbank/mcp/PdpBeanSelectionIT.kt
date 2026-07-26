// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.authz.PolicyDecisionPoint
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Asserts WHICH `PolicyDecisionPoint` the container actually bound in a test JVM — the one thing a
 * green ALLOW assertion cannot tell you (#2494).
 *
 * `McpAuditEventIT`'s ALLOW case is consistent with two different worlds: the test stub is bound, or
 * the real `OpaSidecarPolicyDecisionPoint` is bound and something reachable on `localhost:8181`
 * happens to answer. Both are green. That ambiguity is the entire reported defect, and it cannot be
 * resolved by any assertion about a decision — only by an assertion about the BOUND TYPE. So this
 * makes the binding itself the thing under test: if a future change to `AuthzProducer`, to the
 * alternative's `@Priority`, or to Quarkus' resolution order ever hands the test JVM the real
 * sidecar client, this goes red immediately and by name, instead of turning `McpAuditEventIT` into a
 * mystery timeout on whichever runner happens to be loaded.
 */
@QuarkusTest
class PdpBeanSelectionIT {

    @Inject
    lateinit var pdp: PolicyDecisionPoint

    @Test
    fun `the test JVM binds the deterministic PDP stub, never the real OPA sidecar client`() {
        assertThat(ClientProxy.unwrap(pdp))
            .describedAs(
                "the test JVM must resolve the @Alternative stub; binding the real sidecar client " +
                    "makes every ALLOW assertion depend on whatever is listening on localhost:8181",
            )
            .isInstanceOf(TestPolicyDecisionPoint::class.java)
    }
}
