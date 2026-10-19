// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.valuation.DealKind
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D8 over real HTTP and a real Postgres: stateless pre-trade pricing, and the valuation /
 * sensitivity / VaR / FX-position reads of a snapshot. The deals written here are BOOKED (never
 * on a snapshot's instrument list) and every row is removed afterwards.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskValuationApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var book: TreasuryDealBook

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val asOf = "2025-03-14"
    private val irsDeal = UUID.randomUUID()
    private val legacyDeal = UUID.randomUUID()
    private val plannedDeal = UUID.randomUUID()

    private fun legs(start: String, end: String) = """
        [{"payReceive":"PAY","currency":"CZK","notional":100000000,"accrualStart":"$start","accrualEnd":"$end",
          "accrualFrequency":"1Y","dayCount":"ACT/360","rate":{"type":"FIXED","rate":0.036},"finalNotionalExchange":false},
         {"payReceive":"RECEIVE","currency":"CZK","notional":100000000,"accrualStart":"$start","accrualEnd":"$end",
          "accrualFrequency":"3M","dayCount":"ACT_360","rate":{"type":"FLOATING","index":"PRIBOR-3M","spread":0,
          "fixingLagBusinessDays":2},"finalNotionalExchange":false}]
    """.trimIndent()

    private fun uploadCurveSet(): String = given().contentType("application/json").body(
        """{"asOf":"$asOf","provenance":"synthetic","source":"valuation IT","curves":{
            "CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"1Y","rate":0.036},{"tenor":"5Y","rate":0.037,"instrument":"SWAP"}],
            "PRIBOR_3M":[{"tenor":"3M","rate":0.036},{"tenor":"6x12","rate":0.0365,"instrument":"FRA"},
                         {"tenor":"2Y","rate":0.0366,"instrument":"SWAP"},{"tenor":"5Y","rate":0.037,"instrument":"SWAP"}]}}""",
    ).`when`().post("/api/v1/risk/curve-sets").then().statusCode(201).extract().path("id")

    private fun read(path: String): String = given().`when`().get(path).then().statusCode(200).extract().asString()

    @Test
    @TestSecurity(user = "dealer", roles = ["ROLE_API"])
    fun `pre-trade pricing prices the legs of a full deal ticket and persists nothing`() {
        val curveSetId = uploadCurveSet()
        val dealsBefore = TestDb.count("SELECT count(*) FROM treasury_deal")
        val body = """{"asOf":"$asOf","curveSetId":"$curveSetId","currency":"CZK","kind":"HYPOTHETICAL",
            "counterpartyId":"CP-1","channel":"VOICE","documents":[],"regulatory":{"lcrCategory":null},
            "calendars":["CZK"],"businessDayConvention":"MODIFIED_FOLLOWING","legs":${legs(asOf, "2030-03-14")}}"""
        val r: JsonNode = json.readTree(
            given().contentType("application/json").body(body).`when`().post("/api/v1/risk/pricing")
                .then().statusCode(200).extract().asString(),
        )
        assertThat(r["provenance"].asText()).isEqualTo("synthetic")
        assertThat(r["pvByLeg"]).hasSize(2)
        assertThat(r["pv01"].decimalValue().signum()).isEqualTo(1) // payer of fixed gains when rates rise
        assertThat(r["dv01Buckets"]).hasSize(9)
        assertThat(r["curveModelVersion"].asText()).startsWith("curve-bootstrap-v2")
        assertThat(TestDb.count("SELECT count(*) FROM treasury_deal")).isEqualTo(dealsBefore)

        given().contentType("application/json").body("""{"asOf":"$asOf","curveSetId":"$curveSetId","legs":[]}""")
            .`when`().post("/api/v1/risk/pricing").then().statusCode(400)
        given().contentType("application/json")
            .body("""{"asOf":"$asOf","curveSetId":"$curveSetId","legs":[{"payReceive":"PAY"}]}""")
            .`when`().post("/api/v1/risk/pricing").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a snapshot values its ACTUAL deals per deal and per book, reproducibly, and VaR states its data gap`() {
        ledger.inputs = Fixtures.tiedOut()
        val runId: String = given().contentType("application/json").body("""{"asOf":"$asOf"}""")
            .`when`().post(
                "/api/v1/risk/snapshots",
            ).then().statusCode(org.hamcrest.Matchers.oneOf(200, 201)).extract().path("id")
        val curveSetId = uploadCurveSet()
        runBlocking {
            book.apply(event(irsDeal, "IRS", null, legs("2025-03-10", "2030-03-10"), "TRADING-1", DealKind.ACTUAL))
            book.apply(event(legacyDeal, "MM_PLACEMENT", BigDecimal("3.60"), null, null, DealKind.ACTUAL))
            book.apply(event(plannedDeal, "IRS", null, legs("2025-03-10", "2027-03-10"), "TRADING-1", DealKind.PLANNED))
        }

        val first = read("/api/v1/risk/snapshots/$runId/valuation?curveSetId=$curveSetId")
        val again = read("/api/v1/risk/snapshots/$runId/valuation?curveSetId=$curveSetId")
        assertThat(again).isEqualTo(first) // ADR-0314 D2: same inputs, byte-identical output
        val v = json.readTree(first)
        val ids = v["deals"].map { it["dealId"].asText() }
        assertThat(ids).contains(irsDeal.toString(), legacyDeal.toString()).doesNotContain(plannedDeal.toString())
        assertThat(v["deals"].all { it["flowSource"].asText() == "DERIVED" }).isTrue()
        assertThat(v["book"].isNull).isFalse()
        assertThat(v["header"]["provenance"].asText()).isEqualTo("synthetic")
        assertThat(v["header"]["valuationInputHash"].asText()).startsWith("sha256:")

        val filtered = json.readTree(
            read("/api/v1/risk/snapshots/$runId/valuation?curveSetId=$curveSetId&bookId=TRADING-1"),
        )
        assertThat(filtered["deals"].map { it["dealId"].asText() }).containsExactly(irsDeal.toString())

        val s = json.readTree(
            read("/api/v1/risk/snapshots/$runId/sensitivity?curveSetId=$curveSetId&bookId=TRADING-1&buckets=1Y,5Y"),
        )
        assertThat(s["pv01"].decimalValue().signum()).isEqualTo(1)
        assertThat(s["dv01Buckets"]).hasSize(2)

        val fx = json.readTree(read("/api/v1/risk/snapshots/$runId/fx-position?curveSetId=$curveSetId"))
        assertThat(fx["currencies"].map { it["currency"].asText() }).containsExactly("CZK")

        val gap = json.readTree(read("/api/v1/risk/snapshots/$runId/var?confidence=0.99&horizonDays=1"))
        assertThat(gap["valueAtRisk"].isNull).isTrue()
        assertThat(gap["dataGap"].asText()).contains("below the configured minimum")

        given().`when`().get("/api/v1/risk/snapshots/$runId/var?confidence=2").then().statusCode(400)
        given().`when`().get("/api/v1/risk/snapshots/$runId/sensitivity?curveSetId=$curveSetId&bucketSetId=nope")
            .then().statusCode(400)
        given().`when`().get("/api/v1/risk/snapshots/${UUID.randomUUID()}/valuation").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `an over-bound booked deal is kept, an unbuildable one is a data gap, pricing answers 400`() {
        ledger.inputs = Fixtures.tiedOut()
        val runId: String = given().contentType("application/json").body("""{"asOf":"$asOf"}""")
            .`when`().post(
                "/api/v1/risk/snapshots",
            ).then().statusCode(org.hamcrest.Matchers.oneOf(200, 201)).extract().path("id")
        val curveSetId = uploadCurveSet()
        val longLeg = """{"payReceive":"PAY","currency":"CZK","notional":1000000,"accrualStart":"2025-03-10",
            "accrualEnd":"2085-03-10","accrualFrequency":"1Y","rate":{"type":"FIXED","rate":0.03},"finalNotionalExchange":false}"""
        val nineLegs = "[" + List(9) { longLeg }.joinToString(",") + "]"
        runBlocking {
            book.apply(event(irsDeal, "IRS", null, nineLegs, "TRADING-2", DealKind.ACTUAL))
            book.apply(
                event(legacyDeal, "IRS", null, nineLegs, "TRADING-2", DealKind.ACTUAL)
                    .copy(legsError = "legs cannot be built: schedule exceeds 2000 periods per leg"),
            )
        }
        val v = json.readTree(read("/api/v1/risk/snapshots/$runId/valuation?curveSetId=$curveSetId&bookId=TRADING-2"))
        val byId = v["deals"].associateBy { it["dealId"].asText() }
        assertThat(byId.getValue(irsDeal.toString())["unpriced"].isNull).isTrue() // 9 legs over 60 years: valued
        assertThat(byId.getValue(legacyDeal.toString())["unpriced"].asText()).contains("cannot be built")
        assertThat(v["book"].isNull).isTrue()
        assertThat(v["dataGap"].asText()).contains("cannot be valued")
        listOf("sensitivity", "fx-position").forEach { read ->
            val r = json.readTree(read("/api/v1/risk/snapshots/$runId/$read?curveSetId=$curveSetId&bookId=TRADING-2"))
            assertThat(r["dataGap"].asText()).describedAs(read).contains("cannot be valued")
        }
        val varRead = json.readTree(read("/api/v1/risk/snapshots/$runId/var?bookId=TRADING-2"))
        assertThat(varRead["dataGap"].asText()).contains("cannot be valued")

        given().contentType("application/json")
            .body("""{"asOf":"$asOf","curveSetId":"$curveSetId","legs":$nineLegs}""")
            .`when`().post("/api/v1/risk/pricing").then().statusCode(400)
    }

    private fun event(id: UUID, product: String, rate: BigDecimal?, legs: String?, bookId: String?, kind: DealKind) =
        TreasuryDealEvent(
            state = "BOOKED",
            dealId = id,
            product = product,
            counterpartyId = "CP-1",
            currency = "CZK",
            principal = BigDecimal("100000000"),
            rate = rate,
            valueDate = LocalDate.parse("2025-03-10"),
            maturityDate = LocalDate.parse(if (product == "MM_PLACEMENT") "2025-06-10" else "2030-03-10"),
            kind = kind,
            bookId = bookId,
            legsJson = legs,
            scheduleHash = null,
        )

    @AfterEach
    fun cleanup() {
        listOf(irsDeal, legacyDeal, plannedDeal).forEach {
            TestDb.execute("DELETE FROM treasury_deal WHERE deal_id = '$it'")
        }
    }
}
