// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider verification for the interactions that must be served UNAUTHENTICATED.
 *
 * [CaseCoordinatorPactProviderVerificationTest] carries class-level `@TestSecurity`, which
 * authenticates every request it makes. That is correct for the roster/thread/status interactions
 * but would silently turn the adversarial 401 interaction green. This class has no `@TestSecurity`,
 * so the request arrives anonymous and the `@RolesAllowed` endpoint answers 401 — the behaviour the
 * consumer encoded.
 *
 * The provider-state filter is the exact complement of the sibling class's filter, so every
 * interaction is replayed by exactly one class.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-case-coordinator-agent")
@PactFolder("../pacts")
@PactFilter(NEGATIVE_AUTH_STATE)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class CaseCoordinatorUnauthorizedPactVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port.toInt())
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidIdentity() {
        // Intentionally empty: the state IS the absence of an authenticated identity.
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
