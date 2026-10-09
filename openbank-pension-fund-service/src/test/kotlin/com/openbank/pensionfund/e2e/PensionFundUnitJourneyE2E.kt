// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.e2e

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.path.json.config.JsonPathConfig
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Fund-side end-to-end journey for the pension lifecycle (ADR-0334, issue #12350 slice S7):
 * contributions buy units at the NEXT NAV, a strategy change switches units at the next NAV of
 * each fund, and operators run NAV publication and strategy changes under four-eyes.
 *
 * Same mechanism as the fleet's other `*JourneyE2E` classes: `@QuarkusTest` + RestAssured over
 * the real HTTP surface and a real Postgres. Four-eyes is only meaningful between two DIFFERENT
 * authenticated principals, so the maker and checker steps are separate ordered methods, each with
 * its own `@TestSecurity` identity, sharing state through the companion object.
 *
 * pension-service is the only client of the order routes (FundAdministrationPort); its calls are
 * reproduced here as `ROLE_API`. The trigger from a booked contribution or an elected strategy to
 * these orders is S3 and is not travelled.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PensionFundUnitJourneyE2E {

    companion object {
        val contract: UUID = UUID.randomUUID()
        val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
        lateinit var conservative: String
        lateinit var growth: String
        lateinit var strategy: String
        lateinit var change: String
        lateinit var rejectedChange: String
        lateinit var launchNav: String
        lateinit var nextNav: String
        lateinit var growthNav: String
        lateinit var secondOrder: String
        lateinit var switchOrder: String
        lateinit var nextNavPerUnit: BigDecimal

        const val JSON = "application/json"

        /** Money, units and NAVs compared exactly: the default JsonPath parses numbers as float. */
        val EXACT: JsonPathConfig = JsonPathConfig.jsonPathConfig().numberReturnType(
            JsonPathConfig.NumberReturnType.BIG_DECIMAL,
        )
    }

    /** Scenario (c, maker) and (a, first contribution): funds, strategy, a change, the first order and a NAV. */
    @Test
    @Order(1)
    @TestSecurity(user = "fund-maker", roles = ["ROLE_OPERATOR"])
    fun `maker sets up funds and a strategy, proposes a change and calculates the launch NAV`() {
        conservative = created(post("/api/v1/funds", fund(conservative = true)), "fund").getString("id")
        growth = created(post("/api/v1/funds", fund(conservative = false)), "fund").getString("id")

        strategy = created(
            post("/api/v1/strategies", """{"name":"Balanced E2E","allocations":${allocation("0.7", "0.3")}}"""),
            "strategy",
        ).getString("id")

        change = created(
            post(
                "/api/v1/strategies/$strategy/changes",
                """{"allocations":${allocation(
                    "0.5",
                    "0.5",
                )},"reason":"rebalance","effectiveDate":"${today.plusDays(60)}"}""",
            ),
            "change",
        ).also { assertThat(it.getString("status")).isEqualTo("PENDING_APPROVAL") }.getString("id")
        rejectedChange = created(
            post(
                "/api/v1/strategies/$strategy/changes",
                """{"allocations":${allocation(
                    "0.1",
                    "0.9",
                )},"reason":"too aggressive","effectiveDate":"${today.plusDays(60)}"}""",
            ),
            "change",
        ).getString("id")

        // The first contribution, as pension-service would place it.
        val order =
            post(
                "/api/v1/contracts/$contract/orders",
                """{"fundId":"$conservative","type":"SUBSCRIBE","amount":1700}""",
            )
        assertThat(order.statusCode).describedAs(order.body.asString()).isEqualTo(202)
        assertThat(order.jsonPath().getString("status")).isEqualTo("PENDING")

        launchNav = created(post("/api/v1/funds/$conservative/navs", """{"valuationDate":"$today","cash":0}"""), "nav")
            .also { assertThat(it.getString("status")).isEqualTo("CALCULATED") }
            .getString("id")

        // Four-eyes: the maker can neither publish nor reject their own NAV, nor approve their own change.
        assertThat(post("/api/v1/navs/$launchNav/approve").statusCode).isEqualTo(403)
        assertThat(post("/api/v1/navs/$launchNav/reject").statusCode).isEqualTo(403)
        assertThat(post("/api/v1/strategy-changes/$change/approve").statusCode).isEqualTo(403)
        assertThat(
            holdings().getList<Any>("holdings"),
        ).describedAs("nothing is priced before a NAV is published").isEmpty()
    }

    /** Scenario (c, checker) and (a): publishing settles the queued contribution at that NAV. */
    @Test
    @Order(2)
    @TestSecurity(user = "fund-checker", roles = ["ROLE_OPERATOR"])
    fun `checker publishes the launch NAV, which buys the first contribution's units, and decides the changes`() {
        val published = post("/api/v1/navs/$launchNav/approve")
        assertThat(published.statusCode).describedAs(published.body.asString()).isEqualTo(200)
        assertThat(published.jsonPath(EXACT).getString("nav.status")).isEqualTo("PUBLISHED")
        assertThat(published.jsonPath(EXACT).getString("nav.approvedBy")).isEqualTo("fund-checker")
        assertThat(published.jsonPath(EXACT).getInt("settledOrders")).isEqualTo(1)

        val approved = post("/api/v1/strategy-changes/$change/approve")
        assertThat(approved.statusCode).describedAs(approved.body.asString()).isEqualTo(200)
        assertThat(approved.jsonPath().getString("status")).isEqualTo("APPROVED")
        assertThat(post("/api/v1/strategy-changes/$change/apply").statusCode)
            .describedAs("an approved change cannot take effect before the participant notice period ends")
            .isEqualTo(409)
        assertThat(post("/api/v1/strategy-changes/$rejectedChange/reject").jsonPath().getString("status"))
            .isEqualTo("REJECTED")
    }

    /** Scenario (a): units at the launch price; a later contribution waits for the NEXT NAV, never the known one. */
    @Test
    @Order(3)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `the first contribution holds units at the launch NAV and a second one waits for the next NAV`() {
        val holding = holdings()
        assertThat(BigDecimal(holding.getString("holdings.find { it.fundId == '$conservative' }.units")))
            .isEqualByComparingTo("1700")
        assertThat(BigDecimal(holding.getString("holdings.find { it.fundId == '$conservative' }.value")))
            .isEqualByComparingTo("1700")

        val key = UUID.randomUUID().toString()
        val body = """{"fundId":"$conservative","type":"SUBSCRIBE","amount":500}"""
        val second = post("/api/v1/contracts/$contract/orders", body, key)
        assertThat(second.statusCode).describedAs(second.body.asString()).isEqualTo(202)
        secondOrder = second.jsonPath().getString("id")
        // A contribution retried by pension-service after a lost response buys units once, not twice.
        val retried = post("/api/v1/contracts/$contract/orders", body, key)
        assertThat(retried.statusCode).describedAs(retried.body.asString()).isIn(200, 202)
        assertThat(retried.jsonPath().getString("id")).isEqualTo(secondOrder)
        assertThat(holdings().getList<String>("pendingOrders.id"))
            .describedAs("an order placed after a NAV is published is not priced at it (forward pricing)")
            .contains(secondOrder)
    }

    /** Scenario (c, maker): the next day's NAV, from the fund's assets. */
    @Test
    @Order(4)
    @TestSecurity(user = "fund-maker", roles = ["ROLE_OPERATOR"])
    fun `maker calculates the next NAV from the fund's assets`() {
        // 1 700 units outstanding, the fund grew to 1 800 cash: NAV ~ 1.0588 less one day of fee.
        val nav = created(
            post("/api/v1/funds/$conservative/navs", """{"valuationDate":"${today.plusDays(1)}","cash":1800}"""),
            "nav",
        )
        nextNav = nav.getString("id")
        nextNavPerUnit = BigDecimal(nav.getString("navPerUnit"))
        assertThat(
            nextNavPerUnit,
        ).isGreaterThan(BigDecimal.ONE).isLessThan(BigDecimal("1800").divide(BigDecimal("1700"), 6, RoundingMode.UP))
        assertThat(BigDecimal(nav.getString("unitsOutstanding"))).isEqualByComparingTo("1700")
    }

    /** Scenario (c, checker) and (a): the second contribution buys units at exactly the published next NAV. */
    @Test
    @Order(5)
    @TestSecurity(user = "fund-checker", roles = ["ROLE_OPERATOR"])
    fun `checker publishes the next NAV and the waiting contribution is priced at it`() {
        val published = post("/api/v1/navs/$nextNav/approve")
        assertThat(published.statusCode).describedAs(published.body.asString()).isEqualTo(200)
        assertThat(published.jsonPath(EXACT).getInt("settledOrders")).isEqualTo(1)
    }

    /** Scenario (a) read-back and (b): the second purchase, then a strategy-driven switch at the next NAV. */
    @Test
    @Order(6)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `the second purchase is priced at the next NAV and a switch out is placed`() {
        val tx = transactions()
        val second = tx.getMap<String, Any>("find { it.orderId == '$secondOrder' }")
        assertThat(second).describedAs("transactions: %s", tx.prettify()).isNotNull()
        assertThat(BigDecimal(second["navPerUnit"].toString())).isEqualByComparingTo(nextNavPerUnit)
        assertThat(BigDecimal(second["units"].toString()))
            .isEqualByComparingTo(BigDecimal("500").divide(nextNavPerUnit, 6, RoundingMode.DOWN))

        val expectedUnits = BigDecimal("1700").add(BigDecimal(second["units"].toString()))
        assertThat(BigDecimal(holdings().getString("holdings.find { it.fundId == '$conservative' }.units")))
            .isEqualByComparingTo(expectedUnits)

        // The participant moved to a growth strategy: switch 1 000 units out of the conservative fund.
        val switch = post(
            "/api/v1/contracts/$contract/orders",
            """{"fundId":"$conservative","type":"SWITCH_OUT","units":1000,"targetFundId":"$growth"}""",
        )
        assertThat(switch.statusCode).describedAs(switch.body.asString()).isEqualTo(202)
        switchOrder = switch.jsonPath().getString("id")

        // A switch cannot be placed for more units than the contract will hold — refused at settlement
        // at the latest; a SWITCH_IN can never be placed directly.
        assertThat(
            post(
                "/api/v1/contracts/$contract/orders",
                """{"fundId":"$growth","type":"SWITCH_IN","amount":10}""",
            ).statusCode,
        ).isEqualTo(400)
    }

    /** Scenario (b): both legs of the switch are forward-priced, each at its own fund's next NAV. */
    @Test
    @Order(7)
    @TestSecurity(user = "fund-maker", roles = ["ROLE_OPERATOR"])
    fun `maker calculates the next NAVs of both funds`() {
        created(
            post("/api/v1/funds/$conservative/navs", """{"valuationDate":"${today.plusDays(2)}","cash":2300}"""),
            "nav",
        )
            .getString("id").also { nextNav = it }
        // The growth fund still has no units: it prices at its launch NAV.
        growthNav =
            created(post("/api/v1/funds/$growth/navs", """{"valuationDate":"${today.plusDays(2)}","cash":0}"""), "nav")
                .getString("id")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "fund-checker", roles = ["ROLE_OPERATOR"])
    fun `checker publishes the source NAV first, then the target NAV`() {
        // Source first: settles the SWITCH_OUT and spawns the SWITCH_IN, which must wait for the
        // TARGET fund's NAV published after it.
        assertThat(post("/api/v1/navs/$nextNav/approve").jsonPath().getInt("settledOrders")).isEqualTo(1)
        assertThat(post("/api/v1/navs/$growthNav/approve").jsonPath().getInt("settledOrders")).isEqualTo(1)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `the switch moved exactly the proceeds of the units sold into the target fund`() {
        val tx = transactions()
        val out = tx.getMap<String, Any>("find { it.type == 'SWITCH_OUT' }")
        val into = tx.getMap<String, Any>("find { it.type == 'SWITCH_IN' }")
        assertThat(out).describedAs(tx.prettify()).isNotNull()
        assertThat(into).describedAs(tx.prettify()).isNotNull()
        assertThat(BigDecimal(out["units"].toString())).isEqualByComparingTo("1000")
        assertThat(BigDecimal(into["amount"].toString()))
            .describedAs("money conserved across the switch")
            .isEqualByComparingTo(BigDecimal(out["amount"].toString()))
        assertThat(BigDecimal(into["units"].toString()))
            .isEqualByComparingTo(
                BigDecimal(
                    into["amount"].toString(),
                ).divide(BigDecimal(into["navPerUnit"].toString()), 6, RoundingMode.DOWN),
            )

        val h = holdings()
        assertThat(BigDecimal(h.getString("holdings.find { it.fundId == '$growth' }.units")))
            .isEqualByComparingTo(BigDecimal(into["units"].toString()))
        assertThat(h.getList<Any>("pendingOrders")).isEmpty()
    }

    /** Scenario (f) — authorisation negatives on the fund side. */
    @Test
    @Order(10)
    @TestSecurity(user = "some-customer", roles = ["ROLE_VIEWER"])
    fun `a caller that is neither pension-service nor staff cannot place orders, read holdings or publish NAVs`() {
        assertThat(
            post(
                "/api/v1/contracts/$contract/orders",
                """{"fundId":"$conservative","type":"REDEEM","units":1}""",
            ).statusCode,
        ).isEqualTo(403)
        assertThat(given().`when`().get("/api/v1/contracts/$contract/holdings").statusCode).isEqualTo(403)
        assertThat(
            post(
                "/api/v1/funds/$conservative/navs",
                """{"valuationDate":"${today.plusDays(3)}","cash":1}""",
            ).statusCode,
        )
            .isEqualTo(403)
        assertThat(post("/api/v1/strategy-changes/$change/approve").statusCode).isEqualTo(403)
    }

    @Test
    @Order(11)
    @TestSecurity(user = "pension-service", roles = ["ROLE_API"])
    fun `pension-service itself cannot act as an operator`() {
        assertThat(post("/api/v1/funds", fund(conservative = false)).statusCode).isEqualTo(403)
        assertThat(post("/api/v1/navs/$launchNav/approve").statusCode).isEqualTo(403)
        assertThat(post("/api/v1/strategy-changes/$change/apply").statusCode).isEqualTo(403)
    }

    // ---------------------------------------------------------------------------------------------

    private fun fund(conservative: Boolean): String {
        val isin = "CZ" + UUID.randomUUID().toString().replace("-", "").uppercase().take(9) + (0..9).random()
        return """
            {"name":"E2E ${if (conservative) "Conservative" else "Growth"} $isin","isin":"$isin",
             "lei":"315700ABCDEF12345678","depositaryReference":"DEP-E2E","custodyAccountReference":"CUST-$isin",
             "currency":"CZK","riskClass":${if (conservative) 1 else 5},"mandatoryConservative":$conservative,
             "managementFeeRate":0.008,"launchNavPerUnit":1}
        """.trimIndent()
    }

    private fun allocation(a: String, b: String) = """
        [{"fundId":"$conservative","weight":$a,"lowerBand":0,"upperBand":1},
         {"fundId":"$growth","weight":$b,"lowerBand":0,"upperBand":1}]
    """.trimIndent()

    /** Every POST carries a fresh Idempotency-Key, as pension-service's FundAdministrationPort must. */
    private fun post(path: String, body: String? = null, key: String = UUID.randomUUID().toString()): Response =
        given().contentType(JSON).header("Idempotency-Key", key)
            .let { if (body != null) it.body(body) else it }.`when`().post(path)

    private fun created(response: Response, what: String): JsonPath {
        assertThat(response.statusCode).describedAs("create %s: %s", what, response.body.asString()).isEqualTo(201)
        return response.jsonPath(EXACT)
    }

    private fun holdings(): JsonPath {
        val r = given().`when`().get("/api/v1/contracts/$contract/holdings")
        assertThat(r.statusCode).describedAs(r.body.asString()).isEqualTo(200)
        return r.jsonPath(EXACT)
    }

    private fun transactions(): JsonPath {
        val r = given().`when`().get("/api/v1/contracts/$contract/transactions")
        assertThat(r.statusCode).describedAs(r.body.asString()).isEqualTo(200)
        return r.jsonPath(EXACT)
    }
}
