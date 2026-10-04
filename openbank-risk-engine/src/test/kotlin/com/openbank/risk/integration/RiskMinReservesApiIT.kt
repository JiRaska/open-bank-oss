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
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.Fixtures.tb
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * ČNB minimum reserves end to end: ledger double at the port, a snapshot that TIES OUT on real
 * Postgres, then GET .../min-reserves under the shipped `cnb-pmr` parameter set.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskMinReservesApiIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var lending: FakeLendingPort

    @Inject
    lateinit var treasury: TreasuryDealBook

    /** With the treasury read on, 1510 and 2300 are contract-level: their balances are these deals. */
    private val cnbDeal = UUID.randomUUID()
    private val borrowing = UUID.randomUUID()

    private fun seedDeal(dealId: UUID, product: String, counterparty: String, principal: String): Unit = runBlocking {
        treasury.apply(
            TreasuryDealEvent(
                state = "SETTLED",
                dealId = dealId,
                product = product,
                counterpartyId = counterparty,
                currency = "CZK",
                principal = BigDecimal(principal),
                rate = BigDecimal("2.50"),
                valueDate = LocalDate.parse("2026-05-28"),
                maturityDate = LocalDate.parse("2026-06-01"),
            ),
        )
    }

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @BeforeEach
    fun facts() = TestDb.seedReserveFacts()

    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
        TestDb.execute("DELETE FROM treasury_deal WHERE deal_id IN ('$cnbDeal', '$borrowing')")
    }

    private fun snapshot(asOf: String, status: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied CZK book gets base and the 4 percent requirement in effect, with holdings not stated`() {
        // the 1510 facility and the 2300 borrowing reach the engine as TREASURY_DEAL positions
        seedDeal(cnbDeal, "CNB_DEPOSIT_FACILITY", "CNB", "4000.00")
        seedDeal(borrowing, "MM_BORROWING", "BANK-A", "3000.00")
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("1510", "ASSET", "CZK", "4000.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("2300", "LIABILITY", "CZK", "0", "3000.00"),
                tb("6000", "EQUITY", "CZK", "0", "1000.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        val id = snapshot("2026-05-28", "TIED_OUT")
        val body: JsonNode = json.readTree(
            given().`when`().get("/api/v1/risk/snapshots/$id/min-reserves").then().statusCode(200).extract().asString(),
        )

        assertThat(body["parameterSetId"].asText()).isEqualTo("cnb-pmr")
        assertThat(body["parameterSetVersion"].asText()).isEqualTo("3")
        val czk = body["currencies"].single()
        assertThat(czk["base"].decimalValue()).isEqualByComparingTo("1500.00") // customer deposits only
        // the ratio in effect on 2026-05-28 is the ČNB fact (4 % from 2025-01-02), not a constant
        assertThat(czk["rate"].decimalValue()).isEqualByComparingTo("0.04")
        assertThat(body["requirement"].decimalValue()).isEqualByComparingTo("60.00")
        val facts = body["assumptions"]["rateFacts"].associateBy { it["instrument"].asText() }
        assertThat(facts.keys).containsExactlyInAnyOrder("MIN_RESERVE_RATIO", "MIN_RESERVE_REMUNERATION")
        assertThat(facts.getValue("MIN_RESERVE_RATIO")["effectiveFrom"].asText()).isEqualTo("2025-01-02")
        assertThat(facts.getValue("MIN_RESERVE_RATIO")["sourceUrl"].asText()).endsWith("PMR_historie_zmen.xlsx")
        // no GL is the ČNB current account (1510 is the deposit facility): not stated, never a zero
        assertThat(body["holdings"].isNull).isTrue()
        assertThat(body["totalHoldings"].isNull).isTrue()
        assertThat(body["surplus"].isNull).isTrue()
        assertThat(body["holdingsNotStated"].asText()).contains("current account at the ČNB")
        assertThat(body["remuneration"].decimalValue()).isEqualByComparingTo("0")
        assertThat(czk["requirementNotStated"].isNull).isTrue()
        assertThat(body["unclassified"]).isEmpty()
        assertThat(body["excluded"].map { it["glAccountCode"].asText() })
            .containsExactlyInAnyOrder("1001", "1510", "2300", "6000")
        assertThat(body["notes"].map { it.asText() }.any { it.contains("maintenance period") }).isTrue()
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unclassified liability leaves base and requirement null with a reason, never a partial sum`() {
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1100.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1000.00"),
                tb("2200", "LIABILITY", "CZK", "0", "100.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")),
        )
        val id = snapshot("2026-05-27", "TIED_OUT")
        val body: JsonNode = json.readTree(
            given().`when`().get("/api/v1/risk/snapshots/$id/min-reserves").then().statusCode(200).extract().asString(),
        )

        val czk = body["currencies"].single()
        assertThat(czk["base"].isNull).isTrue()
        assertThat(czk["requirement"].isNull).isTrue()
        assertThat(czk["requirementNotStated"].asText()).contains("not classified")
        assertThat(body["requirement"].isNull).isTrue()
        assertThat(body["surplus"].isNull).isTrue()
        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).containsExactly("2200")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `with no reserve ratio in effect the requirement is NOT_EVALUABLE, never a default`() {
        TestDb.execute("DELETE FROM cnb_policy_rate_fact WHERE instrument = 'MIN_RESERVE_RATIO'")
        val id = snapshot("2026-05-26", "TIED_OUT")
        given().`when`().get("/api/v1/risk/snapshots/$id/min-reserves")
            .then().statusCode(424)
            .body("error", equalTo("NOT_EVALUABLE"))
            .body("asOf", equalTo("2026-05-26"))
            .body("reason", org.hamcrest.Matchers.containsString("MIN_RESERVE_RATIO"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot("2026-01-29", "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/min-reserves")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unknown run answers 404`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-0000000000fe/min-reserves")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `period averaging under the shipped config - holdings and requirement not stated, coverage stated`() {
        // Shipped config maps no ČNB current account, and no run exists on the 2026-10-31 base date.
        snapshot("2026-11-01", "TIED_OUT")
        snapshot("2026-11-02", "TIED_OUT")
        val body: JsonNode = json.readTree(
            given().queryParam("asOf", "2026-11-03").`when`().get("/api/v1/risk/min-reserves/periods/2026-11")
                .then().statusCode(200).extract().asString(),
        )

        assertThat(body["calendarStatus"].asText()).isEqualTo("sample-unverified")
        assertThat(body["notes"].map { it.asText() }.any { it.startsWith("SAMPLE / UNVERIFIED") }).isTrue()
        assertThat(body["requirement"].isNull).isTrue()
        assertThat(body["requirementNotStated"].asText()).contains("2026-10-31")
        assertThat(body["averageHoldings"].isNull).isTrue() // never a stand-in zero
        assertThat(body["averageNotStated"].asText()).contains("current account at the ČNB")
        assertThat(body["daysWithData"].asInt()).isEqualTo(2)
        assertThat(body["daysElapsed"].asInt()).isEqualTo(3)
        assertThat(body["missingDays"].map { it.asText() }).containsExactly("2026-11-03")
        assertThat(body["proposal"].isNull).isTrue()

        val listed: JsonNode = json.readTree(
            given().queryParam("asOf", "2026-11-15").`when`().get("/api/v1/risk/min-reserves/periods")
                .then().statusCode(200).extract().asString(),
        )
        assertThat(listed["periods"].map { it["id"].asText() }).containsExactly("2026-11")
        assertThat(listed["calendarStatus"].asText()).isEqualTo("sample-unverified")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unknown period is 404 and a malformed asOf is 400`() {
        given().`when`().get("/api/v1/risk/min-reserves/periods/1999-01").then().statusCode(404)
        given().queryParam("asOf", "tomorrow").`when`().get("/api/v1/risk/min-reserves/periods/2026-11")
            .then().statusCode(400)
    }

    @Test
    fun `an unauthenticated caller cannot read a maintenance period`() {
        given().`when`().get("/api/v1/risk/min-reserves/periods/2026-11").then().statusCode(401)
    }

    @Test
    fun `an unauthenticated caller cannot read min reserves`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/min-reserves")
            .then().statusCode(401)
    }
}
