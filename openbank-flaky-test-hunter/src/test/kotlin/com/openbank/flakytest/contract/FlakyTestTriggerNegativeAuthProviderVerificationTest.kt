// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Replays the Admin UI's missing-identity interaction against openbank-flaky-test-hunter (ADR-0279 #3):
 * a workflow trigger with no identity must answer 401 before the handler runs.
 *
 * A second class rather than one more `@State` method: [FlakyTestTriggerPactProviderVerificationTest]
 * carries a class-level `@TestSecurity`, so every replay it runs arrives authenticated and a recorded
 * 401 could never verify there. `@TestSecurity` is absent here on purpose; the two classes carry
 * disjoint `@PactFilter` state regexes, so no interaction is replayed twice and none is skipped.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-flaky-test-hunter")
@PactFolder("../pacts")
@PactFilter(NEGATIVE_AUTH_STATE)
class FlakyTestTriggerNegativeAuthProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var port: String

    @BeforeEach
    fun before(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", port.toInt())
    }

    @State(NEGATIVE_AUTH_STATE)
    fun noIdentityPresented() {
        // Intentionally empty: the state IS the absence of an identity, which this class provides
        // by not declaring @TestSecurity.
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun pactVerificationTestTemplate(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }
}

/** The state name of the Admin UI's missing-identity interaction. */
const val NEGATIVE_AUTH_STATE = "no identity is presented"
