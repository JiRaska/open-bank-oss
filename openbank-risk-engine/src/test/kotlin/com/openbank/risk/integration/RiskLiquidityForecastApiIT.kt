// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.Fixtures.tb
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
import java.util.UUID

/**
 * The liquidity survival forecast over real HTTP and a real Postgres; only the ledger read is
 * replaced ([FakeLedgerPort]). Each test uses its own as-of date.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLiquidityForecastApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @AfterEach
    fun reset() {
        ledger.inputs = Fixtures.tiedOut()
    }

    /** Fixtures.tiedOut() (1500 CZK of customer deposits) plus 1000 CZK at the CNB deposit facility (HQLA L1). */
    private fun withReserves() = Fixtures.tiedOut().let {
        it.copy(
            trialBalance =
            it.trialBalance + tb("1510", "ASSET", "CZK", "1000.00", "0") + tb("3000", "EQUITY", "CZK", "0", "1000.00"),
        )
    }

    private fun snapshot(asOf: String, status: String = "TIED_OUT"): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    private fun curveSet(asOf: String): String = given().contentType("application/json")
        .body(
            """{"asOf":"$asOf","provenance":"synthetic","source":"IT fixture",""" +
                """"curves":{"CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"1Y","rate":0.038}]}}""",
        )
        .`when`().post("/api/v1/risk/curve-sets").then().statusCode(201).extract().path("id")

    private fun get(path: String): JsonNode =
        json.readTree(given().`when`().get(path).then().statusCode(200).extract().asString())

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a funded book survives the horizon, opening from the same HQLA stock the LCR reports`() {
        ledger.inputs = withReserves()
        val runId = snapshot("2028-01-31")
        val setId = curveSet("2028-01-31")

        val body = get("/api/v1/risk/snapshots/$runId/liquidity-forecast?curveSetId=$setId")
        assertThat(body["horizonDays"].asInt()).isEqualTo(90)
        assertThat(body["dailyDays"].asInt()).isEqualTo(30)
        assertThat(body["model"]["id"].asText()).isEqualTo("nmd-linear-core")
        assertThat(body["parameterSetId"].asText()).isEqualTo("bcbs-d238-d295")
        assertThat(body["assumptions"].map { it["key"].asText() }).contains("new-business-not-modelled")

        val czk = body["currencies"].single()
        val lcrStock = get("/api/v1/risk/snapshots/$runId/liquidity")["total"]["lcr"]["hqla"]["stock"].decimalValue()
        assertThat(
            czk["openingLiquidity"].decimalValue(),
        ).isEqualByComparingTo(lcrStock).isEqualByComparingTo("1000.00")
        assertThat(czk["hqla"]["stock"].decimalValue()).isEqualByComparingTo("1000.00")

        val ladder = czk["ladder"]
        assertThat(ladder).hasSize(30 + 9) // 30 daily rows, then weekly rows over days 31..90
        assertThat(ladder[0]["behaviouralOutflows"].decimalValue()).isEqualByComparingTo("-450.00")
        assertThat(ladder[0]["cumulative"].decimalValue()).isEqualByComparingTo("550.00")
        var running = czk["openingLiquidity"].decimalValue()
        ladder.forEach { row ->
            running = running.add(row["net"].decimalValue())
            assertThat(row["cumulative"].decimalValue()).isEqualByComparingTo(running)
        }
        // Three monthly core run-off slices of 17.50 by day 90 (2028-02-29, 03-31 and 04-30 = day 90).
        assertThat(ladder.last()["cumulative"].decimalValue()).isEqualByComparingTo("497.50")
        assertThat(czk["survivalHorizonDays"].isNull).isTrue()
        assertThat(czk["survivalDate"].isNull).isTrue()
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a book without HQLA breaches on the first outflow day, and the horizon is honoured`() {
        ledger.inputs = Fixtures.tiedOut()
        val runId = snapshot("2028-02-29") // a Tuesday: the volatile part leaves on day 1
        val setId = curveSet("2028-02-29")

        val body = get("/api/v1/risk/snapshots/$runId/liquidity-forecast?curveSetId=$setId&horizonDays=7")
        val czk = body["currencies"].single()
        assertThat(czk["hqla"].isNull).isTrue()
        assertThat(czk["openingLiquidity"].decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(czk["ladder"]).hasSize(7)
        assertThat(czk["survivalHorizonDays"].asInt()).isEqualTo(1)
        assertThat(czk["survivalDate"].asText()).isEqualTo("2028-03-01")
        assertThat(czk["flowsBeyondHorizon"].asInt()).isEqualTo(2 * 60) // two deposits, 60 core slices each
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val runId = snapshot("2028-03-31", status = "UNTIED")
        val setId = curveSet("2028-03-31")
        given().`when`().get("/api/v1/risk/snapshots/$runId/liquidity-forecast?curveSetId=$setId")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `bad requests are 400 and unknown ids 404`() {
        val runId = snapshot("2028-04-28")
        val setId = curveSet("2028-04-28")
        val base = "/api/v1/risk/snapshots/$runId/liquidity-forecast"
        listOf(
            "",
            "?curveSetId=nope",
            "?curveSetId=$setId&horizonDays=0",
            "?curveSetId=$setId&horizonDays=366",
            "?curveSetId=$setId&horizonDays=abc",
            "?curveSetId=${curveSet("2028-04-27")}",
        ).forEach { q ->
            given().`when`().get(base + q).then().statusCode(400)
        }
        given().`when`().get("$base?curveSetId=$setId&horizonDays=1").then().statusCode(200)
        given().`when`().get("$base?curveSetId=$setId&horizonDays=365").then().statusCode(200)
        given().`when`().get("$base?curveSetId=${UUID.randomUUID()}").then().statusCode(404)
        given().`when`().get("/api/v1/risk/snapshots/${UUID.randomUUID()}/liquidity-forecast?curveSetId=$setId")
            .then().statusCode(404)
    }

    @Test
    fun `an unauthenticated caller cannot read the forecast`() {
        given().`when`().get(
            "/api/v1/risk/snapshots/${UUID.randomUUID()}/liquidity-forecast?curveSetId=${UUID.randomUUID()}",
        )
            .then().statusCode(401)
    }
}
