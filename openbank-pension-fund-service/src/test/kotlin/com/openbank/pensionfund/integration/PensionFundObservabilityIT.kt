// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.infrastructure.observability.PgPensionFundStateSource
import com.openbank.pensionfund.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The observability wiring against the real CDI graph and a real Postgres (#12424): the counters
 * move through the real REST routes — which a hand-built service cannot show, since only CDI hands
 * the Micrometer adapter to the use cases — and the state snapshot's SQL runs against the migrated
 * schema and agrees with a direct count.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PensionFundObservabilityIT {

    @Inject
    lateinit var source: PgPensionFundStateSource

    @Inject
    lateinit var registry: MeterRegistry

    companion object {
        // Random, so this class shares a database with PensionFundApiIT without an ISIN collision.
        val isin = "CZ" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "7"
        val contract: UUID = UUID.randomUUID()
        val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
        lateinit var fund: String
        lateinit var nav: String
    }

    private fun counter(name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counters().sumOf { it.count() }

    private fun count(sql: String): Long {
        val cfg = ConfigProvider.getConfig()
        return java.sql.DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    private fun post(path: String, body: String) =
        given().contentType("application/json").body(body).`when`().post(path)

    @Test
    @Order(1)
    @TestSecurity(user = "obs-maker", roles = ["ROLE_OPERATOR"])
    fun `placing an order and calculating a NAV move the counters once each`() {
        fund = post(
            "/api/v1/funds",
            """{"name":"Obs","isin":"$isin","lei":"315700ABCDEF12345678","depositaryReference":"DEP-1",
               "custodyAccountReference":"CUST-OBS","currency":"CZK","riskClass":3,"mandatoryConservative":false,
               "managementFeeRate":0,"launchNavPerUnit":1}""",
        ).then().statusCode(201).extract().path("id")

        given().contentType("application/json").header("Idempotency-Key", "obs-1")
            .body("""{"fundId":"$fund","type":"SUBSCRIBE","amount":250}""")
            .`when`().post("/api/v1/contracts/$contract/orders").then().statusCode(202)
        nav = post("/api/v1/funds/$fund/navs", """{"valuationDate":"$today","cash":0}""")
            .then().statusCode(201).extract().path("id")

        assertThat(counter("openbank.pension_fund.orders", "fund", isin, "type", "SUBSCRIBE", "status", "PENDING"))
            .isEqualTo(1.0)
        assertThat(counter("openbank.pension_fund.nav.events", "fund", isin, "event", "calculated")).isEqualTo(1.0)

        // The snapshot sees the order pending and the NAV waiting for its checker.
        val snapshot = runBlocking { source.snapshot() }
        val state = snapshot.funds.single { it.isin == isin }
        assertThat(state.lastPublishedAgeSeconds).isNull()
        assertThat(state.netAssets).isNull()
        assertThat(state.pendingOrdersOldestAgeSeconds).isGreaterThanOrEqualTo(0.0)
        assertThat(snapshot.pendingOrders.single { it.labels["fund"] == isin }.count).isEqualTo(1)
        assertThat(snapshot.queueSize["navs_awaiting_approval"])
            .isEqualTo(count("SELECT count(*) FROM fund_navs WHERE status = 'CALCULATED'"))
            .isGreaterThanOrEqualTo(1)
        assertThat(snapshot.queueSize.keys).containsExactlyInAnyOrder(
            "orders_pending",
            "navs_awaiting_approval",
            "strategy_changes_awaiting_approval",
        )
    }

    @Test
    @Order(2)
    @TestSecurity(user = "obs-checker", roles = ["ROLE_OPERATOR"])
    fun `publishing settles the order and the snapshot reports AUM, units and NAV age`() {
        given().`when`().post("/api/v1/navs/$nav/approve").then().statusCode(200)

        assertThat(counter("openbank.pension_fund.nav.events", "fund", isin, "event", "published")).isEqualTo(1.0)
        assertThat(counter("openbank.pension_fund.orders", "fund", isin, "type", "SUBSCRIBE", "status", "SETTLED"))
            .isEqualTo(1.0)
        assertThat(counter("openbank.pension_fund.settled.amount", "fund", isin, "currency", "CZK")).isEqualTo(250.0)
        assertThat(registry.find("openbank.pension_fund.nav.publication.lag").tag("fund", isin).timer()!!.count())
            .isEqualTo(1)

        val snapshot = runBlocking { source.snapshot() }
        val state = snapshot.funds.single { it.isin == isin }
        assertThat(state.unitsOutstanding).isEqualTo(250.0)
        // The NAV was calculated on zero units, so it priced at launch (1) and net assets of 0.
        assertThat(state.navPerUnit).isEqualTo(1.0)
        assertThat(state.netAssets).isEqualTo(0.0)
        // Forward pricing: an order settles at a NAV valuing its own day or later, so the NAV is today's,
        // whose day has not ended yet -- an age of 0, never a negative one.
        assertThat(state.lastPublishedAgeSeconds).isEqualTo(0.0)
        assertThat(state.pendingOrdersOldestAgeSeconds).isEqualTo(0.0)
        assertThat(snapshot.funds.size.toLong()).isEqualTo(count("SELECT count(*) FROM funds WHERE status = 'ACTIVE'"))
        val navRows = snapshot.aggregates.filter { it.labels["aggregate"] == "nav" }.sumOf { it.count }
        assertThat(navRows).isEqualTo(count("SELECT count(*) FROM fund_navs"))
    }
}
