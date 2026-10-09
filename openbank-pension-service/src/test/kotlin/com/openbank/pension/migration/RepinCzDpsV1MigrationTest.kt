// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.migration

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import com.openbank.pension.it.PostgresTestResource
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * V12 re-pins every CZ/DPS v1 contract to v2: v1's claim format has no filing channel, so a v1 pin
 * would leave the participant's state contribution PENDING forever. Runs the real migrations on a
 * real Postgres up to V11, seeds pins, then applies V12.
 */
class RepinCzDpsV1MigrationTest {

    @Test
    fun `V12 moves CZ DPS v1 pins to v2 and touches nothing else`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker not available")
        PostgreSQLContainer(DockerImageName.parse(PostgresTestResource.POSTGRES_IMAGE))
            // The migrations GRANT to the application role, as in every deployed database.
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .use { pg ->
                pg.start()
                fun flyway(target: String) = Flyway.configure()
                    .dataSource(pg.jdbcUrl, pg.username, pg.password)
                    .locations("classpath:db/migration")
                    .target(target)
                    .load()
                flyway("11").migrate()
                val dpsV1 = UUID.randomUUID()
                val dipV1 = UUID.randomUUID()
                DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                    c.insert(dpsV1, "DPS", 1)
                    c.insert(dipV1, "DIP", 1)
                }
                flyway("12").migrate()
                DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                    assertThat(c.versionOf(dpsV1)).isEqualTo(2)
                    assertThat(c.versionOf(dipV1)).isEqualTo(1)
                }
            }
    }

    @Test
    fun `the re-pin target files claims through a channel the v1 format never had`() {
        val registry = JurisdictionPackLoader.loadRegistry()
        fun format(v: Int) = registry.pinned("CZ", ProductLine.DPS, v).incentives
            .first { it.id == "state-contribution" }.claimFormat
        assertThat(format(1)).isEqualTo("agency-monthly-batch-v0")
        assertThat(format(2)).isEqualTo(
            com.openbank.pension.infrastructure.statecontribution.CzMfStateContributionFormat.FORMAT,
        )
    }

    private fun Connection.insert(id: UUID, line: String, version: Int) {
        prepareStatement(
            """insert into pension_contracts (contract_id, participant_party_id, product_line, jurisdiction,
               pack_version, provider_entity_id, provider_type, participant_birth_date, status,
               contribution_amount, contribution_currency, contribution_frequency, created_at, updated_at)
               values (?, ?, ?, 'CZ', ?, ?, 'PENSION_COMPANY', date '1980-01-01', 'DRAFT', 1000, 'CZK',
               'MONTHLY', now(), now())""",
        ).use { st ->
            st.setObject(1, id)
            st.setObject(2, UUID.randomUUID())
            st.setString(3, line)
            st.setInt(4, version)
            st.setObject(5, UUID.randomUUID())
            st.executeUpdate()
        }
    }

    private fun Connection.versionOf(id: UUID): Int =
        prepareStatement("select pack_version from pension_contracts where contract_id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
}
