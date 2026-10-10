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
 * Golden end to end (ADR-0337): ČNB PSP 10-12 PS, PSP 20-12 PS and PEF 12-04 PS assembled through
 * the published route, the real REST client, the real catalogue rules and Postgres, from a stub of
 * the pension company's ledger instance serving its frozen-trial-balance route exactly as ledger
 * does — 200 for a FROZEN LINES_V1 period, 409 for anything else.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_company_books_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@QuarkusTestResource(CompanyLedgerStub::class, restrictToAnnotatedClass = true)
class CompanyBooksGoldenIT {
    private fun assemble(code: String, period: String) = given().contentType("application/json")
        .body("""{"catalogueId":"cz-pension-cnb","returnCode":"$code","entityId":"company","period":"$period"}""")
        .post("/api/v1/statutory-returns/assemble")

    private fun datapoints(code: String, period: String): Map<String, BigDecimal> {
        val body = assemble(code, period).then().log().ifValidationFails().statusCode(200).extract().asString()
        return JsonPath(body).using(JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .getMap<String, Any>("datapoints").mapValues { BigDecimal(it.value.toString()) }
    }

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `the company returns assemble from its own frozen books and reconcile`() {
        val bs = datapoints("PSP10-12-PS", "2025-03")
        assertThat(bs.getValue("total_assets")).isEqualByComparingTo("50820000")
        assertThat(bs.getValue("total_liabilities")).isEqualByComparingTo("0")
        assertThat(bs.getValue("total_equity")).isEqualByComparingTo("50820000")

        val pl = datapoints("PSP20-12-PS", "2025-03")
        assertThat(pl.getValue("income_ytd")).isEqualByComparingTo("830000")
        assertThat(pl.getValue("expenses_ytd")).isEqualByComparingTo("910000")
        assertThat(pl.getValue("profit_loss_ytd")).isEqualByComparingTo("-80000")

        val pef = datapoints("PEF12-04-PS", "2025-Q1")
        assertThat(pef.getValue("total_equity")).isEqualByComparingTo(bs.getValue("total_equity"))

        assertThat(CompanyLedgerStub.requests).contains(
            "/api/v1/ledger/periods/YEAR/2024-01-01/frozen-trial-balance",
            "/api/v1/ledger/periods/MONTH/2025-03-01/frozen-trial-balance",
        )
    }

    @Test
    @TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
    fun `a month not yet frozen is 503 naming the ledger answer, and the company portfolio stays unsourced`() {
        assemble("PSP10-12-PS", "2025-04").then().statusCode(503).body(containsString("409"))
        assemble("PSP34-12-PS", "2025-Q1").then().statusCode(503).body(containsString("not a ledger fact"))
    }
}

/** The pension company's ledger: frozen evidence for 2024 and January–March 2025, 409 otherwise. */
class CompanyLedgerStub : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            val target = exchange.requestURI.toString()
            requests += target
            val name = Regex("/api/v1/ledger/periods/(YEAR|MONTH)/(\\d{4})-(\\d{2})-01/frozen-trial-balance")
                .matchEntire(target)?.destructured?.let { (type, year, month) ->
                    if (type == "YEAR") "YEAR-$year" else "MONTH-$year-$month"
                }
            val body = name?.let { CompanyLedgerStub::class.java.classLoader.getResource("company-ledger/$it.json") }
                ?.readText()
            val (status, payload) = if (body != null) {
                200 to body
            } else {
                409 to """{"error":"no frozen LINES_V1 evidence for the period"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return mapOf(
            "quarkus.rest-client.ledger-service.url" to "http://localhost:${server.address.port}",
            "openbank.statutory-returns.company-books.opened" to "2024-01-01",
        )
    }

    override fun stop() {
        if (this::server.isInitialized) server.stop(0)
    }

    companion object {
        val requests = CopyOnWriteArrayList<String>()
    }
}
