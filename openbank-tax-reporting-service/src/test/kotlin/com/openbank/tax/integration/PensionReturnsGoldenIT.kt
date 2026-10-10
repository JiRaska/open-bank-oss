// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.path.json.config.JsonPathConfig
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Golden end to end (#12425): the ČNB PSP 10-12 / 20-12 / 30-12 / 34-12, PEF 12-04 / 14-04 /
 * 15-01 and PSP 31-04 returns assembled through the published route, the real REST adapters, the
 * real catalogue rules and Postgres — from provider responses RECORDED off the providers' own
 * golden tests:
 *
 * - `pension-golden/fund-*.json` is pension-fund-service's `FundReportingGoldenTest` month (Sept
 *   close, then October subscriptions, a redemption, a unit fee, a switch out and two positions),
 *   run for 2025 and serialised through its `FundPeriodFiguresResponse`;
 * - `pension-golden/participants-2009-q1.json` is pension-service's `ParticipantReportingApiIT`
 *   month (March 2009) with the year-to-date figures that IT pins.
 *
 * Every assembly is a 200 only if the catalogue's arithmetic holds on real provider output
 * (assets = liabilities + equity, unit roll-forward, profit = gains - losses + other - fees, entitlement
 * roll-forward, contribution total). A tampered response breaking one of them is a 422, which is
 * what makes the 200s evidence rather than decoration.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_pension_golden_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@QuarkusTestResource(PensionProvidersStub::class)
class PensionReturnsGoldenIT {

    private fun assemble(code: String, entity: String, period: String) = given().contentType("application/json")
        .body("""{"catalogueId":"cz-pension-cnb","returnCode":"$code","entityId":"$entity","period":"$period"}""")
        .post("/api/v1/statutory-returns/assemble")

    private fun assembled(code: String, entity: String, period: String): Map<String, BigDecimal> {
        val body = assemble(code, entity, period).then().log().ifValidationFails().statusCode(200).extract().asString()
        // BIG_DECIMAL: RestAssured's default float parsing would round 1987.571714 to 1987.5717.
        val json = JsonPath(body).using(JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
        assertThat(json.getString("status")).isEqualTo("ASSEMBLED")
        return json.getMap<String, Any>("datapoints").mapValues { BigDecimal(it.value.toString()) }
    }

    private fun Map<String, BigDecimal>.v(k: String) = getValue(k)

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `fund returns assemble from the recorded provider month and reconcile`() {
        val bs = assembled("PSP10-12-FUND", FUND, "2025-10")
        assertThat(bs.v("total_assets")).isEqualByComparingTo(bs.v("total_liabilities") + bs.v("total_equity"))

        val pl = assembled("PSP20-12-FUND", FUND, "2025-10")
        assertThat(pl.v("revaluation_gains_ytd")).isEqualByComparingTo("100.00")
        assertThat(pl.v("revaluation_losses_ytd")).isEqualByComparingTo("0.00")
        assertThat(pl.v("management_fees_ytd")).isEqualByComparingTo("13.77")
        assertThat(pl.v("profit_loss_ytd")).isEqualByComparingTo("1292.44")
        assertThat(pl.v("profit_loss_ytd")).isEqualByComparingTo(
            pl.v("revaluation_gains_ytd") - pl.v("revaluation_losses_ytd") + pl.v("other_investment_result_ytd") -
                pl.v("management_fees_ytd"),
        )

        val units = assembled("PSP30-12", FUND, "2025-10")
        assertThat(units.v("units_opening")).isEqualByComparingTo("15000")
        assertThat(units.v("units_issued")).isEqualByComparingTo("1987.571714")
        assertThat(units.v("units_cancelled")).isEqualByComparingTo("1549.689293")
        assertThat(units.v("units_closing")).isEqualByComparingTo("15437.882421")
        assertThat(units.v("units_closing"))
            .isEqualByComparingTo(units.v("units_opening") + units.v("units_issued") - units.v("units_cancelled"))
        assertThat(units.v("fund_equity")).isEqualByComparingTo(bs.v("total_equity"))

        val portfolio = assembled("PSP34-12-FUND", FUND, "2025-10")
        assertThat(portfolio.v("holdings_count")).isEqualByComparingTo("2")
        assertThat(portfolio.v("holdings_carrying_value")).isEqualByComparingTo("16300.00")

        val pefBs = assembled("PEF12-04-FUND", FUND, "2025-Q4")
        assertThat(pefBs.v("total_equity")).isEqualByComparingTo(bs.v("total_equity"))

        val entitlements = assembled("PEF14-04", FUND, "2025-Q4")
        assertThat(entitlements.v("pension_entitlements_closing")).isEqualByComparingTo(
            entitlements.v("pension_entitlements_opening") + entitlements.v("entitlement_increase") -
                entitlements.v("entitlement_decrease"),
        )
        assertThat(entitlements.v("pension_entitlements_closing")).isEqualByComparingTo(bs.v("total_equity"))

        val participants = assembled("PEF15-01", FUND, "2025")
        assertThat(participants.v("participants_count")).isEqualByComparingTo("3")
        assertThat(participants.v("participants_contributing_count")).isEqualByComparingTo("3")

        // Each return asked its provider for exactly its own period.
        assertThat(PensionProvidersStub.requests).contains(
            "/api/v1/reporting/funds/$FUND/period-figures?periodStart=2025-10-01&periodEnd=2025-10-31",
            "/api/v1/reporting/funds/$FUND/period-figures?periodStart=2025-10-01&periodEnd=2025-12-31",
            "/api/v1/reporting/funds/$FUND/period-figures?periodStart=2025-01-01&periodEnd=2025-12-31",
        )
    }

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `a loss month assembles PSP 20-12 as a losses line, and PEF 13-04 reads the classified loans`() {
        // Recorded off pension-fund-service's FundLossAndClassificationTest: the bond fell 1 000.
        val pl = assembled("PSP20-12-FUND", FUND, "2025-03")
        assertThat(pl.v("profit_loss_ytd")).isEqualByComparingTo("-973.47")
        assertThat(pl.v("revaluation_gains_ytd")).isEqualByComparingTo("0.00")
        assertThat(pl.v("revaluation_losses_ytd")).isEqualByComparingTo("1000.00")
        assertThat(pl.v("other_investment_result_ytd")).isEqualByComparingTo("50.00")
        assertThat(pl.v("management_fees_ytd")).isEqualByComparingTo("23.47")

        val loans = assembled("PEF13-04", FUND, "2025-Q1")
        assertThat(loans.v("loans_outstanding")).isEqualByComparingTo("500.00")
    }

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `PEF 13-04 is unavailable while a closing position is unclassified, never zero`() {
        assemble("PEF13-04", FUND2, "2025-Q1").then().statusCode(503)
            .body(containsString("UNCLASSIFIED"))
    }

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `PSP 31-04 assembles from the recorded participant month and its contributions add up`() {
        val flows = assembled("PSP31-04", "company", "2009-Q1")
        assertThat(flows.v("contributions_participant_ytd")).isEqualByComparingTo("2300.00")
        assertThat(flows.v("contributions_employer_ytd")).isEqualByComparingTo("500.00")
        assertThat(flows.v("contributions_state_ytd")).isEqualByComparingTo("230.00")
        assertThat(flows.v("contributions_total_ytd")).isEqualByComparingTo("3030.00")
        assertThat(flows.v("payouts_total_ytd")).isEqualByComparingTo("125000.00")
        assertThat(flows.v("payout_cases_ytd")).isEqualByComparingTo("2")
        assertThat(flows.v("pensioners_count")).isEqualByComparingTo("1")
    }

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `a provider answer that breaks the roll-forward is refused, and unsourced returns stay 503`() {
        // November 2025 is served with units_closing one unit short of the roll-forward.
        assemble("PSP30-12", FUND, "2025-11").then().statusCode(422)
            .body(containsString("unit-roll-forward"))
        // A provider 409 (no NAV for the period) is unavailable, never zero.
        assemble("PSP10-12-FUND", FUND, "2025-12").then().statusCode(503)
            .body(containsString("409"))
        // The recorded October positions predate classification: loans are unknown, not zero.
        assemble("PEF13-04", FUND, "2025-Q4").then().statusCode(503)
            .body(containsString("loans"))
    }

    companion object {
        const val FUND = PensionProvidersStub.FUND
        const val FUND2 = PensionProvidersStub.FUND2
    }
}

/** Serves the recorded provider responses; anything else is a 409, which the adapter must surface as 503. */
class PensionProvidersStub : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            val target = exchange.requestURI.toString()
            requests += target
            val file = ROUTES[target]
            val body = file?.let { load(it) }
            val (status, payload) = when {
                body == null -> 409 to """{"error":"no published NAV backs the period"}"""
                target.contains("2025-11-01") -> 200 to tamper(body)
                else -> 200 to body
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://localhost:${server.address.port}"
        return mapOf(
            "quarkus.rest-client.pension-fund-service.url" to url,
            "quarkus.rest-client.pension-service.url" to url,
            "openbank.statutory-returns.fund-ids" to "$FUND,$FUND2",
        )
    }

