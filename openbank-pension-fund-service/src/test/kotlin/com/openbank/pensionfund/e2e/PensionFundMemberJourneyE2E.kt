// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.e2e

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * End-to-end journey of a pension participant's money through the fund unit register — from the
 * first subscription to a redemption and a fund switch — over the real HTTP surface against real
 * Postgres. The API ITs prove each endpoint in isolation; this suite proves the **member spine as
 * one continuous story**, which is what the collector's E2E row counts:
 *
 *  - **Forward pricing (ADR-0334)**: an order carries no price. A subscription queues PENDING until
 *    the fund's NEXT published NAV; a redemption of units settles at the next NAV after it was
 *    placed — never at a price already known when the order was placed.
 *  - **Four-eyes holds for NAV publication**: the maker of a NAV cannot publish it (403), a
 *    different checker publishes, and publication settles everything queued against that fund.
 *  - **A switch is two forward-priced legs**: the SWITCH_OUT settles at the source fund's next NAV
 *    and spawns the SWITCH_IN, which settles at the TARGET fund's next NAV.
 *  - **Idempotency**: a replayed key returns the same order; the register and the transaction
 *    ledger are the audit record of the whole spine.
 *  - **Least privilege**: participants never reach this service directly — a customer is refused
 *    403 on placing an order.
 *
 * Both funds run with `managementFeeRate = 0` (a legal fraction in [0, 1)) so the forward-pricing
 * math is exact: every figure asserted below is derivable on paper, and the suite still reads the
 * published NAV back from the wire rather than trusting its own arithmetic. Literal values only:
 * a QuarkusTestProfile loads in a different classloader from the test class (root CLAUDE.md) — no
 * profile is needed here beyond the shared test application.properties.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PensionFundMemberJourneyE2E {

    companion object {
        // Quarkus tests run one instance per method: the journey state lives on the companion.
        val contract: UUID = UUID.randomUUID()
        lateinit var fundA: String
        lateinit var fundB: String
        lateinit var nav1: String
        lateinit var nav2: String
        lateinit var nav3: String
        lateinit var navB1: String
    }

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun fundBody(isin: String) = """
        {"name":"Journey Fund $isin","isin":"$isin","lei":"315700ABCDEF12345678","depositaryReference":"DEP-J",
         "custodyAccountReference":"CUST-$isin","currency":"CZK","riskClass":3,"mandatoryConservative":false,
         "managementFeeRate":0,"launchNavPerUnit":1}
    """.trimIndent()

    private fun post(path: String, body: String? = null) = given().contentType("application/json")
        .let { if (body != null) it.body(body) else it }
        .`when`().post(path)

    @Test
    @Order(1)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `1 - the operator onboards two zero-fee CZK funds`() {
        fundA = post("/api/v1/funds", fundBody("CZ0008474101")).then().statusCode(201)
            .body("status", equalTo("ACTIVE")).extract().path<String>("id")
        fundB = post("/api/v1/funds", fundBody("CZ0008474119")).then().statusCode(201)
            .body("status", equalTo("ACTIVE")).extract().path<String>("id")
        // A duplicate ISIN is refused, never two funds under one identity.
        post("/api/v1/funds", fundBody("CZ0008474101")).then().statusCode(409)
        given().`when`().get("/api/v1/funds/$fundA").then().statusCode(200)
            .body("isin", equalTo("CZ0008474101")).body("currency", equalTo("CZK"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `2 - the member's subscription queues pending, priced only by the next published NAV`() {
        val order = """{"fundId":"$fundA","type":"SUBSCRIBE","amount":1000}"""
        val placed = given().contentType("application/json").header("Idempotency-Key", "journey-sub-1").body(order)
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(202).body("status", equalTo("PENDING")).extract().path<String>("id")
        // A replay with the same key returns the same order: one order, not two.
        given().contentType("application/json").header("Idempotency-Key", "journey-sub-1").body(order)
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(202).body("id", equalTo(placed))
        // The same key bound to a DIFFERENT instruction is a conflict, never silently one of them.
        given().contentType("application/json").header("Idempotency-Key", "journey-sub-1")
            .body("""{"fundId":"$fundA","type":"SUBSCRIBE","amount":2000}""")
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(409)
        // Nothing settled yet: the register holds no units before a NAV exists.
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().statusCode(200)
            .body("holdings", hasSize<Any>(0))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `3 - the maker calculates the first NAV and cannot publish their own calculation`() {
        nav1 = post("/api/v1/funds/$fundA/navs", """{"valuationDate":"$today","cash":0}""")
            .then().statusCode(201).body("status", equalTo("CALCULATED")).extract().path("id")
        // Four-eyes: the calculator is not the publisher.
        post("/api/v1/navs/$nav1/approve").then().statusCode(403)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `4 - the checker publishes and the queued subscription settles at the launch NAV`() {
        post("/api/v1/navs/$nav1/approve").then().statusCode(200)
            .body("nav.status", equalTo("PUBLISHED"))
            .body("settledOrders", equalTo(1))
        // No units were outstanding at valuation, so the launch NAV of 1.0 priced the issue:
        // 1000 CZK became exactly 1000 units.
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().statusCode(200)
            .body("holdings[0].fundId", equalTo(fundA))
            .body("holdings[0].units", equalTo(1000.0f))
            .body("holdings[0].value", equalTo(1000.0f))
            .body("pendingOrders.size()", equalTo(0))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `5 - a redemption of units queues, forward-priced like the subscription`() {
        given().contentType("application/json").header("Idempotency-Key", "journey-red-1")
            .body("""{"fundId":"$fundA","type":"REDEEM","units":400}""")
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(202).body("status", equalTo("PENDING"))
        // A redemption with an amount instead of units is a client error, never a mispriced order.
        given().contentType("application/json").header("Idempotency-Key", "journey-red-bad")
            .body("""{"fundId":"$fundA","type":"REDEEM","amount":400}""")
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(400)
        // The units are still held until the next NAV publishes.
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().statusCode(200)
            .body("holdings[0].units", equalTo(1000.0f))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `6 - the maker calculates the next NAV on the fund's 1000 CZK cash`() {
        // The fund holds the subscribed 1000 CZK in cash; zero fee, so the NAV stays exactly 1.0.
        // The maker only calculates — publishing is the checker's four-eyes step (test 7).
        nav2 = post("/api/v1/funds/$fundA/navs", """{"valuationDate":"${today.plusDays(1)}","cash":1000}""")
            .then().statusCode(201).extract().path("id")
        post("/api/v1/navs/$nav2/approve").then().statusCode(403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `7 - the checker publishes and the redemption settles at the next NAV`() {
        post("/api/v1/navs/$nav2/approve").then().statusCode(200)
            .body("settledOrders", equalTo(1))
            .body("nav.navPerUnit", equalTo(1.0f))
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().statusCode(200)
            .body("holdings[0].units", equalTo(600.0f))
            .body("holdings[0].value", equalTo(600.0f))
        // The transaction ledger is the audit record: both legs priced at the published NAVs.
        given().`when`().get("/api/v1/contracts/$contract/transactions").then().statusCode(200)
            .body("type", hasItem("SUBSCRIBE"))
            .body("type", hasItem("REDEEM"))
            .body("find { it.type == 'REDEEM' }.units", equalTo(400.0f))
            .body("find { it.type == 'REDEEM' }.amount", equalTo(400.0f))
            .body("find { it.type == 'REDEEM' }.navPerUnit", equalTo(1.0f))
            .body("find { it.type == 'REDEEM' }.navId", equalTo(nav2))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `8 - a switch out is placed against the target fund`() {
        given().contentType("application/json").header("Idempotency-Key", "journey-switch-1")
            .body("""{"fundId":"$fundA","type":"SWITCH_OUT","units":100,"targetFundId":"$fundB"}""")
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(202).body("status", equalTo("PENDING"))
        // Switching into the same fund is a client error.
        given().contentType("application/json").header("Idempotency-Key", "journey-switch-self")
            .body("""{"fundId":"$fundA","type":"SWITCH_OUT","units":10,"targetFundId":"$fundA"}""")
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(400)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `9 - the maker calculates both NAVs for the switch legs`() {
        nav3 = post("/api/v1/funds/$fundA/navs", """{"valuationDate":"${today.plusDays(2)}","cash":600}""")
            .then().statusCode(201).extract().path("id")
        navB1 = post("/api/v1/funds/$fundB/navs", """{"valuationDate":"${today.plusDays(2)}","cash":0}""")
            .then().statusCode(201).extract().path("id")
    }

    @Test
    @Order(10)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `10 - publishing settles the switch out, spawns the switch in, and both funds price their leg`() {
        // The source fund's NAV settles the SWITCH_OUT and spawns the SWITCH_IN for the proceeds.
        post("/api/v1/navs/$nav3/approve").then().statusCode(200)
            .body("settledOrders", equalTo(1))
        // The SWITCH_IN is queued against the TARGET fund until its own NAV publishes.
        post("/api/v1/navs/$navB1/approve").then().statusCode(200)
            .body("settledOrders", equalTo(1))
        // 100 units left fund A at 1.0 (proceeds 100 CZK), which bought 100 units of fund B at its
        // launch NAV of 1.0.
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().log().ifValidationFails().statusCode(200)
            .body("holdings.fundId", hasItem(fundA))
            .body("holdings.fundId", hasItem(fundB))
            .body("holdings.find { it.fundId == '$fundA' }.units", equalTo(500.0f))
            .body("holdings.find { it.fundId == '$fundA' }.value", equalTo(500.0f))
            .body("holdings.find { it.fundId == '$fundB' }.units", equalTo(100.0f))
            .body("holdings.find { it.fundId == '$fundB' }.value", equalTo(100.0f))
        given().`when`().get("/api/v1/contracts/$contract/transactions").then().statusCode(200)
            .body("type", hasItem("SWITCH_OUT"))
            .body("type", hasItem("SWITCH_IN"))
            .body("find { it.type == 'SWITCH_OUT' }.units", equalTo(100.0f))
            .body("find { it.type == 'SWITCH_IN' }.units", equalTo(100.0f))
        // Every priced transaction points at the NAV that priced it.
        given().`when`().get("/api/v1/contracts/$contract/transactions").then().statusCode(200)
            .body("find { it.type == 'SWITCH_OUT' }.navId", equalTo(nav3))
            .body("find { it.type == 'SWITCH_IN' }.navId", equalTo(navB1))
            .body("navPerUnit", notNullValue())
    }

    @Test
    @Order(11)
    @TestSecurity(user = "vera.customer", roles = ["ROLE_CUSTOMER"])
    fun `11 - a participant never reaches the register directly`() {
        given().contentType("application/json").header("Idempotency-Key", "customer-denied")
            .body("""{"fundId":"$fundA","type":"SUBSCRIBE","amount":1}""")
            .`when`().post("/api/v1/contracts/$contract/orders")
            .then().statusCode(403)
        given().`when`().get("/api/v1/contracts/$contract/holdings").then().statusCode(403)
    }
}
