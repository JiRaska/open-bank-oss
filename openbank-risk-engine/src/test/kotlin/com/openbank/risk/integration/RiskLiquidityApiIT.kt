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

/**
 * ADR-0313 phase 1 LCR / NSFR end to end: ledger and lending doubles at the ports, a snapshot that
 * TIES OUT on real Postgres, then GET .../liquidity under the shipped parameter set.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLiquidityApiIT {

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

    private fun liquidity(runId: String): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/liquidity").then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied book gets LCR and NSFR with components, unclassified balances and the parameter set`() {
        val loan = lendingLoan(principal = "12000.00", currency = "CZK", term = 24, paid = 6)
        val l = loan.outstandingPrincipal.toPlainString()
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("1200", "ASSET", "CZK", l, "0"),
                tb("6000", "EQUITY", "CZK", "0", l),
                tb("1000", "ASSET", "CZK", "300.00", "0"),
                tb("4100", "INCOME", "CZK", "0", "300.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        lending.loans = listOf(loan)
        val body = liquidity(snapshot("2026-09-30", "TIED_OUT"))

        assertThat(body["parameterSetId"].asText()).isEqualTo("bcbs-d238-d295")
        assertThat(body["parameterSetVersion"].asText()).isEqualTo("2")
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")
        val total = body["total"]
        assertThat(total["currency"].asText()).isEqualTo("CZK")

        val lcr = total["lcr"]
        assertThat(lcr["hqla"]["stock"].decimalValue()).isEqualByComparingTo("0")
        assertThat(lcr["totalOutflows"].decimalValue()).isEqualByComparingTo("150.00")
        assertThat(lcr["inflows"].map { it["factorKey"].asText() })
            .contains("lcr-operational-deposit-inflow", "lcr-retail-loan-inflow")
        assertThat(lcr["cappedInflows"].decimalValue()).isLessThanOrEqualTo(lcr["inflowCap"].decimalValue())
        assertThat(lcr["ratio"].decimalValue()).isEqualByComparingTo("0")

        val nsfr = total["nsfr"]
        assertThat(nsfr["asf"].map { it["factorKey"].asText() })
            .contains("nsfr-asf-capital", "nsfr-asf-retail-less-stable", "nsfr-asf-other")
        assertThat(nsfr["ratio"].isNull).isFalse()

        // 1000 "Cash and Cash Equivalents" is NOT mapped: listed, never counted.
        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).containsExactly("1000")
        assertThat(lcr["hqla"]["lines"].isEmpty).isTrue()

        val a = body["assumptions"]
        assertThat(a["scope"].asText()).contains("2015/61 deviations not applied")
        assertThat(a["factors"].size()).isEqualTo(29)
        assertThat(a["factors"].all { it["citation"].asText().contains("BCBS d2") }).isTrue()
        assertThat(a["classification"]["retailStableShare"].decimalValue()).isEqualByComparingTo("0")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot("2026-04-30", "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/liquidity")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unknown run answers 404`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-0000000000ff/liquidity")
            .then().statusCode(404)
    }

    @Test
    fun `an unauthenticated caller cannot read liquidity`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/liquidity")
            .then().statusCode(401)
    }
}
