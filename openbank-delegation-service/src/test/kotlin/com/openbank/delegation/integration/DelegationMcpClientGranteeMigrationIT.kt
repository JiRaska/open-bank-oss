// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * ADR-0383 D3 — V34 against real Postgres: rows that existed before it read back as PARTY, and the
 * CHECK constraints refuse a half-bound MCP_CLIENT row and a PARTY row carrying client bindings.
 */
class DelegationMcpClientGranteeMigrationIT {
    @TempDir
    lateinit var migrations: Path

    private val jkt = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"

    @Test
    fun `V34 defaults existing grants to PARTY and pins the binding shape`() {
        val v1 = requireNotNull(javaClass.getResource("/db/migration/V1__init_delegation.sql"))
        val scripts = Files.list(Path.of(v1.toURI()).parent).use { p ->
            p.filter { it.toString().endsWith(".sql") }.toList()
        }
        val v34 = scripts.single { version(it) == V34 }
        scripts.filter { version(it) < V34 }.forEach { Files.copy(it, migrations.resolve(it.fileName)) }

        PostgreSQLContainer("postgres:18.6-alpine").withUsername("openbank").use { postgres ->
            postgres.start()
            val flyway = Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("filesystem:$migrations")
                .load()
            flyway.migrate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
                verifyV34(c, flyway, v34)
            }
        }
    }

    private fun verifyV34(c: Connection, flyway: Flyway, v34: Path) {
        insert(c, "00000000-0000-0000-0000-0000000000a1", extraCols = "", extraVals = "")

        Files.copy(v34, migrations.resolve(v34.fileName))
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1)

        c.createStatement().use { s ->
            s.executeQuery(
                "SELECT grantee_type, grantee_client_id, grantee_key_thumbprint FROM delegation_grants",
            ).use { r ->
                assertThat(r.next()).isTrue()
                assertThat(r.getString(1)).isEqualTo("PARTY")
                assertThat(r.getString(2)).isNull()
                assertThat(r.getString(3)).isNull()
            }
        }

        assertThatThrownBy {
            insert(
                c,
                "00000000-0000-0000-0000-0000000000a2",
                ", grantee_type, grantee_client_id",
                ", 'MCP_CLIENT', 'client'",
            )
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("chk_delegation_grantee_binding")

        assertThatThrownBy {
            insert(
                c,
                "00000000-0000-0000-0000-0000000000a3",
                ", grantee_type, grantee_client_id, grantee_key_thumbprint",
                ", 'PARTY', 'client', '$jkt'",
            )
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("chk_delegation_grantee_binding")

        assertThatThrownBy {
            insert(c, "00000000-0000-0000-0000-0000000000a4", ", grantee_type", ", 'AGENT'")
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("chk_delegation_grantee_")

        insert(
            c,
            "00000000-0000-0000-0000-0000000000a5",
            ", grantee_type, grantee_client_id, grantee_key_thumbprint",
            ", 'MCP_CLIENT', 'client', '$jkt'",
        )
    }

    private fun insert(c: Connection, id: String, extraCols: String, extraVals: String) {
        c.createStatement().use { s ->
            s.executeUpdate(
                """
                INSERT INTO delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, valid_from$extraCols)
                VALUES ('$id', gen_random_uuid(), gen_random_uuid(), 'ACCOUNT', gen_random_uuid(), NOW()$extraVals)
                """.trimIndent(),
            )
        }
    }

    private fun version(script: Path): Int =
        script.fileName.toString().substringAfter("V").substringBefore("__").toInt()

    private companion object {
        const val V34 = 34
    }
}
