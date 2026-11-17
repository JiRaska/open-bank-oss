// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The participant-dealing journey over the real HTTP surface: an operator opens two CZK funds,
 * a participant subscribes (idempotent on the order key), a second operator publishes the NAV
 * which settles the queued order, holdings read back valued, and the participant then switches
 * part of the units into the second fund — settled by the next NAV publication and observable
 * through the holdings route.
 *
 * Ordered, because the lifecycle is a state machine — each step is only reachable from the
 * previous one, and `@TestSecurity` fixes one identity per method (the maker and the NAV
 * checker must be different principals).
 *
 * The module's ITs prove one property each (four-eyes parity across API versions, replay
 * idempotency, cross-currency refusal, reporting aggregates); this proves the connected
 * operator journey across a SWITCH — the money movement between two funds whose only
 * settlement trigger is NAV publication — so a wiring regression that deregisters a route
 * (#3371), breaks NAV-settled order flow, or mis-values holdings fails here even when every
 * property-level assertion stays green.
 *
 * Everything — fund registration, order pipeline, NAV calculation and publication,
 * unit-holder accounting, authorization — is the real, CDI-wired code over Testcontainers
 * Postgres.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_pension_fund_e2e")],
)
@TestProfile(PensionSwitchLifecycleE2E.JourneyProfile::class)
class PensionSwitchLifecycleE2E {

    /** Own boot (own container DB), mirroring the ITs' isolation. */
    class JourneyProfile : QuarkusTestProfile

    private val contract: UUID = UUID.randomUUID()
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val tomorrow: LocalDate = today.plusDays(1)

    private var fundA: String = ""
    private var fundB: String = ""
    private var nav: String = ""
    private var nav2: String = ""
    private var navB: String = ""

