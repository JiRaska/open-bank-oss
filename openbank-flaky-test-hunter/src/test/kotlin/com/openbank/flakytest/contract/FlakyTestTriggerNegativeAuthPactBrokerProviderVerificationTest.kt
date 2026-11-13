// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-sourced counterpart to [FlakyTestTriggerNegativeAuthProviderVerificationTest].
 *
 * [FlakyTestTriggerPactBrokerProviderVerificationTest] filters the missing-identity interaction out
 * (it runs under `@TestSecurity`, so a recorded 401 could never verify there). Without this class the
 * broker would receive no verification result for that interaction, while the PR lane replays it, so
 * the two lanes would disagree about what is verified (issue #9752). `@TestSecurity` is absent here
 * on purpose; the broker classes carry disjoint `@PactFilter` state regexes, so no interaction is
 * replayed twice and none is skipped.
 *
 * Gated on `pactbroker.url`, so it is skipped locally and on the PR lane and runs on main-push.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-flaky-test-hunter")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@PactFilter(NEGATIVE_AUTH_STATE)
class FlakyTestTriggerNegativeAuthPactBrokerProviderVerificationTest {

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
