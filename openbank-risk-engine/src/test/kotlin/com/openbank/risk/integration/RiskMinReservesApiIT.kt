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

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
    }

    private fun snapshot(asOf: String, status: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied CZK book gets base and 2 percent requirement, with holdings not stated`() {
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("1510", "ASSET", "CZK", "4000.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("2300", "LIABILITY", "CZK", "0", "3000.00"),
                tb("2200", "LIABILITY", "CZK", "0", "100.00"),
                tb("6000", "EQUITY", "CZK", "0", "900.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        val id = snapshot("2026-05-28", "TIED_OUT")
        val body: JsonNode = json.readTree(
            given().`when`().get("/api/v1/risk/snapshots/$id/min-reserves").then().statusCode(200).extract().asString(),
        )

        assertThat(body["parameterSetId"].asText()).isEqualTo("cnb-pmr")
        assertThat(body["parameterSetVersion"].asText()).isEqualTo("1")
        val czk = body["currencies"].single()
        assertThat(czk["base"].decimalValue()).isEqualByComparingTo("1500.00") // customer deposits only
        assertThat(czk["rate"].decimalValue()).isEqualByComparingTo("0.02")
        assertThat(body["requirement"].decimalValue()).isEqualByComparingTo("30.00")
        // no GL is the ČNB current account (1510 is the deposit facility): not stated, never a zero
        assertThat(body["holdings"].isNull).isTrue()
        assertThat(body["totalHoldings"].isNull).isTrue()
        assertThat(body["surplus"].isNull).isTrue()
        assertThat(body["holdingsNotStated"].asText()).contains("current account at the ČNB")
        assertThat(body["remuneration"].decimalValue()).isEqualByComparingTo("0")
        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).containsExactly("2200")
        assertThat(body["excluded"].map { it["glAccountCode"].asText() })
            .containsExactlyInAnyOrder("1001", "1510", "2300", "6000")
        assertThat(body["notes"].map { it.asText() }.any { it.contains("maintenance period") }).isTrue()
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
    fun `an unauthenticated caller cannot read min reserves`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/min-reserves")
            .then().statusCode(401)
    }
}
