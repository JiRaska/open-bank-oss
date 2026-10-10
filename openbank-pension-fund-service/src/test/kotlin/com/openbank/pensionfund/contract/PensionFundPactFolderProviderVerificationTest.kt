// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * PR-lane replay of pension-service's consumer pact (`pacts/openbank-pension-service-openbank-
 * pension-fund-service.json`, ADR-0334, #12350): holdings priced and unpriced, transactions,
 * strategies, SUBSCRIBE and REDEEM orders — every call pension-service's FundAdministrationPort
 * makes. A wrong path in pension-service's client is red HERE and nowhere else: the consumer's
 * mock server answers whatever it is asked.
 *
 * Replayed as pension-service's own principal (`service-account-openbank-pension`, ROLE_API), the
 * identity this service admits for holdings and orders. The missing-identity interactions are
 * filtered out to [PensionFundNegativeAuthProviderVerificationTest], which boots without
 * `@TestSecurity`. Counterpart: [PensionFundPactBrokerProviderVerificationTest] (main-push,
 * publishes the result `can-i-deploy` reads). Every `@State` here exists there too.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
@Provider("openbank-pension-fund-service")
@PactFolder("../pacts")
@PactFilter("^(?!" + NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PensionFundPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State(PRICED_STATE)
    fun pricedHoldings(): Unit = PensionFundPactStates.pricedHoldings()

    @State(UNPUBLISHED_STATE)
    fun unpublishedNavHoldings(): Unit = PensionFundPactStates.unpublishedNavHoldings()
}
