// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.dispute.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import javax.sql.DataSource

/**
 * Broker-side provider verification for dispute-service, published-result counterpart to
 * [DisputePactProviderVerificationTest]. The sanctioned pair CLAUDE.md documents —
 * `@PactFolder` plus `@PactBroker` gated on `pactbroker.url` — as openbank-ledger-service and
 * openbank-sanctions-service already carry: the folder class is the always-on PR-lane gate
 * (ADR-0063: zero infra), the broker class is what publishes a verification result so
 * `can-i-deploy` sees a verified pact instead of blocking a healthy pair.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane (`_service-ci.yml` blanks
 * `PACT_BROKER_URL`). It runs on main-push with `PUBLISH_RESULTS=true` — that run is what creates
 * the main version the reconciler looks for.
 *
 * Both of the counterpart's states are handled here, because the broker serves every consumer's
 * pact for this provider: a missing state fails with MissingStateChangeMethod and publishes a
 * FAILURE, which blocks deploys on a healthy pair.
 */
@QuarkusTest
@TestProfile(DisputePactAuthzOffProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-dispute-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DisputePactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

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

    /** Mirrors [DisputePactProviderVerificationTest] — direct-JDBC seed, see that class's KDoc. */
    @State("a dispute exists for the pact agent")
    fun statePactDisputeExists() = AgentDisputePactSeed.seedPactDispute(dataSource)

    /** Mirrors [DisputePactProviderVerificationTest] — the negative UUID is never inserted. */
    @State("no dispute exists with the requested id")
    fun statePactUnknownDisputeNotFound() {
        val missingId = UUID.fromString("abababab-abab-4aba-8aba-babababababa")
        io.restassured.RestAssured.given().get("/api/v1/disputes/$missingId").then().statusCode(404)
    }
}
