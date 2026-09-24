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
import java.util.UUID

/**
 * The fixing consumer through the in-memory connector into a real Postgres: rows land once per
 * (source, fixingDate, currency), a redelivery adds nothing, and a malformed event is acked
 * without blocking the channel behind it.
 */
@QuarkusTest
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class FxFixingConsumerIT {

    @Inject
    @jakarta.enterprise.inject.Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var registry: MeterRegistry

    private fun outcome(name: String): Double =
        registry.find("openbank_risk_fx_fixing_events").tag("outcome", name).counter()?.count() ?: 0.0

    private fun event(date: String) = """
        {
          "source": "CNB",
          "fixingDate": "$date",
          "sequence": 187,
          "quoteCurrency": "CZK",
          "validFrom": "${date}T00:00:00Z",
          "validTo": "${date}T00:00:00Z",
          "rates": [
            {"rateId": "${UUID.randomUUID()}", "currency": "EUR", "ratePerUnit": 24.335},
            {"rateId": "${UUID.randomUUID()}", "currency": "USD", "ratePerUnit": 20.871}
          ],
          "occurredAt": "${date}T12:30:00Z"
        }
    """.trimIndent()

    @Test
    fun `a fixing event is stored once per currency and a redelivery adds nothing`() {
        val source = connector.source<String>("fx-fixing-in")

        source.send(event("2026-09-21"))
        awaitRows("2026-09-21", 2)
        source.send(event("2026-09-21"))
        // A malformed event in between must be acked and must not stop what follows.
        source.send("""{"source":"CNB"}""")
        source.send("not json")
        source.send(event("2026-09-22"))
        awaitRows("2026-09-22", 2)

        assertThat(rows("2026-09-21")).isEqualTo(2)
        // The redelivery must be recognised as a duplicate, not fail its write: with the in-memory
        // connector a failed write is only a nack, so the row count alone could not tell the two
        // apart — this counter can (falsified by dropping ON CONFLICT from the insert).
        assertThat(outcome("duplicate")).isEqualTo(1.0)
        assertThat(outcome("write_error")).isEqualTo(0.0)
        assertThat(outcome("malformed")).isEqualTo(2.0)
        assertThat(outcome("stored")).isEqualTo(2.0)
        assertThat(rate("2026-09-21", "EUR")).isEqualTo("24.335")
    }

    private fun awaitRows(date: String, expected: Int) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (rows(date) < expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertThat(rows(date)).isEqualTo(expected)
    }

    private fun rows(date: String): Int = query("SELECT count(*) FROM fx_fixing_rate WHERE fixing_date = DATE '$date'")

    private fun rate(date: String, currency: String): String = jdbc { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT rate_per_unit FROM fx_fixing_rate WHERE fixing_date = DATE '$date' AND currency = '$currency'",
            ).use { rs ->
                rs.next()
                rs.getBigDecimal(1).toPlainString()
            }
        }
    }

    private fun query(sql: String): Int = jdbc { conn ->
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
