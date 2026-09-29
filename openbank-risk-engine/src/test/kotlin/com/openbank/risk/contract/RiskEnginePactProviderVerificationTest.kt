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
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider replay of finrep's snapshot-list, capital and liquidity pact (ADR-0313 D6) — the risk engine's
 * first provider contract. Reads `pacts/` (`@PactFolder`, git-pact, ADR-0063) and replays every
 * interaction whose provider is `openbank-risk-engine` against the running Quarkus test instance
 * and a real Postgres. Always runs on a PR, no broker: this is the half that catches a wrong PATH,
 * which the consumer's mock server cannot (#2269).
 *
 * Replays as finrep's own service-account with ROLE_API; OPA is not enforced in tests, the identity
 * grant is held by `risk_rest_ext_test.rego`. [RiskEnginePactBrokerProviderVerificationTest] is the
 * broker half (main-push only), which publishes the verification `can-i-deploy` reads.
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

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // The class-level @TestSecurity would authenticate the negative interaction and turn its
        // expected 401 into a 200 mismatch, or hide a provider that stopped enforcing authn.
        if (context?.interaction?.providerStates?.any { it.name == CapitalPactState.NO_IDENTITY } == true) {
            testIdentity.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(CapitalPactState.NO_IDENTITY)
    fun noIdentity() {
        // Intentionally empty: the state IS the absence of an identity (see verifyPacts).
    }

    @State(CapitalPactState.NAME)
    fun tiedOutRun(): Map<String, Any> = CapitalPactState.seed(ledger)

    /** C 72.00's liquidity interaction reads the same seeded run (the same book and report date). */
    @State(CapitalPactState.LIQUIDITY_NAME)
    fun tiedOutRunWithLiquidity(): Map<String, Any> = CapitalPactState.seed(ledger)
}

/**
 * The provider state both capital verification classes share ([RiskEnginePactProviderVerificationTest]
 * and [RiskEnginePactBrokerProviderVerificationTest]), so the broker replay cannot drift from the
 * folder replay. Builds the run through the real REST endpoint (only an HTTP request carries the
 * Vert.x context the reactive store needs) from a book that ties out, and hands its id to the pact;
 * a replay of the same inputs returns the same run, so the second interaction reuses it.
 */
object CapitalPactState {
    const val NAME = "a TIED_OUT risk snapshot exists at the report date"

    /** C 72.00's state: the same run as [NAME], read through `/liquidity`. */
    const val LIQUIDITY_NAME = "a TIED_OUT risk snapshot with an LCR result exists at the report date"

    /** The negative-auth state: the request carries no identity and must be refused 401. */
    const val NO_IDENTITY = "no valid identity is presented"
    private const val REPORTING_DATE = "2026-06-30"

    fun seed(ledger: FakeLedgerPort): Map<String, Any> {
        ledger.inputs = Fixtures.tiedOut()
        val id: String = given().contentType("application/json").body("""{"asOf":"$REPORTING_DATE"}""")
            .`when`().post("/api/v1/risk/snapshots")
            .then().statusCode(oneOf(HTTP_OK, HTTP_CREATED))
            .extract().path("id")
        return mapOf("runId" to id)
    }

    private const val HTTP_OK = 200
    private const val HTTP_CREATED = 201
}
