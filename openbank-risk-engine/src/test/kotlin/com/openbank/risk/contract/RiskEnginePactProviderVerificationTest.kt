// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.risk.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.integration.FakeLedgerPort
import com.openbank.risk.integration.RiskSnapshotApiIT
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider replay of finrep's snapshot-list and capital pact (ADR-0313 D6) — the risk engine's
 * first provider contract. Reads `pacts/` (`@PactFolder`, git-pact, ADR-0063) and replays every
 * interaction whose provider is `openbank-risk-engine` against the running Quarkus test instance
 * and a real Postgres. Always runs on a PR, no broker: this is the half that catches a wrong PATH,
 * which the consumer's mock server cannot (#2269).
 *
 * Replays as finrep's own service-account with ROLE_API; OPA is not enforced in tests, the identity
 * grant is held by `risk_rest_ext_test.rego`.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-finrep", roles = ["ROLE_API"])
@Provider("openbank-risk-engine")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class RiskEnginePactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var ledger: FakeLedgerPort

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

    /**
     * Builds the run through the real REST endpoint (only an HTTP request carries the Vert.x
     * context the reactive store needs) from a book that ties out, and hands its id to the pact.
     * A replay of the same inputs returns the same run, so a second interaction reuses it.
     */
    @State(STATE)
    fun tiedOutRun(): Map<String, Any> {
        ledger.inputs = Fixtures.tiedOut()
        val id: String = given().contentType("application/json").body("""{"asOf":"$REPORTING_DATE"}""")
            .`when`().post("/api/v1/risk/snapshots")
            .then().statusCode(org.hamcrest.Matchers.oneOf(200, 201))
            .extract().path("id")
        return mapOf("runId" to id)
    }

    private companion object {
        const val STATE = "a TIED_OUT risk snapshot exists at the report date"
        const val REPORTING_DATE = "2026-06-30"
    }
}
