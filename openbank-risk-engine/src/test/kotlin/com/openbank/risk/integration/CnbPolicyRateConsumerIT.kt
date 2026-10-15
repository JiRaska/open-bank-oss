// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.it.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * The policy-rate consumer through the in-memory connector into a real Postgres: a fact lands once
 * per (instrument, effectiveFrom), a redelivery is a counted duplicate, a changed rate is a counted
 * revision, and malformed events (including a rate outside [0, 1]) are acked without blocking what
 * follows.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class CnbPolicyRateConsumerIT {

    @Inject
    @jakarta.enterprise.inject.Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var registry: MeterRegistry

    private fun outcome(name: String): Double =
        registry.find("openbank_risk_cnb_policy_rate_events").tag("outcome", name).counter()?.count() ?: 0.0

    private fun event(instrument: String, effectiveFrom: String, rate: String, revised: Boolean = false) = """
        {
          "instrument": "$instrument",
          "effectiveFrom": "$effectiveFrom",
          "rate": $rate,
          "sourceUrl": "https://www.cnb.cz/cs/casto-kladene-dotazy/.galleries/vyvoj_lombard_historie.txt",
          "fetchedAt": "2026-10-04T12:45:00Z",
          "contentSha256": "${"c".repeat(64)}",
          "note": null,
          "revised": $revised,
          "previousRate": null,
          "occurredAt": "2026-10-04T12:45:01Z",
          "sourceService": "fx-service"
        }
    """.trimIndent()

    @Test
    fun `facts are stored once per key, a redelivery is a duplicate and a changed rate is a revision`() {
        val source = connector.source<String>("cnb-policy-rate-in")

        source.send(event("LOMBARD", "2031-06-19", "0.0475"))
        awaitRate("LOMBARD", "2031-06-19", "0.04750000")
        source.send(event("LOMBARD", "2031-06-19", "0.0475"))
        source.send("""{"instrument":"LOMBARD"}""")
        source.send("not json")
        source.send(event("LOMBARD", "2031-07-01", "1.5"))
        source.send(event("LOMBARD", "2031-06-19", "0.05", revised = true))
        awaitRate("LOMBARD", "2031-06-19", "0.05000000")

        assertThat(count("SELECT count(*) FROM cnb_policy_rate_fact WHERE instrument = 'LOMBARD'")).isEqualTo(1)
        assertThat(
            count("SELECT count(*) FROM cnb_policy_rate_fact WHERE instrument = 'LOMBARD' AND revised"),
        ).isEqualTo(1)
        // Counters, not only rows: with the in-memory connector a failed write is a silent nack, so
        // only the outcome tells "duplicate" from "the write failed".
        assertThat(outcome("stored")).isEqualTo(1.0)
        assertThat(outcome("duplicate")).isEqualTo(1.0)
        assertThat(outcome("revised")).isEqualTo(1.0)
        assertThat(outcome("malformed")).isEqualTo(3.0)
        assertThat(outcome("write_error")).isEqualTo(0.0)
    }

    private fun awaitRate(instrument: String, effectiveFrom: String, expected: String) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (rate(instrument, effectiveFrom) != expected && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertThat(rate(instrument, effectiveFrom)).isEqualTo(expected)
    }

    private fun rate(instrument: String, effectiveFrom: String): String? = jdbc { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT rate FROM cnb_policy_rate_fact WHERE instrument = '$instrument' " +
                    "AND effective_from = DATE '$effectiveFrom'",
            ).use { rs -> if (rs.next()) rs.getBigDecimal(1).toPlainString() else null }
        }
    }

    private fun count(sql: String): Int = jdbc { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private companion object {
        const val TIMEOUT_NANOS = 10_000_000_000L
        const val POLL_MS = 100L
    }
}
