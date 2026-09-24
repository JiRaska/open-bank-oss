// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.Fixtures.tiedOutWithLoans
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager

/**
 * ADR-0314 D4 end to end: lending's loan book (a test double at the port, like the ledger) enters a
 * real snapshot over real HTTP and a real Postgres, ties out on Loans Receivable, is persisted as
 * instruments, and is expanded by the cash-flow endpoint.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskLoanInstrumentIT {

    @Inject
    lateinit var ledger: FakeLedgerPort

    @Inject
    lateinit var lending: FakeLendingPort

    private val json = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    private val loanA = lendingLoan(id = Fixtures.LOAN_A)
    private val loanB =
        lendingLoan(
            id = Fixtures.LOAN_B,
            principal = "6000.00",
            method = com.openbank.libs.lending.AmortizationMethod.EQUAL_PRINCIPAL,
        )
    private val loanTotal = loanA.outstandingPrincipal.add(loanB.outstandingPrincipal)

    /** The fakes are application-scoped singletons shared with every other IT class: reset them. */
    @AfterEach
    fun reset() {
        lending.loans = emptyList()
        ledger.inputs = Fixtures.tiedOut()
    }

    private fun snapshot(asOf: String, status: String): String = given().contentType("application/json")
        .body("""{"asOf":"$asOf"}""").`when`().post("/api/v1/risk/snapshots")
        .then().statusCode(201).body("status", equalTo(status)).extract().path("id")

    private fun curveSet(asOf: String): String = given().contentType("application/json")
        .body(
            """{"asOf":"$asOf","provenance":"synthetic","source":"IT","curves":{"CZEONIA":""" +
                """[{"tenor":"ON","rate":0.035},{"tenor":"3M","rate":0.036},{"tenor":"1Y","rate":0.038}]}}""",
        )
        .`when`().post("/api/v1/risk/curve-sets").then().statusCode(201).extract().path("id")

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `loans and deposits tie out, persist as instruments, and bucket to loan inflows plus deposit outflows`() {
        ledger.inputs = tiedOutWithLoans(loanTotal)
        lending.loans = listOf(loanA, loanB)

        val runId = snapshot("2026-09-30", "TIED_OUT")

        given().`when`().get("/api/v1/risk/snapshots/$runId/positions").then().statusCode(200)
            .body("positions.findAll { it.kind == 'LOAN' }", hasSize<Any>(2))
            .body("positions.findAll { it.glAccountCode == '1200' && it.kind != 'LOAN' }", hasSize<Any>(0))
        val instruments = json.readTree(
            given().`when`().get(
                "/api/v1/risk/snapshots/$runId/instruments",
            ).then().statusCode(200).extract().asString(),
        )["instruments"]
        assertThat(instruments.map { it["kind"].asText() }).containsOnly("AMORTISING_LOAN")
        assertThat(instruments.map { it["loan"]["remainingPeriods"].asInt() }).containsOnly(6)
        assertThat(count("SELECT count(*) FROM snapshot_instrument WHERE run_id = ?::uuid", runId)).isEqualTo(2)
        assertThat(count("SELECT count(*) FROM snapshot_instrument_installment WHERE run_id = ?::uuid", runId))
            .isEqualTo(12)

        val body = json.readTree(
            given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=${curveSet("2026-09-30")}")
                .then().statusCode(200).extract().asString(),
        )
        val loanInflows = (loanA.remainingInstallments + loanB.remainingInstallments)
            .fold(BigDecimal.ZERO) { acc, i -> acc.add(i.principal).add(i.interest) }
        val czk = body["currencies"].single()
        val bucketSum = czk["buckets"].fold(BigDecimal.ZERO) { acc, b -> acc.add(b["amount"].decimalValue()) }
        assertThat(bucketSum).isEqualByComparingTo(loanInflows.subtract(BigDecimal("1500.00")))
        assertThat(czk["total"].decimalValue()).isEqualByComparingTo(bucketSum)
        assertThat(body["expandedPositions"].asInt()).isEqualTo(4)
        assertThat(body["notExpanded"].asInt()).isEqualTo(2) // 1001 nostro, 3000 equity — not 1200
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `lending disagreeing with the ledger is UNTIED on 1200 and nothing is served`() {
        ledger.inputs = tiedOutWithLoans(loanTotal.add(BigDecimal("0.01")))
        lending.loans = listOf(loanA, loanB)

        val runId = snapshot("2026-09-29", "UNTIED")

        given().`when`().get("/api/v1/risk/snapshots/$runId").then().statusCode(200)
            .body("mismatches[0].glAccountCode", equalTo("1200"))
            .body("mismatches[0].difference", equalTo(-0.01f))
        given().`when`().get("/api/v1/risk/snapshots/$runId/positions").then().statusCode(409)
        given().`when`().get("/api/v1/risk/snapshots/$runId/instruments").then().statusCode(409)
        given().`when`().get("/api/v1/risk/snapshots/$runId/cash-flows?curveSetId=${curveSet("2026-09-29")}")
            .then().statusCode(409).body("error", equalTo("UNTIED"))
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a loan contract lending breaks is a 502 and no run is stored`() {
        ledger.inputs = tiedOutWithLoans(loanTotal)
        lending.loans = listOf(loanA.copy(outstandingPrincipal = loanA.outstandingPrincipal.add(BigDecimal.ONE)))

        given().contentType("application/json").body("""{"asOf":"2026-09-28"}""")
            .`when`().post("/api/v1/risk/snapshots").then().statusCode(502)
        assertThat(count("SELECT count(*) FROM snapshot_run WHERE as_of = ?::date", "2026-09-28")).isEqualTo(0)
    }

    private fun count(sql: String, arg: String): Int {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, arg)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
