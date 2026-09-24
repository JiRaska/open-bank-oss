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
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID

/**
 * Curve upload and snapshot cash flows over real HTTP and a real Postgres; only the ledger read is
 * replaced ([FakeLedgerPort]). Each test uses its own as-of date.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskCashFlowApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /** Fixtures.tiedOut() plus a EUR customer deposit of 200 on control 2101. */
    private fun twoCurrencies() = Fixtures.tiedOut().let {
        it.copy(
            trialBalance = it.trialBalance + listOf(
                tb("1002", "ASSET", "EUR", "200.00", "0"),
                tb("2101", "LIABILITY", "EUR", "0", "200.00"),
            ),
            subLedger = it.subLedger + sl(Fixtures.ALICE, "EUR", "0", "200.00"),
        )
    }

    private fun uploadCurveSet(asOf: String, vararg indices: String): String {
        val curves = indices.joinToString(",") {
            """"$it":[{"tenor":"ON","rate":0.035},{"tenor":"3M","rate":0.036},{"tenor":"1Y","rate":0.038}]"""
        }
        return given().contentType("application/json")
            .body("""{"asOf":"$asOf","provenance":"synthetic","source":"IT fixture","curves":{$curves}}""")
            .`when`().post("/api/v1/risk/curve-sets")
            .then().statusCode(201)
            .body("curves", hasSize<Any>(indices.size))
            .extract().path("id")
    }

    private fun snapshot(asOf: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).extract().path("id")

    private fun cashFlows(runId: String, curveSetId: String): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=$curveSetId")
            .then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a stored curve set is served back with its pillars, and persisted with quotes and pillars`() {
        val id = uploadCurveSet("2026-05-29", "CZEONIA", "ESTR")
        given().`when`().get("/api/v1/risk/curve-sets/$id").then().statusCode(200)
            .body("provenance", equalTo("synthetic"))
            .body("curves[0].index", equalTo("CZEONIA"))
            .body("curves[0].pillars", hasSize<Any>(3))
            .body("curves[0].pillars[0].date", equalTo("2026-05-30"))
        assertThat(count("SELECT count(*) FROM curve_set_quote WHERE curve_set_id = ?::uuid", id)).isEqualTo(6)
        assertThat(count("SELECT count(*) FROM curve_set_pillar WHERE curve_set_id = ?::uuid", id)).isEqualTo(6)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `cash flows of a tied run bucket to minus the deposit balances, with PV and the model named`() {
        ledger.inputs = twoCurrencies()
        val runId = snapshot("2026-06-30")
        val setId = uploadCurveSet("2026-06-30", "CZEONIA", "ESTR")

        val body = cashFlows(runId, setId)
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")
        assertThat(body["curveSetId"].asText()).isEqualTo(setId)
        assertThat(body["model"]["id"].asText()).isEqualTo("nmd-linear-core")
        assertThat(body["model"]["version"].asText()).isEqualTo("1.0.0")
        assertThat(body["expandedPositions"].asInt()).isEqualTo(3)
        assertThat(body["notExpanded"].asInt()).isEqualTo(2) // GL 1001 and 1002
        assertThat(body["unpriced"]).isEmpty()

        val expected = mapOf("CZK" to BigDecimal("-1500.00"), "EUR" to BigDecimal("-200.00"))
        body["currencies"].forEach { c ->
            val want = expected.getValue(c["currency"].asText())
            val bucketSum = c["buckets"].fold(BigDecimal.ZERO) { acc, b -> acc.add(b["amount"].decimalValue()) }
            assertThat(c["buckets"]).hasSize(10)
            assertThat(bucketSum).isEqualByComparingTo(want)
            assertThat(c["total"].decimalValue()).isEqualByComparingTo(want)
            val pv = c["presentValue"].decimalValue()
            assertThat(c["priced"].asBoolean()).isTrue()
            // Outflows discounted at positive rates: smaller in magnitude, same sign.
            assertThat(pv).isLessThan(BigDecimal.ZERO).isGreaterThan(want)
        }
        assertThat(body["currencies"].map { it["discountIndex"].asText() }).containsExactly("CZEONIA", "ESTR")
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a currency without a curve in the set is reported unpriced, never valued at zero`() {
        ledger.inputs = twoCurrencies()
        val runId = snapshot("2026-07-31")
        val setId = uploadCurveSet("2026-07-31", "CZEONIA")

        val body = cashFlows(runId, setId)
        assertThat(body["unpriced"].map { it.asText() }).containsExactly("EUR")
        val eur = body["currencies"].single { it["currency"].asText() == "EUR" }
        assertThat(eur["priced"].asBoolean()).isFalse()
        assertThat(eur["presentValue"].isNull).isTrue()
        assertThat(eur["total"].decimalValue()).isEqualByComparingTo("-200.00")
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `an untied run answers 409 and nothing is derived from it`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val runId = given().contentType("application/json").body("""{"asOf":"2026-08-31"}""")
            .`when`().post("/api/v1/risk/snapshots").then().statusCode(201)
            .body("status", equalTo("UNTIED")).extract().path<String>("id")
        val setId = uploadCurveSet("2026-08-31", "CZEONIA")

        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=$setId")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `bad requests are 400 and unknown ids 404`() {
        ledger.inputs = Fixtures.tiedOut()
        val runId = snapshot("2026-10-30")
        val otherDay = uploadCurveSet("2026-10-29", "CZEONIA")

        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows").then().statusCode(400)
        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=nope").then().statusCode(400)
        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=$otherDay").then().statusCode(400)
        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=${UUID.randomUUID()}")
            .then().statusCode(404)
        given().`when`().get("/api/v1/risk/curve-sets/${UUID.randomUUID()}").then().statusCode(404)

        fun upload(body: String) = given().contentType("application/json").body(body)
            .`when`().post("/api/v1/risk/curve-sets").then()
        upload("{}").statusCode(400)
        upload(
            """{"asOf":"2026-10-30","provenance":"synthetic","source":"x","curves":{"LIBOR":[{"tenor":"1M","rate":0.01}]}}""",
        )
            .statusCode(400)
        upload(
            """{"asOf":"2026-10-30","provenance":"synthetic","source":"x","curves":{"ESTR":[{"tenor":"2Y","rate":0.01}]}}""",
        )
            .statusCode(400)
        upload(
            """{"asOf":"2026-10-30","provenance":"real","source":"x","curves":{"ESTR":[{"tenor":"1M","rate":0.01}]}}""",
        )
            .statusCode(400)
        upload("""{"asOf":"2026-10-30","provenance":"synthetic","source":"x","curves":{"ESTR":[{"tenor":"1M"}]}}""")
            .statusCode(400)
    }

    @Test
    fun `an unauthenticated caller cannot upload a curve set`() {
        given().contentType("application/json").body("{}").`when`().post("/api/v1/risk/curve-sets")
            .then().statusCode(401)
    }

    private fun count(sql: String, id: String): Int {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
