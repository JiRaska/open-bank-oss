// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.infrastructure.observability.PgPensionStateSource
import com.openbank.pension.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * The observability wiring against the real CDI graph and a real Postgres (#12424): the state
 * snapshot's SQL runs against the migrated schema and agrees with a direct count, and the
 * contribution counters move through the real REST route — a unit test with a hand-built service
 * cannot show that the producers hand the Micrometer adapter, not the no-op default, to the service.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionObservabilityIT {

    @Inject
    lateinit var source: PgPensionStateSource

    @Inject
    lateinit var registry: MeterRegistry

    private val ops = "/api/v1/pension/funding/operations"

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private fun count(sql: String): Long = jdbc { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next().let { rs.getLong(1) } } }
    }

    private fun unmatched(outcome: String): Double = registry.find("openbank.pension.contributions.received")
        .tags("source", "PARTICIPANT", "outcome", outcome).counters().sumOf { it.count() }

    @Test
    @TestSecurity(user = "alice", roles = ["ROLE_OPERATOR"])
    fun `a stray payment moves the counter once and appears in the unmatched queue snapshot`() {
        val before = unmatched("unmatched")
        val beforeDuplicate = unmatched("duplicate")
        val body = """{"paymentId":"obs-${UUID.randomUUID()}","amount":300,"currency":"CZK",""" +
            """"valueDate":"2026-01-20","reference":"0000"}"""
        repeat(2) {
            given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .body(body).`when`().post("$ops/payments").then().statusCode(200)
                .body("outcome", equalTo("UNMATCHED"))
        }

        assertThat(unmatched("unmatched") - before).isEqualTo(1.0)
        assertThat(unmatched("duplicate") - beforeDuplicate).isEqualTo(1.0)

        val snapshot = runBlocking { source.snapshot() }
        assertThat(snapshot.queueSize["unmatched_payments"])
            .isEqualTo(count("SELECT count(*) FROM pension_unmatched_payments WHERE status = 'OPEN'"))
            .isGreaterThanOrEqualTo(1)
        assertThat(snapshot.queueOldestAgeSeconds["unmatched_payments"]).isNotNull().isGreaterThanOrEqualTo(0.0)
        assertThat(snapshot.queueSize.keys).containsExactlyInAnyOrder(
            "unmatched_payments",
            "payment_instructions_pending",
            "incentive_claims_pending",
            "state_contribution_returns_due",
        )
        val unmatchedOpen = snapshot.aggregates.single {
            it.labels == mapOf("aggregate" to "unmatched_payment", "status" to "OPEN")
        }
        assertThat(unmatchedOpen.count).isEqualTo(snapshot.queueSize["unmatched_payments"])
        // Every contract row is accounted for by the (status, product_line, jurisdiction) split.
        assertThat(snapshot.contracts.sumOf { it.count }).isEqualTo(count("SELECT count(*) FROM pension_contracts"))
    }
}
