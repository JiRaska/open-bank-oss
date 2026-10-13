// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Replays the consumer's anonymous request against real HTTP: the generated pact expects 401.
 * No @TestSecurity identity is installed. Authenticated interactions run in
 * [PensionReportingPactFolderProviderVerificationTest].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-pension-service")
@PactFolder("../pacts")
@PactFilter("no valid M2M identity is presented")
class PensionReportingNegativeAuthProviderVerificationTest {
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        checkNotNull(context).target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        checkNotNull(context).verifyInteraction()
    }

    @State("no valid M2M identity is presented")
    fun configureState() {
        // Absence of @TestSecurity is the state: the request carries no identity.
    }
}
