// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.ap2.e2e

import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the ap2 E2E suite. The module's other tests never exercise the two ports this
 * service actually has: `Ap2VerifyEndpointTest` calls the endpoint as a Kotlin object with mocked
 * collaborators, `Ap2MandateVerifierTest` constructs the verifier by hand, and every `@QuarkusTest`
 * inherits the contract [com.openbank.ap2.contract.TestPolicyDecisionPoint] alternative — so the
 * real OPA HTTP client and the real trust-list config path had never been on the wire.
 *
 * This profile excludes that alternative, letting the libs
 * [com.openbank.libs.authz.OpaPolicyDecisionPointProducer] build the real
 * [com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint] against
 * [Ap2E2EWireMockResource] (standing in for the sidecar), and receives the issuer trust list from
 * the same resource — generated keys cross the real config property into the real
 * [com.openbank.ap2.infrastructure.keys.TrustListKeyResolver].
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM across the
 * whole suite.
 */
class Ap2E2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(Ap2E2EWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        // Without the exclusion the test alternative silently displaces the real OPA PDP producer
        // and the wire below proves nothing.
        "quarkus.arc.exclude-types" to "com.openbank.ap2.contract.TestPolicyDecisionPoint",
    )

    override fun runMainMethod(): Boolean = false
}
