// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
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
 * Broker-side provider verification for sepa-instant, published-result counterpart to
 * [SepaInstantPactProviderVerificationTest]. The sanctioned pair CLAUDE.md documents —
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
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-sepa-instant")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class SepaInstantPactBrokerProviderVerificationTest {

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

    /** Mirrors [SepaInstantPactProviderVerificationTest] — direct-JDBC seed, see that class's KDoc. */
    @State("a settled SCT Inst payment exists for the pact agent")
    fun statePactPaymentExists() = AgentSepaInstantPactSeed.seedPactPayment(dataSource)

    /** Mirrors [SepaInstantPactProviderVerificationTest] — the negative UUID is never inserted. */
    @State("no SCT Inst payment exists with the requested id")
    fun statePactUnknownPaymentNotFound() {
        val missingId = UUID.fromString("abababab-abab-4aba-8aba-babababababa")
        io.restassured.RestAssured.given().get("/api/v1/sepa-instant/$missingId").then().statusCode(404)
    }
}
