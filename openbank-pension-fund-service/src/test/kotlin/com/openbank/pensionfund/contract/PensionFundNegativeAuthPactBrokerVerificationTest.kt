// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.
package com.openbank.pensionfund.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/** Anonymous HTTP replay must have no TestSecurity identity. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@Provider("openbank-pension-fund-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@PactFilter("no valid M2M identity is presented")
class PensionFundNegativeAuthPactBrokerVerificationTest {
    @BeforeEach
    fun target(context: PactVerificationContext) {
        val port = ConfigProvider.getConfig().getValue("quarkus.http.test-port", Int::class.java)
        context.target = HttpTestTarget("localhost", port)
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    @State("no valid M2M identity is presented")
    fun anonymous() = Unit
}
