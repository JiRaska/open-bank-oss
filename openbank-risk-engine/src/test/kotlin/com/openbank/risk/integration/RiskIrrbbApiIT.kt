// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR-0313 phase 1 IRRBB end to end: ledger and lending test doubles at the ports, a real
 * snapshot that TIES OUT on real Postgres, a curve set over HTTP, then GET .../irrbb.
 * The shipped configuration carries EUR shock sizes only (d368 Table 1), so the EUR book is
 * shocked and a CZK book is reported "not configured".
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskIrrbbApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var lending: FakeLendingPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    private val loan = lendingLoan(id = Fixtures.LOAN_A, principal = "60000.00", currency = "EUR", term = 60, paid = 6)

    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
    }

    /** A EUR-only bank: 200 on deposit, the loan on 1200 funded by equity. */
    private fun eurBook(): LedgerInputs {
        val l = loan.outstandingPrincipal.toPlainString()
        return LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1002", "ASSET", "EUR", "200.00", "0"),
                tb("2101", "LIABILITY", "EUR", "0", "200.00"),
                tb("1200", "ASSET", "EUR", l, "0"),
                tb("3000", "EQUITY", "EUR", "0", l),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "EUR", "0", "200.00")),
        )
    }

    private fun snapshot(asOf: String, status: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    private fun curveSet(asOf: String): String = given().contentType("application/json")
        .body(
            """{"asOf":"$asOf","provenance":"synthetic","source":"IT","curves":{""" +
                """"ESTR":[{"tenor":"ON","rate":0.025},{"tenor":"3M","rate":0.026},{"tenor":"1Y","rate":0.028}],""" +
                """"CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"1Y","rate":0.038}]}}""",
        )
        .`when`().post("/api/v1/risk/curve-sets").then().statusCode(201).extract().path("id")

    private fun irrbb(runId: String, setId: String, query: String = ""): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/irrbb?curveSetId=$setId$query")
            .then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied EUR book gets gap, six scenarios, worst case and the outlier ratio only with Tier 1`() {
        ledger.inputs = eurBook()
        lending.loans = listOf(loan)
        val asOf = "2025-11-28"
        val run = snapshot(asOf, "TIED_OUT")
        val set = curveSet(asOf)

        val body = irrbb(run, set)
        val gap = body["gaps"].single()
        assertThat(gap["currency"].asText()).isEqualTo("EUR")
        val bucketSum = gap["buckets"].fold(BigDecimal.ZERO) { a, b -> a.add(b["gap"].decimalValue()) }
        assertThat(bucketSum).isEqualByComparingTo(gap["totalGap"].decimalValue())
        assertThat(gap["totalGap"].decimalValue())
            .isEqualByComparingTo(loan.outstandingPrincipal.subtract(BigDecimal("200.00")))

        assertThat(body["scenarios"].map { it["scenario"].asText() }).containsExactly(
            "parallel-up",
            "parallel-down",
            "steepener",
            "flattener",
            "short-up",
            "short-down",
        )
        val up = body["scenarios"][0]["currencies"].single()
        assertThat(up["deltaEve"].decimalValue().signum()).isNegative()
        assertThat(up["deltaNii"].isNull).isFalse()
        assertThat(body["scenarios"][2]["currencies"].single()["deltaNii"].isNull).isTrue()
        assertThat(body["worstCase"]["scenario"].asText()).isEqualTo("parallel-up")
        assertThat(body["worstCase"]["currency"].asText()).isEqualTo("EUR")

        assertThat(body["outlierTest"]["tier1Supplied"].asBoolean()).isFalse()
        assertThat(body["outlierTest"]["ratio"].isNull).isTrue()
        assertThat(body["assumptions"]["shockSource"].asText()).contains("d368")
        assertThat(body["assumptions"]["shockSizes"].single()["parallelBp"].decimalValue()).isEqualByComparingTo("200")
        assertThat(body["assumptions"]["postShockFloor"].isNull).isTrue()
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")

        val withTier1 = irrbb(run, set, "&tier1Capital=10000")
        val worst = withTier1["worstCase"]["loss"].decimalValue()
        assertThat(withTier1["outlierTest"]["ratio"].decimalValue())
            .isEqualByComparingTo(worst.divide(BigDecimal(10000)).setScale(6, java.math.RoundingMode.HALF_EVEN))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a CZK book is reported as not configured, never shocked with a guess`() {
        ledger.inputs = Fixtures.tiedOut()
        val asOf = "2026-01-30"
        val body = irrbb(snapshot(asOf, "TIED_OUT"), curveSet(asOf), "&tier1Capital=1000")
        assertThat(body["shockNotConfigured"].map { it.asText() }).containsExactly("CZK")
        assertThat(body["scenarios"].all { it["currencies"].isEmpty }).isTrue()
        assertThat(body["gaps"].single()["currency"].asText()).isEqualTo("CZK")
        assertThat(body["outlierTest"]["ratio"].isNull).isTrue()
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409 and bad parameters 400`() {
        val asOf = "2025-10-31"
        val set = curveSet(asOf)
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot(asOf, "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/irrbb?curveSetId=$set")
            .then().statusCode(409).body("error", equalTo("UNTIED"))

        ledger.inputs = Fixtures.tiedOut()
        val tied = given().contentType("application/json").body("""{"asOf":"2026-02-27"}""")
            .`when`().post("/api/v1/risk/snapshots").then().statusCode(201).extract().path<String>("id")
        given().`when`().get("/api/v1/risk/snapshots/$tied/irrbb").then().statusCode(400)
        given().`when`().get(
            "/api/v1/risk/snapshots/$tied/irrbb?curveSetId=$set&tier1Capital=abc",
        ).then().statusCode(400)
        given().`when`().get(
            "/api/v1/risk/snapshots/$tied/irrbb?curveSetId=$set&tier1Capital=-5",
        ).then().statusCode(400)
        // curve set of another date
        given().`when`().get("/api/v1/risk/snapshots/$tied/irrbb?curveSetId=$set").then().statusCode(400)
    }

    @Test
    fun `an unauthenticated caller cannot read IRRBB`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/irrbb?curveSetId=x")
            .then().statusCode(401)
    }
}
