// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import javax.sql.DataSource

/** A database already at V27 must still apply V26 when that migration arrives later (#13026). */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class FlywayV26LateArrivalIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `late V26 is repaired without losing V27 or provisioning summary state`() {
        val configuredOutOfOrder = ConfigProvider.getConfig()
            .getValue("quarkus.flyway.out-of-order", Boolean::class.java)
        assertThat(configuredOutOfOrder)
            .`as`("application.yaml must retain the temporary V26 recovery setting")
            .isTrue()

        val fullMigrations = Path.of("src/main/resources/db/migration").toAbsolutePath()
        val withoutV26 = Files.createTempDirectory("lending-migrations-without-v26-")
        val freshSchema = schemaName("v26_fresh")
        val lateSchema = schemaName("v26_late")
        try {
            Files.list(fullMigrations).use { migrations ->
                migrations.filter { it.fileName.toString().matches(Regex("V\\d+__.*\\.sql")) }
                    .forEach { migration ->
                        if (migration.fileName.toString() != V26) {
                            Files.copy(migration, withoutV26.resolve(migration.fileName))
                        }
                    }
            }

            flyway(fullMigrations, freshSchema, outOfOrder = false, validate = false).migrate()
            assertThat(applied(freshSchema, 26)).isTrue()
            assertThat(applied(freshSchema, 27)).isTrue()
            flyway(fullMigrations, freshSchema, outOfOrder = false, validate = true).migrate()

            // Recreate the legacy ordering: all versions except V26, including V27.
            flyway(withoutV26, lateSchema, outOfOrder = false, validate = false).migrate()
            assertThat(applied(lateSchema, 27)).isTrue()
            assertThat(applied(lateSchema, 26)).isFalse()
            seedLegacyRows(lateSchema)

            // Production validation is disabled, so strict ordering returns without V26's schema.
            flyway(fullMigrations, lateSchema, outOfOrder = false, validate = false).migrate()
            assertThat(applied(lateSchema, 26)).isFalse()
            assertThat(tableExists(lateSchema, "provisioning_cycle_attempt")).isFalse()
            assertThat(columnExists(lateSchema, "provisioning_cycle_run", "current_attempt_id")).isFalse()
            assertLegacyState(lateSchema)

            // Drive recovery from the application setting, not a hard-coded true.
            flyway(fullMigrations, lateSchema, outOfOrder = configuredOutOfOrder, validate = false).migrate()
            assertThat(applied(lateSchema, 26)).isTrue()
            assertThat(installedRank(lateSchema, 26)).isGreaterThan(installedRank(lateSchema, 27))
            assertLegacyState(lateSchema)
            assertThat(attemptEvents(lateSchema)).containsExactlyInAnyOrder("STARTED", "COMPLETE")

            val attemptCount = countAttempts(lateSchema)
            flyway(fullMigrations, lateSchema, outOfOrder = configuredOutOfOrder, validate = false).migrate()
            assertThat(countAttempts(lateSchema)).isEqualTo(attemptCount)
            assertLegacyState(lateSchema)

            // Once V26 is recorded, strict ordering and validation are safe again.
            flyway(fullMigrations, lateSchema, outOfOrder = false, validate = true).migrate()
        } finally {
            try {
                dropSchema(freshSchema)
            } finally {
                try {
                    dropSchema(lateSchema)
                } finally {
                    deleteTree(withoutV26)
                }
            }
        }
    }

    private fun flyway(location: Path, schema: String, outOfOrder: Boolean, validate: Boolean): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:$location")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .outOfOrder(outOfOrder)
            .validateOnMigrate(validate)
            .cleanDisabled(true)
            .load()

    private fun seedLegacyRows(schema: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO $schema.loan_application
                    (id, party_id, requested_amount, currency, nominal_annual_rate, term_periods, first_due_date,
                     proposed_by, signature_artefact_hash)
                    VALUES (?, ?, 100.00, 'USD', 0.050000, 12, DATE '2026-01-01', 'v26-late-arrival', ?)""",
            ).use { statement ->
                statement.setObject(1, LEGACY_LOAN_ID)
                statement.setObject(2, UUID.fromString("00000000-0000-0000-0000-000000000222"))
                statement.setString(3, LEGACY_HASH)
                statement.executeUpdate()
            }
            connection.createStatement().use {
                it.execute(
                    """INSERT INTO $schema.provisioning_cycle_run(period, status, started_at, checked_at, missing_loans)
                        VALUES (DATE '2026-01-02', 'COMPLETE', TIMESTAMPTZ '2026-01-02 01:00:00Z',
                                TIMESTAMPTZ '2026-01-02 01:05:00Z', 0)""",
                )
            }
        }
    }

    private fun assertLegacyState(schema: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT signature_artefact_hash FROM $schema.loan_application WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, LEGACY_LOAN_ID)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo(LEGACY_HASH)
                }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT status, missing_loans FROM $schema.provisioning_cycle_run " +
                        "WHERE period = DATE '2026-01-02'",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("COMPLETE")
                    assertThat(rows.getLong(2)).isZero()
                }
            }
        }
    }

    private fun attemptEvents(schema: String): List<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT event_type FROM $schema.provisioning_cycle_attempt " +
                    "WHERE period = DATE '2026-01-02' ORDER BY id",
            ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun countAttempts(schema: String): Long =
        scalarLong("SELECT count(*) FROM $schema.provisioning_cycle_attempt")

    private fun applied(schema: String, version: Int): Boolean = scalarBoolean(
        "SELECT EXISTS (SELECT 1 FROM $schema.flyway_schema_history WHERE version = '$version' AND success)",
    )

    private fun installedRank(schema: String, version: Int): Int = scalarLong(
        "SELECT installed_rank FROM $schema.flyway_schema_history WHERE version = '$version' AND success",
    ).toInt()

    private fun tableExists(schema: String, table: String): Boolean = scalarBoolean(
        "SELECT EXISTS (SELECT 1 FROM information_schema.tables " +
            "WHERE table_schema = '$schema' AND table_name = '$table')",
    )

    private fun columnExists(schema: String, table: String, column: String): Boolean = scalarBoolean(
        "SELECT EXISTS (SELECT 1 FROM information_schema.columns " +
            "WHERE table_schema = '$schema' AND table_name = '$table' AND column_name = '$column')",
    )

    private fun scalarBoolean(sql: String): Boolean = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
    }

    private fun scalarLong(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun dropSchema(schema: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun schemaName(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private companion object {
        const val V26 = "V26__provisioning_cycle_attempts.sql"
        const val LEGACY_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val LEGACY_LOAN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000111")
    }
}
