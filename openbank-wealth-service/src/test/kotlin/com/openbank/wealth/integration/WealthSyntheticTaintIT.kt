// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.integration

import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.wealth.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * ADR-0252 phase 1 (#4348): a canary's holding is synthetic in wealth-service's own state and on
 * every event it emits, and nobody else's is.
 *
 * Driven over real HTTP because the taint is decided by `SyntheticTaintRequestFilter` from a header
 * plus a trusted principal, which only a real request carries; a direct use-case call would hand in
 * `synthetic = true` and prove nothing. Rows are read back over JDBC so a mapper that dropped the
 * flag cannot hide behind the API.
 *
 * The profile names [CANARY] as trusted. Without it every "synthetic" request below would be
 * recorded as real and the negative assertions would pass for the wrong reason, which is why the
 * positive case runs first in each test.
 */
@QuarkusTest
@QuarkusTestResource(WealthSyntheticTaintIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(WealthSyntheticTaintIT.TrustedCanaryProfile::class)
class WealthSyntheticTaintIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("wealth-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    class TrustedCanaryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.synthetic.trusted-principals" to CANARY,
        )
    }

    @Test
    @TestSecurity(user = CANARY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = CANARY),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `a trusted canary's holding is synthetic on the row and on every event, revalue included`() {
        val id = declare(given().header(SyntheticTaint.KAFKA_HEADER, "true"))

        assertThat(holdingSynthetic(id)).`as`("holding row").isTrue()
        assertThat(outboxSynthetic(id)).`as`("declared event").containsExactly(true)

        // No header on the revalue: the taint comes from the holding, not from this request.
        given().contentType("application/json")
            .body(
                """{"valuation":{"amount":900,"currency":"CZK","valuedAt":"2026-09-02","source":"CUSTOMER_DECLARED"}}""",
            )
            .`when`().put("/api/v1/holdings/$id/valuation")
            .then().statusCode(200)

        assertThat(outboxSynthetic(id)).`as`("declared + revalued events").containsExactly(true, true)
    }

    @Test
    @TestSecurity(user = CANARY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = CANARY),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `a trusted principal without the header declares a real holding`() {
        val id = declare(given())

        assertThat(holdingSynthetic(id)).isFalse()
        assertThat(outboxSynthetic(id)).containsExactly(false)
    }

    @Test
    @TestSecurity(user = "someone-else", roles = ["ROLE_API"])
    fun `an untrusted principal's header is refused and the holding is real`() {
        val id = declare(given().header(SyntheticTaint.KAFKA_HEADER, "true"))

        assertThat(holdingSynthetic(id)).isFalse()
        assertThat(outboxSynthetic(id)).containsExactly(false)
    }

    private fun declare(spec: RequestSpecification): UUID = UUID.fromString(
        spec.contentType("application/json")
            .header("X-Customer-Party-Id", UUID.randomUUID().toString())
            .body(
                """{"holdingType":"COLLECTIBLE","label":"Canary watch",
                   "valuation":{"amount":800,"currency":"CZK","valuedAt":"2026-09-01","source":"CUSTOMER_DECLARED"}}""",
            )
            .`when`().post("/api/v1/holdings")
            .then().statusCode(201)
            .extract().path<String>("holdingId"),
    )

    private fun holdingSynthetic(id: UUID): Boolean =
        query("select synthetic from declared_holdings where holding_id = ?", id).single()

    private fun outboxSynthetic(id: UUID): List<Boolean> =
        query("select synthetic from wealth_outbox where aggregate_id = ? order by id", id)

    private fun query(sql: String, id: UUID): List<Boolean> {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        return DriverManager.getConnection(url, "openbank", "openbank_secret").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getBoolean(1) else null }.toList()
                }
            }
        }
    }

    private companion object {
        /** A literal: a QuarkusTestProfile loads in another classloader, so a random id would split. */
        const val CANARY = "service-account-openbank-synthetic-canary"
    }
}
