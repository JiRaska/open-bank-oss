// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.UUID

/** Exercises V8 against real pre-upgrade billing_outbox history, not an empty migrated database. */
@Testcontainers
class BillingAnnualFeeSummaryMigrationIT {
    @Test
    fun `V8 backfills retained annual summaries of every outbox status`() {
        val schema = schema("retained")
        val flyway = flyway(schema)
        flyway(schema, "7").migrate()
        val pending = insertHistoricalEvent(schema, "account-pending", 2024, "PENDING")
        val sent = insertHistoricalEvent(schema, "account-sent", 2025, "SENT")
        val dead = insertHistoricalEvent(schema, "account-dead", 2023, "DEAD")

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "SELECT account_id, calendar_year, source_event_id FROM $schema.billing_annual_fee_summary_issuance",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val actual = buildSet {
                        while (rows.next()) {
                            add(Triple(rows.getString(1), rows.getInt(2), rows.getObject(3, UUID::class.java)))
                        }
                    }
                    assertThat(actual).containsExactlyInAnyOrder(
                        Triple("account-pending", 2024, pending),
                        Triple("account-sent", 2025, sent),
                        Triple("account-dead", 2023, dead),
                    )
                }
            }
        }
    }

    @Test
    fun `V8 refuses malformed historical identity and leaves prior schema intact`() {
        val schema = schema("malformed")
        val flyway = flyway(schema)
        flyway(schema, "7").migrate()
        insertHistoricalEvent(schema, "account-good", 2025, "SENT")
        insertHistoricalEvent(
            schema,
            "account-bad",
            2025,
            "PENDING",
            """{"eventType":"AnnualFeeSummaryReady","accountId":42,"year":2025}""",
        )

        assertThatThrownBy { flyway.migrate() }
            .hasStackTraceContaining("malformed issuance identity")
        assertNoIssuanceTable(schema)
        assertThat(outboxCount(schema)).isEqualTo(2)
    }

    @Test
    fun `V8 refuses duplicate historical account year and leaves prior schema intact`() {
        val schema = schema("duplicate")
        val flyway = flyway(schema)
        flyway(schema, "7").migrate()
        insertHistoricalEvent(schema, "account-same", 2025, "SENT")
        insertHistoricalEvent(schema, "account-same", 2025, "PENDING")

        assertThatThrownBy { flyway.migrate() }
            .hasStackTraceContaining("duplicate account/year issuances")
        assertNoIssuanceTable(schema)
        assertThat(outboxCount(schema)).isEqualTo(2)
    }

    private fun flyway(schema: String, target: String = "8"): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .schemas(schema)
        .createSchemas(true)
        .locations("classpath:db/migration")
        .target(target)
        .load()

    private fun insertHistoricalEvent(
        schema: String,
        accountId: String,
        year: Int,
        status: String,
        payload: String = """{"eventType":"AnnualFeeSummaryReady","accountId":"$accountId","year":$year}""",
    ): UUID {
        val eventId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "INSERT INTO $schema.billing_outbox " +
                    "(event_id, aggregate_id, event_type, payload, status, created_at, updated_at) " +
                    "VALUES (?, ?, 'billing.annual-fee-summary.ready', ?, ?, NOW(), NOW())",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, payload)
                statement.setString(4, status)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        return eventId
    }

    private fun assertNoIssuanceTable(schema: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT to_regclass(?)").use { statement ->
                statement.setString(1, "$schema.billing_annual_fee_summary_issuance")
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isNull()
                }
            }
        }
    }

    private fun outboxCount(schema: String): Long =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $schema.billing_outbox").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    private fun schema(prefix: String): String = "billing_v8_${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18.6-alpine")
            .withUsername("openbank")
            .withPassword("openbank_secret")
    }
}
