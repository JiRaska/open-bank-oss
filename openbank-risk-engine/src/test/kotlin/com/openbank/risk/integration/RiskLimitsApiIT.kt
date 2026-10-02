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

/**
 * ADR-0313 D9 end to end over REST: a snapshot that ties out on real Postgres, then
 * GET .../limits under the shipped limit set. The book is chosen so every status appears:
 * LCR BREACHES (a nostro is not HQLA, so HQLA is 0), NSFR and the capital ratio are OK, and the
 * IRRBB and large-exposure limits are NOT_EVALUABLE with the gap named.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLimitsApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @AfterEach
    fun reset() {
        ledger.inputs = Fixtures.tiedOut()
    }

    /** Nostro 1500 (bank, GL-level), retail deposits 1500, CET1 1000. */
    private fun book() = LedgerInputs(
        asOf = Fixtures.AS_OF,
        trialBalance = listOf(
            tb("1001", "ASSET", "CZK", "1500.00", "0"),
            tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
            tb("6000", "EQUITY", "CZK", "0", "1000.00"),
        ),
        subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
    )

    private fun snapshot(asOf: String, status: String, code: Int = 201): String =
        given().contentType("application/json")
            .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
            .then().statusCode(code).body("status", equalTo(status)).extract().path("id")

    private fun curveSet(asOf: String): String = given().contentType("application/json")
        .body(
            """{"asOf":"$asOf","provenance":"synthetic","source":"IT","curves":{""" +
                """"CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"1Y","rate":0.038}]}}""",
        )
        .`when`().post("/api/v1/risk/curve-sets").then().statusCode(201).extract().path("id")

    private fun limits(runId: String): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/limits").then().statusCode(200).extract().asString(),
    )

    private fun JsonNode.limit(id: String): JsonNode = this["limits"].single { it["limitId"].asText() == id }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `every limit is reported with its status, and a gap is NOT_EVALUABLE with its reason`() {
        ledger.inputs = book()
        val body = limits(snapshot("2024-03-04", "TIED_OUT"))

        assertThat(body["limitSet"]["id"].asText()).isEqualTo("openbank-risk-appetite")
        assertThat(body["limitSet"]["version"].asText()).isEqualTo("1")
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")
        assertThat(body["limits"].map { it["limitId"].asText() }).containsExactlyInAnyOrder(
            "lcr-min",
            "nsfr-min",
            "total-capital-ratio-min",
            "irrbb-eve-outlier",
            "large-exposure-bank-max",
        )

        val lcr = body.limit("lcr-min")
        assertThat(lcr["status"].asText()).isEqualTo("BREACH")
        assertThat(lcr["value"].decimalValue()).isEqualByComparingTo("0")
        assertThat(lcr["bound"].asText()).isEqualTo("MIN")
        assertThat(lcr["reason"].isNull).isTrue()

        assertThat(body.limit("nsfr-min")["status"].asText()).isEqualTo("OK")

        // 1000 own funds / (1500 nostro x 150 %, SCRA Grade C) = 0.444444
        val capital = body.limit("total-capital-ratio-min")
        assertThat(capital["status"].asText()).isEqualTo("OK")
        assertThat(capital["value"].decimalValue()).isEqualByComparingTo("0.444444")

        val irrbb = body.limit("irrbb-eve-outlier")
        assertThat(irrbb["status"].asText()).isEqualTo("NOT_EVALUABLE")
        assertThat(irrbb["value"].isNull).isTrue()
        assertThat(irrbb["reason"].asText()).contains("no curve set recorded as of 2024-03-04")
        assertThat(body["curveSetId"].isNull).isTrue()

        val large = body.limit("large-exposure-bank-max")
        assertThat(large["status"].asText()).isEqualTo("NOT_EVALUABLE")
        assertThat(large["reason"].asText()).contains("carry no counterparty").contains("GL 1001")

        val summary = body["summary"]
        assertThat(summary["BREACH"].asInt()).isEqualTo(1)
        assertThat(summary["OK"].asInt()).isEqualTo(2)
        assertThat(summary["NOT_EVALUABLE"].asInt()).isEqualTo(2)
        assertThat(summary["EARLY_WARNING"].asInt()).isEqualTo(0) // present at zero, never absent
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `with a curve set of the run's own date the IRRBB limit is evaluated on it`() {
        ledger.inputs = book()
        val runId = snapshot("2024-03-05", "TIED_OUT")
        curveSet("2024-03-02") // a neighbouring day's curve is never borrowed
        assertThat(limits(runId).limit("irrbb-eve-outlier")["status"].asText()).isEqualTo("NOT_EVALUABLE")

        val setId = curveSet("2024-03-05")
        val body = limits(runId)
        assertThat(body["curveSetId"].asText()).isEqualTo(setId)
        val irrbb = body.limit("irrbb-eve-outlier")
        assertThat(irrbb["status"].asText()).isIn("OK", "EARLY_WARNING", "BREACH")
        assertThat(irrbb["value"].isNull).isFalse()
        assertThat(irrbb["basis"].asText()).contains("Tier 1 1000")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an UNTIED run answers 409 and an unknown run 404`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot("2024-03-06", "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/limits").then().statusCode(409)
        given().`when`().get("/api/v1/risk/snapshots/${java.util.UUID.randomUUID()}/limits").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "someone", roles = ["ROLE_CUSTOMER"])
    fun `a role outside the risk workspace is refused`() {
        given().`when`().get("/api/v1/risk/snapshots/${java.util.UUID.randomUUID()}/limits").then().statusCode(403)
    }
}
