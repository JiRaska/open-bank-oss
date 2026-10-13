// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.sun.net.httpserver.HttpExchange
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
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val flows = assembled("PSP31-04", PensionProvidersStub.COMPANY, "2009-Q1")
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
    private lateinit var executor: ExecutorService
    private val concurrentCaptures = CountDownLatch(2)

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        snapshotRoutes()
        aggregateRoutes()
        server.start()
        val url = "http://localhost:${server.address.port}"
        return mapOf(
            "quarkus.rest-client.pension-fund-service.url" to url,
            "quarkus.rest-client.pension-service.url" to url,
            "openbank.statutory-returns.fund-ids" to "$FUND,$FUND2",
            "tax.source-test.stub-base" to url,
        )
    }

    private fun snapshotRoutes() {
        server.createContext("/api/v2/pension/reporting/participant-snapshots") { exchange ->
            val rootPath = "/api/v2/pension/reporting/participant-snapshots"
            val requestBody =
                if (exchange.requestMethod == "POST") exchange.requestBody.bufferedReader().readText() else ""
            val is2013 = exchange.requestURI.path == "$rootPath/$RACE_SNAPSHOT_ID" ||
                requestBody.contains("\"periodStart\":\"2013-01-01\"") &&
                requestBody.contains("\"periodEnd\":\"2013-12-31\"")
            val snapshotId = if (is2013) RACE_SNAPSHOT_ID else SNAPSHOT_ID
            val periodStart = if (is2013) "2013-01-01" else "2011-01-01"
            val periodEnd = if (is2013) "2013-12-31" else "2011-12-31"
            val payload = if (is2013) RACE_SOURCE_PAYLOAD else SOURCE_PAYLOAD
            val sha = if (is2013) RACE_SOURCE_SHA else SOURCE_SHA
            val receipt = mapOf(
                "snapshotId" to snapshotId,
                "companyId" to COMPANY,
                "periodStart" to periodStart,
                "periodEnd" to periodEnd,
                "revision" to 1,
                "payloadSchemaVersion" to "participant-period-aggregates/2.0",
                "sha256" to sha,
            )
            val (status, response) = when {
                acceptsCapture(exchange, rootPath, requestBody, periodStart, periodEnd) -> {
                    captureCount.incrementAndGet()
                    captureResponse(is2013, mapper.writeValueAsString(receipt))
                }
                acceptsSnapshotRead(exchange, rootPath, snapshotId) ->
                    200 to mapper.writeValueAsString(mapOf("receipt" to receipt, "payloadJson" to payload))
                else -> 404 to "{}"
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/__stats") { exchange ->
            val bytes = captureCount.get().toString().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun acceptsCapture(
        exchange: HttpExchange,
        rootPath: String,
        body: String,
        from: String,
        to: String,
    ): Boolean = exchange.requestMethod == "POST" &&
        exchange.requestURI.path == rootPath &&
        exchange.requestHeaders.getFirst("Idempotency-Key")?.isNotBlank() == true &&
        body.contains("\"companyId\":\"$COMPANY\"") &&
        body.contains("\"periodStart\":\"$from\"") &&
        body.contains("\"periodEnd\":\"$to\"")

    private fun acceptsSnapshotRead(exchange: HttpExchange, rootPath: String, snapshotId: String): Boolean =
        exchange.requestMethod == "GET" &&
            exchange.requestURI.path == "$rootPath/$snapshotId" &&
            exchange.requestURI.query == "companyId=$COMPANY"

    private fun captureResponse(is2013: Boolean, receiptJson: String): Pair<Int, String> {
        if (!is2013) return 200 to receiptJson
        concurrentCaptures.countDown()
        return if (concurrentCaptures.await(10, TimeUnit.SECONDS)) 200 to receiptJson else 503 to "{}"
    }

    private fun aggregateRoutes() {
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
    }

    override fun stop() {
        if (this::server.isInitialized) server.stop(0)
        if (this::executor.isInitialized) executor.shutdownNow()
    }

    /** The October answer re-dated to November with one unit lost from the closing balance. */
    private fun tamper(body: String): String = body
        .replace("2025-10-01", "2025-11-01").replace("2025-10-31", "2025-11-30")
        .replace("\"closing\" : 15437.882421", "\"closing\" : 15436.882421")

    private fun load(name: String): String =
        PensionProvidersStub::class.java.classLoader.getResourceAsStream("pension-golden/$name")!!
            .bufferedReader().readText()

    companion object {
        const val COMPANY = "00000000-0000-4000-8000-000000000001"
        const val SNAPSHOT_ID = "d45ca8df-f9dc-4573-bb7b-513219f33a96"
        const val RACE_SNAPSHOT_ID = "d45ca8df-f9dc-4573-bb7b-513219f33a97"
        val captureCount = AtomicInteger()
        private val mapper = jacksonObjectMapper()

        // Technical source-command fixture only. The recorded 2009 golden responses below are unchanged;
        // this reserved 2011 period exercises transport, hash and DB isolation, not regulator truth.
        private val SOURCE_PAYLOAD = PensionProvidersStub::class.java.classLoader
            .getResourceAsStream("pension-golden/participants-2009-year.json")!!.bufferedReader().readText()
            .replace("2009-01-01", "2011-01-01")
            .replace("2009-12-31", "2011-12-31")
        val SOURCE_SHA = MessageDigest.getInstance("SHA-256")
            .digest(SOURCE_PAYLOAD.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private val RACE_SOURCE_PAYLOAD = SOURCE_PAYLOAD
            .replace("2011-01-01", "2013-01-01")
            .replace("2011-12-31", "2013-12-31")
        private val RACE_SOURCE_SHA = MessageDigest.getInstance("SHA-256")
            .digest(RACE_SOURCE_PAYLOAD.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** The fund id the provider golden test assigned; recorded with the fixtures. */
        const val FUND = "0f0f2425-0000-4000-8000-000000002025"
        const val FUND2 = "0f0f2425-0000-4000-8000-000000002026"
        val requests = CopyOnWriteArrayList<String>()
        private const val FUNDS = "/api/v1/reporting/funds/$FUND/period-figures"
        private const val PARTICIPANTS = "/api/v2/pension/reporting/participant-aggregates"
        private val ROUTES = mapOf(
            "$FUNDS?periodStart=2025-10-01&periodEnd=2025-10-31" to "fund-month-2025-10.json",
            "$FUNDS?periodStart=2025-11-01&periodEnd=2025-11-30" to "fund-month-2025-10.json",
            "$FUNDS?periodStart=2025-10-01&periodEnd=2025-12-31" to "fund-quarter-2025-q4.json",
            "$FUNDS?periodStart=2025-01-01&periodEnd=2025-12-31" to "fund-year-2025.json",
            "$FUNDS?periodStart=2025-03-01&periodEnd=2025-03-31" to "fund-loss-month-2025-03.json",
            "$FUNDS?periodStart=2025-01-01&periodEnd=2025-03-31" to "fund-loss-quarter-2025-q1.json",
            "/api/v1/reporting/funds/$FUND2/period-figures?periodStart=2025-01-01&periodEnd=2025-03-31" to
                "fund-unclassified-quarter-2025-q1.json",
            "$PARTICIPANTS?companyId=$COMPANY&periodStart=2009-01-01&periodEnd=2009-03-31" to
                "participants-2009-q1.json",
            // pension-service's ParticipantReportingApiIT books nothing after March 2009, so the
            // year answer is the same YTD figures re-dated to the year end (PSP 40-01).
            "$PARTICIPANTS?companyId=$COMPANY&periodStart=2009-01-01&periodEnd=2009-12-31" to
                "participants-2009-year.json",
        )
    }
}
