// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.treasury.application.port.`in`.PortfolioStatementUseCase
import com.openbank.treasury.integration.TreasuryDealApiIT
import com.openbank.treasury.it.PostgresTestResource
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
 * Git-pact provider replay for treasury-service (ADR-0337 amendment): tax-reporting-service's
 * consumer pact for `GET /api/v1/treasury/portfolio/period-end?date=`, replayed on every PR. A
 * consumer pact cannot catch a wrong request path — only this replay can (#2269).
 *
 * Replays AS tax-reporting's own client (`service-account-openbank-tax-reporting`, ROLE_API), the
 * one machine identity treasury_rest_ext.rego admits to `treasury.portfolio.read`. The 200
 * interaction (and the 409 one once the consumer records it) runs here; the 401 interaction is replayed by
 * [TreasuryNegativeAuthProviderVerificationTest], which boots without an identity.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
@Provider("openbank-treasury-service")
@PactFolder("../pacts")
@PactFilter("^(?!" + TreasuryPactStates.NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class TreasuryPactFolderProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var portfolio: PortfolioStatementUseCase

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

    @State(TreasuryPactStates.SNAPSHOT_STATE)
    fun snapshotExists() = TreasuryPactStates.snapshotExists(vertx, portfolio)

    @State(TreasuryPactStates.NO_SNAPSHOT_STATE)
    fun noSnapshotExists() = TreasuryPactStates.noSnapshotExists()
}
