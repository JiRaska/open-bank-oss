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
 * ADR-0313 phase 2 credit-risk capital end to end: ledger and lending doubles at the ports, a
 * snapshot that TIES OUT on real Postgres, then GET .../capital under the shipped parameter set.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskCapitalApiIT {

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

    private fun capital(runId: String): JsonNode = json.readTree(
        given().`when`().get("/api/v1/risk/snapshots/$runId/capital").then().statusCode(200).extract().asString(),
    )

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `a tied book gets RWA per class, the 8 percent requirement, ratios and unclassified balances`() {
        val loan = lendingLoan(principal = "12000.00", currency = "CZK", term = 24, paid = 6)
        val l = loan.outstandingPrincipal
        ledger.inputs = LedgerInputs(
            asOf = Fixtures.AS_OF,
            trialBalance = listOf(
                tb("1001", "ASSET", "CZK", "1500.00", "0"),
                tb("1510", "ASSET", "CZK", "4000.00", "0"),
                tb("2100", "LIABILITY", "CZK", "0", "1500.00"),
                tb("1200", "ASSET", "CZK", l.toPlainString(), "0"),
                tb("6000", "EQUITY", "CZK", "0", l.add(BigDecimal("4000.00")).toPlainString()),
                tb("1000", "ASSET", "CZK", "300.00", "0"),
                tb("4100", "INCOME", "CZK", "0", "300.00"),
            ),
            subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00"), sl(Fixtures.BOB, "CZK", "0", "500.00")),
        )
        lending.loans = listOf(loan)
        val body = capital(snapshot("2026-05-29", "TIED_OUT"))

        assertThat(body["parameterSetId"].asText()).isEqualTo("bcbs-d424-sa")
        assertThat(body["parameterSetVersion"].asText()).isEqualTo("1")
        assertThat(body["provenance"].asText()).isEqualTo("synthetic")
        val total = body["total"]
        // nostro 1500 × 150% (SCRA Grade C) + ČNB 4000 × 0% (¶8) + loan × 100% (other retail, ¶57)
        val rwa = BigDecimal("2250.00").add(l)
        assertThat(total["totalRwa"].decimalValue()).isEqualByComparingTo(rwa)
        assertThat(body["ownFundsRequirement"].decimalValue())
            .isEqualByComparingTo(rwa.multiply(BigDecimal("0.08")).setScale(2, java.math.RoundingMode.HALF_EVEN))
        val classes = total["classes"].associate { it["exposureClass"].asText() to it["rwa"].decimalValue() }
        assertThat(classes.keys).containsExactlyInAnyOrder("sovereign-and-central-bank", "bank", "retail")
        assertThat(classes["sovereign-and-central-bank"]).isEqualByComparingTo("0")
        assertThat(total["lines"].all { it["citation"].asText().startsWith("BCBS d424 ¶") }).isTrue()

        val cet1 = l.add(BigDecimal("4000.00"))
        assertThat(total["ownFunds"]["cet1"].decimalValue()).isEqualByComparingTo(cet1)
        assertThat(body["ratios"]["total"]["ratio"].decimalValue())
            .isEqualByComparingTo(cet1.divide(rwa, 6, java.math.RoundingMode.HALF_EVEN))
        assertThat(body["ratiosNotComputable"].isNull).isTrue()

        // 1000 "Cash and Cash Equivalents" is NOT mapped: listed, never counted.
        assertThat(body["unclassified"].map { it["glAccountCode"].asText() }).containsExactly("1000")

        val a = body["assumptions"]
        assertThat(
            a["scope"].asText(),
        ).contains("BCBS d424 SA weights; EU CRR Part Three Title II Chapter 2 not applied")
        assertThat(a["factors"].size()).isEqualTo(15)
        assertThat(a["classification"]["bankScraGrade"].asText()).isEqualTo("C")
        assertThat(a["creditRiskMitigation"].asText()).startsWith("None applied")
        assertThat(a["offBalanceSheet"].asText()).contains("no credit conversion factor")
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an untied run answers 409`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))
        val untied = snapshot("2026-01-30", "UNTIED")
        given().`when`().get("/api/v1/risk/snapshots/$untied/capital")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "risk", roles = ["ROLE_RISK"])
    fun `an unknown run answers 404`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-0000000000ff/capital")
            .then().statusCode(404)
    }

    @Test
    fun `an unauthenticated caller cannot read capital`() {
        given().`when`().get("/api/v1/risk/snapshots/00000000-0000-7000-8000-000000000001/capital")
            .then().statusCode(401)
    }
}