    @Test
    @Order(1)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `1 - the operator registers two CZK funds and reads one back`() {
        fundA = createFund("A")
        fundB = createFund("B")

        given().get("/api/v2/funds/$fundA").then()
            .statusCode(200)
            .body("id", equalTo(fundA))
            .body("status", equalTo("ACTIVE"))
            .body("currency", equalTo("CZK"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `2 - a subscription is queued pending and an identical retry replays it`() {
        val orderKey = UUID.randomUUID().toString()
        val order = """{"fundId":"$fundA","type":"SUBSCRIBE","amount":1000}"""

        val placed = given().contentType("application/json")
            .header("Idempotency-Key", orderKey)
            .body(order)
            .post("/api/v2/contracts/$contract/orders").then()
            .statusCode(202)
            .body("status", equalTo("PENDING"))
            .extract().path<String>("id")

        // A retry with the same key returns the same order: one order, not two.
        given().contentType("application/json")
            .header("Idempotency-Key", orderKey)
            .body(order)
            .post("/api/v2/contracts/$contract/orders").then()
            .statusCode(202)
            .body("id", equalTo(placed))

        given().get("/api/v2/contracts/$contract/orders").then()
            .statusCode(200)
            .body("", hasSize<Any>(1))
            .body("[0].status", equalTo("PENDING"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `3 - the maker calculates the NAV but cannot approve their own valuation`() {
        nav = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"valuationDate":"$today","cash":0}""")
            .post("/api/v2/funds/$fundA/navs").then()
            .statusCode(201)
            .body("status", equalTo("CALCULATED"))
            .extract().path("id")

        given().header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/navs/$nav/approve").then()
            .statusCode(403)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "pension-e2e-checker", roles = ["ROLE_OPERATOR"])
    fun `4 - a different operator publishes the NAV, settling the subscription`() {
        given().header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/navs/$nav/approve").then()
            .statusCode(200)
            .body("nav.status", equalTo("PUBLISHED"))
            .body("settledOrders", equalTo(1))

        // Holdings — valued at the published NAV (1 CZK per unit) — read back per fund.
        given().get("/api/v2/contracts/$contract/holdings").then()
            .statusCode(200)
            .body("holdings.find { it.fundId == '$fundA' }.units", equalTo(1000.0f))
            .body("holdings.find { it.fundId == '$fundA' }.value", equalTo(1000.0f))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `5 - the participant switches 400 units into the second fund`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"fundId":"$fundA","type":"SWITCH_OUT","units":400,"targetFundId":"$fundB"}""")
            .post("/api/v2/contracts/$contract/orders").then()
            .statusCode(202)
            .body("status", equalTo("PENDING"))

        given().get("/api/v2/contracts/$contract/orders").then()
            .statusCode(200)
            .body("", hasSize<Any>(2))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `6 - maker calculates the next-day NAV that will settle the switch`() {
        // A same-day NAV would be a CORRECTION of the published one (correctsNavId) and would
        // re-price, not settle. Settlement needs a NAV with a valuation date of its own. The cash
        // is the settled subscription: a 0-cash NAV over 1000 outstanding units prices at zero and
        // the calculator refuses (409 "NAV per unit must be positive").
        nav2 = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"valuationDate":"$tomorrow","cash":1000}""")
            .post("/api/v2/funds/$fundA/navs").then()
            .statusCode(201)
            .body("status", equalTo("CALCULATED"))
            .extract().path<String>("id")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "pension-e2e-checker", roles = ["ROLE_OPERATOR"])
    fun `7 - checker publishes the NAV, the switch-out settles, and fund A holds 600 units`() {
        given().header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/navs/$nav2/approve").then()
            .statusCode(200)
            .body("nav.status", equalTo("PUBLISHED"))
            .body("settledOrders", equalTo(1))

        given().get("/api/v2/contracts/$contract/holdings").then()
            .statusCode(200)
            .body("holdings.find { it.fundId == '$fundA' }.units", equalTo(600.0f))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `8 - maker calculates the first NAV of fund B`() {
        navB = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"valuationDate":"$tomorrow","cash":0}""")
            .post("/api/v2/funds/$fundB/navs").then()
            .statusCode(201)
            .body("status", equalTo("CALCULATED"))
            .extract().path<String>("id")
    }

    @Test
    @Order(9)
    @TestSecurity(user = "pension-e2e-checker", roles = ["ROLE_OPERATOR"])
    fun `9 - checker publishes fund B NAV and the switch-in lands as units of fund B`() {
        given().header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/navs/$navB/approve").then()
            .statusCode(200)
            .body("nav.status", equalTo("PUBLISHED"))
            .body("settledOrders", equalTo(1))

        given().get("/api/v2/contracts/$contract/holdings").then()
            .statusCode(200)
            .body("holdings.find { it.fundId == '$fundA' }.units", equalTo(600.0f))

        // The switch-in bought fund B units with the switch-out proceeds priced at fund A's NAV:
        // 400 units x 0.999980 (one day of the 0.80% fee on the 1000 CZK cash) = 399.99 CZK,
        // issued at fund B's launch NAV of 1.00. Wire amounts are strings at scale — compare
        // numerically, never with a raw Float equality.
        val fundBUnits = given().get("/api/v2/contracts/$contract/holdings").then()
            .statusCode(200)
            .extract().path<Number>("holdings.find { it.fundId == '$fundB' }.units").toString()
        assertThat(BigDecimal(fundBUnits)).isEqualByComparingTo("399.990000")
    }

    @Test
    @Order(10)
    @TestSecurity(user = "pension-e2e-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `10 - compliance staff cannot place orders`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"fundId":"$fundA","type":"SUBSCRIBE","amount":1}""")
            .post("/api/v2/contracts/$contract/orders").then()
            .statusCode(403)
    }

    @Test
    @Order(11)
    @TestSecurity(user = "pension-e2e-maker", roles = ["ROLE_OPERATOR"])
    fun `11 - the dealing surface fails closed on unknown funds and a missing idempotency key`() {
        given().get("/api/v2/funds/${UUID.randomUUID()}").then().statusCode(404)
        // Without the key the money-path POST is refused, never queued (#8351).
        given().contentType("application/json")
            .body("""{"fundId":"$fundA","type":"SUBSCRIBE","amount":1}""")
            .post("/api/v2/contracts/$contract/orders").then()
            .statusCode(400)
    }

    private fun createFund(tag: String): String {
        val isin = "CZ" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).uppercase() + "7"
        return given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """
                {"name":"E2E Fund $tag $isin","isin":"$isin","lei":"315700ABCDEF12345678",
                 "depositaryReference":"DEP-E2E-$tag","custodyAccountReference":"CUST-E2E-$tag",
                 "currency":"CZK","riskClass":3,"mandatoryConservative":false,
                 "productRevisionId":"00000000-0000-0000-0000-000000000002","launchNavPerUnit":1}
                """.trimIndent(),
            )
            .post("/api/v2/funds").then()
            .statusCode(201)
            .body("status", equalTo("ACTIVE"))
            .body("id", notNullValue())
            .extract().path("id")
    }
}
