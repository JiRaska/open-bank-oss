// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Real HTTP against a real Postgres. It proves what no unit test can: that the routes are
 * registered, that the entities' column names match the migration, and that four-eyes holds for
 * two DIFFERENT authenticated principals — which is why the maker and checker steps are separate,
 * ordered methods, each with its own @TestSecurity identity.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PensionFundApiIT {

    companion object {
        val contract: UUID = UUID.randomUUID()
        lateinit var fundA: String
        lateinit var fundB: String
        lateinit var strategy: String
        lateinit var change: String
        lateinit var nav: String
        lateinit var loanNav: String
        lateinit var correction: String
        lateinit var loanPosition: String
    }

    private fun fundBody(isin: String) = """
        {"name":"Fund $isin","isin":"$isin","lei":"315700ABCDEF12345678","depositaryReference":"DEP-1",
         "custodyAccountReference":"CUST-$isin","currency":"CZK","riskClass":3,"mandatoryConservative":false,
         "managementFeeRate":0.008,"launchNavPerUnit":1}
    """.trimIndent()

    private fun allocation(a: String, b: String) = """
        [{"fundId":"$fundA","weight":$a,"lowerBand":0,"upperBand":1},{"fundId":"$fundB","weight":$b,"lowerBand":0,"upperBand":1}]
    """.trimIndent()

    private fun post(path: String, body: String? = null) = given().contentType("application/json")
        .let { if (body != null) it.body(body) else it }
        .`when`().post(path)

    @Test
    @Order(1)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `maker creates funds, a lifecycle strategy, a change, an order and a NAV`() {
        fundA = post("/api/v1/funds", fundBody("CZ0008474053")).then().statusCode(201)
            .body("status", equalTo("ACTIVE")).extract().path("id")
        fundB = post("/api/v1/funds", fundBody("CZ0008474061")).then().statusCode(201).extract().path("id")

        strategy = post(
            "/api/v1/strategies",
            """{"name":"Lifecycle","allocations":${allocation("0.6", "0.4")},
                "glidePath":[{"minYearsToRetirement":15,"allocations":${allocation("0.9", "0.1")}},
                             {"minYearsToRetirement":0,"allocations":${allocation("0.2", "0.8")}}]}""",
        ).then().statusCode(201).body("version", equalTo(1)).extract().path("id")

        val effective = LocalDate.now(ZoneOffset.UTC).plusDays(31)
        change = post(
            "/api/v1/strategies/$strategy/changes",
            """{"allocations":${allocation("0.5", "0.5")},"reason":"de-risk","effectiveDate":"$effective"}""",
        ).then().statusCode(201).body("status", equalTo("PENDING_APPROVAL")).extract().path("id")

        val order = """{"fundId":"$fundA","type":"SUBSCRIBE","amount":1000}"""
        // Without the key the money-path POST is refused, never queued (#8351).
        post("/api/v1/contracts/$contract/orders", order).then().statusCode(400)
        val placed = given().contentType("application/json").header("Idempotency-Key", "order-1").body(order)
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(202).body("status", equalTo("PENDING")).extract().path<String>("id")
        // A retry with the same key returns the same order: one order, not two.
        given().contentType("application/json").header("Idempotency-Key", "order-1").body(order)
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(202).body("id", equalTo(placed))

        nav = post("/api/v1/funds/$fundA/navs", """{"valuationDate":"${LocalDate.now(ZoneOffset.UTC)}","cash":0}""")
            .then().statusCode(201).body("status", equalTo("CALCULATED")).extract().path("id")

        // The maker cannot be the checker, for a NAV or a strategy change.
        post("/api/v1/navs/$nav/approve").then().statusCode(403)
        post("/api/v1/strategy-changes/$change/approve").then().statusCode(403)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `checker publishes the NAV, which settles the queued order, and approves the change`() {
        post("/api/v1/navs/$nav/approve").then().statusCode(200)
            .body("nav.status", equalTo("PUBLISHED"))
            .body("settledOrders", equalTo(1))

        post("/api/v1/strategy-changes/$change/approve").then().statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("participantNotificationDate", equalTo(LocalDate.now(ZoneOffset.UTC).toString()))
        // Not before the effective date.
        post("/api/v1/strategy-changes/$change/apply").then().statusCode(409)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `holdings are valued at the published NAV and persisted`() {
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().statusCode(200)
            .body("holdings[0].fundId", equalTo(fundA))
            .body("holdings[0].units", equalTo(1000.0f))
            .body("holdings[0].value", equalTo(1000.0f))

        given().`when`().get(
            "/api/v1/strategies/$strategy/allocation?yearsToRetirement=20",
        ).then().log().ifValidationFails().statusCode(200)
            .body("[0].weight", equalTo(0.9f))
        // Absent query parameter is a 400, never a 500 (#3104).
        given().`when`().get("/api/v1/strategies/$strategy/allocation").then().statusCode(400)
        given().`when`().get("/api/v1/funds/${UUID.randomUUID()}").then().statusCode(404)

        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.prepareStatement("select units from unit_holdings where contract_id = ?").use { st ->
                st.setObject(1, contract)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("1000")
                }
            }
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    fun `the reporting read model serves period aggregates that reconcile, over real HTTP and SQL`() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = today.withDayOfMonth(1)
        val path = "/api/v1/reporting/funds/$fundA/period-figures"
        val body = given().queryParam("periodStart", "$start").queryParam("periodEnd", "$today")
            .`when`().get(path).then().log().ifValidationFails().statusCode(200)
            .body("units.opening", equalTo(0.0f))
            .body("units.issued", equalTo(1000.0f))
            .body("units.closing", equalTo(1000.0f))
            .body("flows.subscriptions", equalTo(1000.0f))
            .body("participants.holders", equalTo(1))
            .body("portfolio.holdingsCount", equalTo(0))
            .extract().asString()
        // Aggregate only: the contract the IT subscribed for appears nowhere in the payload.
        assertThat(body).doesNotContain(contract.toString())
        // The same question twice is the same answer.
        val again = given().queryParam("periodStart", "$start").queryParam("periodEnd", "$today")
            .`when`().get(path).then().statusCode(200).extract().path<String>("fingerprint")
        assertThat(body).contains(again)

        // Absent parameter: 400, never a 500 (#3104). Malformed date: 400.
        given().queryParam("periodEnd", "$today").`when`().get(path).then().statusCode(400)
        given().queryParam("periodStart", "nope").queryParam("periodEnd", "$today")
            .`when`().get(path).then().statusCode(400)
        // A period before any NAV: not reportable (409), never a report of zeroes.
        given().queryParam("periodStart", "2001-01-01").queryParam("periodEnd", "2001-01-31")
            .`when`().get(path).then().statusCode(409)
        given().queryParam("periodStart", "$start").queryParam("periodEnd", "$today")
            .`when`().get("/api/v1/reporting/funds/${UUID.randomUUID()}/period-figures").then().statusCode(404)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "cust-1", roles = ["ROLE_CUSTOMER"])
    fun `a customer cannot reach the reporting read model`() {
        val today = LocalDate.now(ZoneOffset.UTC)
        given().queryParam("periodStart", "${today.withDayOfMonth(1)}").queryParam("periodEnd", "$today")
            .`when`().get("/api/v1/reporting/funds/$fundA/period-figures").then().statusCode(403)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `a position recorded without a class is UNCLASSIFIED and the maker can only propose a class`() {
        loanNav = post(
            "/api/v1/funds/$fundB/navs",
            """{"valuationDate":"${LocalDate.now(ZoneOffset.UTC)}","cash":0,
                "positions":[{"instrumentId":"LOAN-1","quantity":1,"price":100},
                             {"instrumentId":"CZ-BOND-1","quantity":2,"price":50,"instrumentClass":"DEBT_SECURITY"}]}""",
        ).then().statusCode(201).extract().path("id")
        loanPosition = given().`when`().get("/api/v1/navs/$loanNav/positions").then().statusCode(200)
            .body("find { it.instrumentId == 'LOAN-1' }.instrumentClass", equalTo("UNCLASSIFIED"))
            .body("find { it.instrumentId == 'CZ-BOND-1' }.instrumentClass", equalTo("DEBT_SECURITY"))
            .extract().path("find { it.instrumentId == 'LOAN-1' }.positionId")

        // Missing fields are a 400, never a 500.
        post("/api/v1/position-classification-corrections", """{"positionId":"$loanPosition"}""").then().statusCode(400)
        correction = post(
            "/api/v1/position-classification-corrections",
            """{"positionId":"$loanPosition","toClass":"LOAN","reason":"depositary statement"}""",
        ).then().statusCode(200).body("status", equalTo("PROPOSED")).body("fromClass", equalTo("UNCLASSIFIED"))
            .extract().path("id")
        // The proposer is not the checker.
        post("/api/v1/position-classification-corrections/$correction/approve").then().statusCode(403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `the checker approves the class, the position row itself is never rewritten`() {
        post("/api/v1/position-classification-corrections/$correction/approve").then().statusCode(200)
            .body("status", equalTo("APPROVED")).body("decidedBy", equalTo("checker"))
        post("/api/v1/position-classification-corrections/$correction/approve").then().statusCode(409)
        given().`when`().get("/api/v1/navs/$loanNav/positions").then().statusCode(200)
            .body("find { it.instrumentId == 'LOAN-1' }.instrumentClass", equalTo("LOAN"))

        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.prepareStatement("select instrument_class from fund_nav_positions where id = ?").use { st ->
                st.setObject(1, UUID.fromString(loanPosition))
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("UNCLASSIFIED")
                }
            }
            c.prepareStatement(
                "select status, proposed_by, decided_by from position_classification_corrections where id = ?",
            ).use { st ->
                st.setObject(1, UUID.fromString(correction))
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("APPROVED")
                    assertThat(rs.getString(2)).isEqualTo("maker")
                    assertThat(rs.getString(3)).isEqualTo("checker")
                }
            }
        }
    }
}
