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
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.vertx.core.Vertx
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Git-pact provider replay for pension-fund-service (#12425): tax-reporting-service's consumer
 * pact for `GET /api/v1/reporting/funds/{fundId}/period-figures`, replayed on every PR. A consumer
 * pact cannot catch a wrong request path — only this replay can (#2269).
 *
 * Replays AS tax-reporting's own client (`service-account-openbank-tax-reporting`, ROLE_API), the
 * identity the route admits in pension_fund_rest_ext.rego. The 401 interaction is replayed by
 * [PensionFundNegativeAuthProviderVerificationTest], which boots without an identity.
 *
 * The broker-sourced twin, [PensionFundPactBrokerProviderVerificationTest], publishes the result
 * on main-push; both share [PensionFundPactStates].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
@Provider("openbank-pension-fund-service")
@PactFolder("../pacts")
@PactFilter("^(?!" + PensionFundPactStates.NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PensionFundPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var store: PensionFundStore

    @Inject
    lateinit var vertx: Vertx

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

    @State(PensionFundPactStates.FUND_STATE)
    fun fundWithSeptemberNav() = PensionFundPactStates.fundWithSeptemberNav(vertx, store)
}
