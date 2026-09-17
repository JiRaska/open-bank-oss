// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DelegationRecertificationMigrationIT {
    @TempDir
    lateinit var migrations: Path

    @Test
    fun `recertification upgrades portfolios without out of order migration and can be retried`() {
        val initialMigration = requireNotNull(javaClass.getResource("/db/migration/V1__init_delegation.sql"))
        val source = Path.of(initialMigration.toURI()).parent
        val scripts = Files.list(source).use { paths -> paths.filter { it.toString().endsWith(".sql") }.toList() }
        val recertification = scripts.filter { it.fileName.toString().contains("__delegation_recertification_") }
        val approvalGroups = scripts.filter { it.fileName.toString().contains("__approval_group") }
        assertThat(recertification).hasSize(2)
        assertThat(approvalGroups).hasSize(3)
        scripts.filterNot { it in recertification || it in approvalGroups }
            .forEach { Files.copy(it, migrations.resolve(it.fileName)) }

        PostgreSQLContainer("postgres:16-alpine").withUsername("openbank").use { postgres ->
            postgres.start()
            val flyway = Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("filesystem:$migrations")
                .outOfOrder(false)
                .load()
            flyway.migrate()
            assertThat(flyway.info().current().version.version).isEqualTo("19")
            verifyUpgrade(postgres, flyway, recertification, approvalGroups)
        }
    }

    private fun verifyUpgrade(
        postgres: PostgreSQLContainer<*>,
        flyway: Flyway,
        recertification: List<Path>,
        approvalGroups: List<Path>,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO delegation_portfolios (id, owner_party_id, name, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000002', 'Upgrade fixture', NOW(), NOW())
                    """.trimIndent(),
                )
            }
            recertification.forEach { Files.copy(it, migrations.resolve(it.fileName)) }
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2)
            assertThat(flyway.migrate().migrationsExecuted).isZero()
            flyway.validate()
            approvalGroups.forEach { Files.copy(it, migrations.resolve(it.fileName)) }
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3)
            assertThat(flyway.migrate().migrationsExecuted).isZero()
            flyway.validate()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM delegation_portfolios").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("Upgrade fixture")
                    assertThat(rows.next()).isFalse()
                }
                statement.executeQuery("SELECT COUNT(*) FROM delegation_recertification_cycles").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isZero()
                }
                val audienceQuery = "SELECT recertification_audience FROM delegation_grants LIMIT 0"
                statement.executeQuery(audienceQuery).use { rows ->
                    assertThat(rows.metaData.getColumnName(1)).isEqualTo("recertification_audience")
                }
            }
        }
    }
}
