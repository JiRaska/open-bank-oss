// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Real HTTP against a real Postgres, with only the ledger read replaced ([FakeLedgerPort]).
 * Each test uses its own as-of date, so the natural key never collides across tests.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskSnapshotApiIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory("fx-fixing-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var ledger: FakeLedgerPort

    private fun create(asOf: String) = given()
        .contentType("application/json")
        .body("""{"asOf":"$asOf"}""")
        .`when`().post("/api/v1/risk/snapshots")

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a tied-out run is persisted and its positions are served`() {
        ledger.inputs = Fixtures.tiedOut()

        val id = create("2026-01-31").then().statusCode(201)
            .body("status", equalTo("TIED_OUT"))
            .body("provenance", equalTo("synthetic"))
            .body("mismatchCount", equalTo(0))
            .extract().path<String>("id")

        given().`when`().get("/api/v1/risk/snapshots/$id/positions")
            .then().statusCode(200)
            .body("positions", hasSize<Any>(3))

        assertThat(count("SELECT count(*) FROM snapshot_position WHERE run_id = ?", id)).isEqualTo(3)
        assertThat(
            count("SELECT count(*) FROM snapshot_position WHERE run_id = ? AND valid_date = DATE '2026-01-31'", id),
        )
            .isEqualTo(3)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `an untied run is stored and flagged, and its positions answer 409 with the mismatches`() {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1000.00")))

        val id = create("2026-02-28").then().statusCode(201)
            .body("status", equalTo("UNTIED"))
            .body("mismatchCount", equalTo(1))
            .extract().path<String>("id")

        given().`when`().get("/api/v1/risk/snapshots/$id/positions")
            .then().statusCode(409)
            .body("error", equalTo("UNTIED"))
            .body("mismatches[0].glAccountCode", equalTo("2100"))

        given().`when`().get("/api/v1/risk/snapshots/$id")
            .then().statusCode(200)
            .body("mismatches[0].currency", equalTo("CZK"))
        assertThat(count("SELECT count(*) FROM snapshot_run WHERE id = ?::uuid AND status = 'UNTIED'", id)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a replay returns the same run and a changed ledger creates a new one`() {
        ledger.inputs = Fixtures.tiedOut()
        val first = create("2026-03-31").then().statusCode(201).extract().path<String>("id")
        val replay = create("2026-03-31").then().statusCode(200).extract().path<String>("id")
        assertThat(replay).isEqualTo(first)

        ledger.inputs = Fixtures.tiedOut().copy(subLedger = Fixtures.tiedOut().subLedger.reversed())
        val reordered = create("2026-03-31").then().statusCode(200).extract().path<String>("id")
        assertThat(reordered).isEqualTo(first)

        ledger.inputs = Fixtures.tiedOut().copy(subLedger = listOf(sl(Fixtures.ALICE, "CZK", "0", "1500.00")))
        val changed = create("2026-03-31").then().statusCode(201).extract().path<String>("id")
        assertThat(changed).isNotEqualTo(first)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `a missing or malformed asOf is a 400 and an unknown run a 404`() {
        given().contentType("application/json").body("{}").`when`().post("/api/v1/risk/snapshots")
            .then().statusCode(400)
        create("30/09/2026").then().statusCode(400)
        given().`when`().get("/api/v1/risk/snapshots/${UUID.randomUUID()}").then().statusCode(404)
    }

    // #10618 department roles. RBAC only (OPA is off in %test; the rego suite holds the policy half).
    @Test
    @TestSecurity(user = "risk-analyst", roles = ["ROLE_RISK"])
    fun `the risk department creates a snapshot and uploads a curve set`() {
        ledger.inputs = Fixtures.tiedOut()
        val id = create("2026-05-31").then().statusCode(201).extract().path<String>("id")
        given().`when`().get("/api/v1/risk/snapshots/$id").then().statusCode(200)
        given().contentType("application/json")
            .body("""{"asOf":"2026-05-31","provenance":"synthetic","source":"IT","curves":{"CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"3M","rate":0.036},{"tenor":"1Y","rate":0.038}]}}""")
            .`when`().post("/api/v1/risk/curve-sets").then().statusCode(201)
    }

    @Test
    @TestSecurity(user = "fin-reader", roles = ["ROLE_FINANCE"])
    fun `the finance department reads but cannot create a snapshot or upload a curve set`() {
        // 404, not 403: RBAC admitted the reader and the run simply does not exist.
        given().`when`().get("/api/v1/risk/snapshots/${UUID.randomUUID()}").then().statusCode(404)
        create("2026-07-31").then().statusCode(403)
        given().contentType("application/json")
            .body("""{"asOf":"2026-07-31","provenance":"synthetic","source":"IT","curves":{"CZEONIA":[{"tenor":"ON","rate":0.035},{"tenor":"3M","rate":0.036},{"tenor":"1Y","rate":0.038}]}}""")
            .`when`().post("/api/v1/risk/curve-sets").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "dealer", roles = ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"])
    fun `treasury roles are declared only and reach nothing here`() {
        given().`when`().get("/api/v1/risk/snapshots/${UUID.randomUUID()}").then().statusCode(403)
        create("2026-08-31").then().statusCode(403)
    }

    @Test
    fun `an unauthenticated caller is refused`() {
        create("2026-04-30").then().statusCode(401)
    }

    private fun count(sql: String, id: String): Int {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(sql.replace("run_id = ?", "run_id = ?::uuid")).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
