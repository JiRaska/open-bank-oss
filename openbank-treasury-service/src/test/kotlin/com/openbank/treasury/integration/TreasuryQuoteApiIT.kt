// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315 D9 over real HTTP, a real Postgres and the REAL scheduler: with the simulated market and
 * its quotes switched on (profile, literals only), `GET /quotes` serves the synthetic board off the
 * curve set ([FakeCurveSets] stands in for the risk engine, whose wire is pinned by the pact), and
 * the scheduled pass — not a direct call, which would supply a Vert.x context the scheduler does
 * not have — confirms and settles a deal struck at the counterparty's quote while leaving one
 * struck off it BOOKED.
 *
 * Flat 3.5 % curve, 30 days: mid 3.4570 %, SIMBK-A (5 bp) bid 3.4070 / ask 3.5070.
 */
@QuarkusTest
@TestProfile(TreasuryQuoteApiIT.QuotesOn::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryQuoteApiIT {

    /** Literal values only: a profile loads in a different classloader from the test (root CLAUDE.md). */
    class QuotesOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.simulated-market.enabled" to "true",
            "openbank.treasury.simulated-market.quotes.enabled" to "true",
            "openbank.treasury.simulated-market.interval" to "1s",
            "openbank.treasury.simulated-market.initial-delay" to "1s",
        )
    }

    @Inject
    lateinit var curves: FakeCurveSets

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun quotes(query: String) = given().`when`().get("/api/v1/treasury/quotes?$query")

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - the board is synthetic, per counterparty, off the curve set, and bad queries are 400`() {
        curves.reset()
        quotes("product=MM_PLACEMENT&currency=CZK&tenorDays=30").then().statusCode(200)
            .body("synthetic", equalTo(true))
            .body("quotes.counterpartyId", contains("SIMBK-A", "SIMBK-B", "SIMBK-C"))
            .body("quotes.synthetic", everyItem(equalTo(true)))
            .body("quotes[0].mid", equalTo(3.4570f))
            .body("quotes[0].bid", equalTo(3.4070f))
            .body("quotes[0].ask", equalTo(3.5070f))
            .body("quotes[0].dealRate", equalTo(3.4070f))
            .body("quotes[0].curveIndex", equalTo("CZEONIA"))
            .body("quotes[0].curveProvenance", equalTo("synthetic"))
            .body("quotes[0].curveSetId", equalTo(FakeCurveSets.ID.toString()))
        quotes("product=MM_BORROWING&currency=CZK&tenorDays=30").then().statusCode(200)
            .body("quotes[0].dealRate", equalTo(3.5070f))
        for (bad in listOf(
            "currency=CZK&tenorDays=30",
            "product=MM_PLACEMENT&tenorDays=30",
            "product=MM_PLACEMENT&currency=CZK",
            "product=NOPE&currency=CZK&tenorDays=30",
            "product=FX_SPOT&currency=CZK&tenorDays=30",
            "product=MM_PLACEMENT&currency=USD&tenorDays=30",
            "product=MM_PLACEMENT&currency=CZK&tenorDays=0",
            "product=MM_PLACEMENT&currency=CZK&tenorDays=366",
            "product=MM_PLACEMENT&currency=CZK&tenorDays=ten",
        )) {
            quotes(bad).then().statusCode(400)
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `2 - no curve set or an unreachable risk engine is 503, never an invented price`() {
        curves.set = null
        quotes("product=MM_PLACEMENT&currency=CZK&tenorDays=30").then().statusCode(503)
            .body("error", equalTo("QUOTE_UNAVAILABLE"))
        curves.reset()
        curves.down = true
        quotes("product=MM_PLACEMENT&currency=CZK&tenorDays=30").then().statusCode(503)
        curves.reset()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - a dealer books two placements - one at the quote, one over it`() {
        atQuote = draftAndSubmit("3.40")
        offQuote = draftAndSubmit("3.60")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `4 - the scheduled market confirms and settles the one at the quote, the other stays BOOKED`() {
        approve(atQuote)
        approve(offQuote)
        await { state(atQuote) == "SETTLED" }
        // Several more passes (1 s each) must still leave the off-quote deal for a person.
        Thread.sleep(SEVERAL_PASSES_MS)
        assertThat(state(offQuote)).isEqualTo("BOOKED")
        given().`when`().get("/api/v1/treasury/deals/$atQuote").then().statusCode(200)
            .body("history.find { it.to == 'CONFIRMED' }.actor", equalTo("system:simulated-market"))
            .body(
                "history.find { it.to == 'CONFIRMED' }.note",
                org.hamcrest.Matchers.containsString("synthetic quote bid 3.4070 / ask 3.5070"),
            )
        // A person can still confirm what the simulated counterparty declined.
        given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .`when`().post("/api/v1/treasury/deals/$offQuote/confirm").then().statusCode(200)
            .body("state", equalTo("CONFIRMED"))
    }

    private fun draftAndSubmit(rate: String): String {
        val id: String = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":1000.00,
                   "rate":$rate,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}""",
            )
            .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")
        given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .`when`().post("/api/v1/treasury/deals/$id/submit").then().statusCode(200)
        return id
    }

    private fun approve(id: String) {
        given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .`when`().post("/api/v1/treasury/deals/$id/approve").then().statusCode(200)
    }

    private fun state(id: String): String {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.prepareStatement("select state from deals where deal_id = ?").use { ps ->
                ps.setObject(1, UUID.fromString(id))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertThat(condition()).describedAs("the scheduled simulated market moved the deal").isTrue()
    }

    companion object {
        private lateinit var atQuote: String
        private lateinit var offQuote: String
        private const val TIMEOUT_NANOS = 20_000_000_000L
        private const val POLL_MS = 200L
        private const val SEVERAL_PASSES_MS = 3_000L
    }
}
