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
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.valuation.DealKind
import com.openbank.risk.integration.FakeLedgerPort
import com.openbank.risk.integration.RiskSnapshotApiIT
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Provider replay of finrep's snapshot-list, capital and liquidity pact (ADR-0313 D6) — the risk engine's
 * first provider contract — and of treasury's curve-set reads for the simulated quotes (ADR-0315 D9). Reads `pacts/` (`@PactFolder`, git-pact, ADR-0063) and replays every
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

    @Inject
    lateinit var dealBook: TreasuryDealBook

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

    /** treasury's simulated quotes (ADR-0315 D9) read the newest curve set and its pillars. */
    @State(CurveSetPactState.NAME)
    fun curveSetWithCzeonia(): Map<String, Any> = CurveSetPactState.seed()

    /** treasury's revaluation (ADR-0344 D8) lists the runs and reads one TIED_OUT run's valuation. */
    @State(ValuationPactState.NAME)
    fun tiedOutRunValuingAnIrs(): Map<String, Any> = ValuationPactState.seed(ledger, dealBook)
}

/**
 * treasury-service's revaluation state: the TIED_OUT run of [CapitalPactState], a curve set as of the
 * same date carrying the two curves a CZK floating IRS needs, and one ACTUAL treasury IRS in the
 * engine's deal book, so the valuation values exactly one deal. Run and deal ids go to the pact.
 */
object ValuationPactState {
    const val NAME = "a TIED_OUT risk snapshot values a treasury IRS"
    private val DEAL_ID: UUID = UUID.fromString("0191c0de-0000-7000-8000-00000000d1a1")

    fun seed(ledger: FakeLedgerPort, book: TreasuryDealBook): Map<String, Any> {
        val runId = CapitalPactState.seed(ledger).getValue("runId")
        given().contentType("application/json").body(
            """{"asOf":"2026-06-30","provenance":"synthetic","source":"pact provider state","curves":{
                "CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"1Y","rate":0.036},{"tenor":"5Y","rate":0.037,"instrument":"SWAP"}],
                "PRIBOR_3M":[{"tenor":"3M","rate":0.036},{"tenor":"6x12","rate":0.0365,"instrument":"FRA"},
                             {"tenor":"2Y","rate":0.0366,"instrument":"SWAP"},{"tenor":"5Y","rate":0.037,"instrument":"SWAP"}]}}""",
        ).`when`().post("/api/v1/risk/curve-sets").then().statusCode(HTTP_CREATED)
        val legs = """
            [{"payReceive":"PAY","currency":"CZK","notional":100000000,"accrualStart":"2026-03-10","accrualEnd":"2031-03-10",
              "accrualFrequency":"1Y","dayCount":"ACT/360","rate":{"type":"FIXED","rate":0.036},"finalNotionalExchange":false},
             {"payReceive":"RECEIVE","currency":"CZK","notional":100000000,"accrualStart":"2026-03-10","accrualEnd":"2031-03-10",
              "accrualFrequency":"3M","dayCount":"ACT_360","rate":{"type":"FLOATING","index":"PRIBOR-3M","spread":0,
              "fixingLagBusinessDays":2},"finalNotionalExchange":false}]
        """.trimIndent()
        runBlocking {
            book.apply(
                TreasuryDealEvent(
                    state = "BOOKED",
                    dealId = DEAL_ID,
                    product = "IRS",
                    counterpartyId = "CP-1",
                    currency = "CZK",
                    principal = BigDecimal("100000000"),
                    rate = null,
                    valueDate = LocalDate.parse("2026-03-10"),
                    maturityDate = LocalDate.parse("2031-03-10"),
                    kind = DealKind.ACTUAL,
                    bookId = "TRADING-1",
                    legsJson = legs,
                    scheduleHash = null,
                ),
            )
        }
        return mapOf("runId" to runId, "dealId" to DEAL_ID.toString())
    }

    private const val HTTP_CREATED = 201
}

/**
 * treasury-service's provider state: a curve set holding ONE curve, CZEONIA — the curve treasury
 * reads by name for CZK — uploaded through the real endpoint so it is also the newest set when the
 * list interaction asks for `limit=1`. Its id is handed to the pact's `${'$'}{curveSetId}`.
 */
object CurveSetPactState {
    const val NAME = "a curve set with a CZEONIA curve exists"

    fun seed(): Map<String, Any> {
        val id: String = given().contentType("application/json")
            .body(
                """{"asOf":"2026-09-21","provenance":"synthetic","source":"pact provider state",
                   "curves":{"CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"3M","rate":0.036},{"tenor":"1Y","rate":0.038}]}}""",
            )
            .`when`().post("/api/v1/risk/curve-sets")
            .then().statusCode(HTTP_CREATED)
            .extract().path("id")
        return mapOf("curveSetId" to id)
    }

    private const val HTTP_CREATED = 201
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