    override fun stop() {
        if (this::server.isInitialized) server.stop(0)
    }

    /** The October answer re-dated to November with one unit lost from the closing balance. */
    private fun tamper(body: String): String = body
        .replace("2025-10-01", "2025-11-01").replace("2025-10-31", "2025-11-30")
        .replace("\"closing\" : 15437.882421", "\"closing\" : 15436.882421")

    private fun load(name: String): String =
        PensionProvidersStub::class.java.classLoader.getResourceAsStream("pension-golden/$name")!!
            .bufferedReader().readText()

    companion object {
        /** The fund id the provider golden test assigned; recorded with the fixtures. */
        const val FUND = "0f0f2425-0000-4000-8000-000000002025"
        const val FUND2 = "0f0f2425-0000-4000-8000-000000002026"
        val requests = CopyOnWriteArrayList<String>()
        private const val FUNDS = "/api/v1/reporting/funds/$FUND/period-figures"
        private val ROUTES = mapOf(
            "$FUNDS?periodStart=2025-10-01&periodEnd=2025-10-31" to "fund-month-2025-10.json",
            "$FUNDS?periodStart=2025-11-01&periodEnd=2025-11-30" to "fund-month-2025-10.json",
            "$FUNDS?periodStart=2025-10-01&periodEnd=2025-12-31" to "fund-quarter-2025-q4.json",
            "$FUNDS?periodStart=2025-01-01&periodEnd=2025-12-31" to "fund-year-2025.json",
            "$FUNDS?periodStart=2025-03-01&periodEnd=2025-03-31" to "fund-loss-month-2025-03.json",
            "$FUNDS?periodStart=2025-01-01&periodEnd=2025-03-31" to "fund-loss-quarter-2025-q1.json",
            "/api/v1/reporting/funds/$FUND2/period-figures?periodStart=2025-01-01&periodEnd=2025-03-31" to
                "fund-unclassified-quarter-2025-q1.json",
            "/api/v1/pension/reporting/participant-aggregates?periodStart=2009-01-01&periodEnd=2009-03-31" to
                "participants-2009-q1.json",
        )
    }
}
