// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.UUID

/** Upgrade real retained V6 human evidence without rewriting its actor or inventing proof. */
@Testcontainers
class AgentMergeProvenanceMigrationIT {
    @Test
    fun `V7 preserves preexisting human merge evidence and absent authorization`() {
        fun flyway(target: String) = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(target)
            .load()
        flyway("6").migrate()
        val id = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.prepareStatement(
                """
                INSERT INTO agent_merge_record
                  (id, agent_id, pr_number, pr_url, head_branch, merged_by, merged_at, title, recorded_at, source)
                VALUES (?, 'fixture-agent', 1, 'https://github.com/example/project/pull/1',
                        'agent/fixture', 'fixture-reviewer', '2026-10-01T12:00:00Z',
                        'original title', '2026-10-01T12:01:00Z', 'github-actions')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                assertThat(ps.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(flyway("7").migrate().migrationsExecuted).isEqualTo(1)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.prepareStatement("SELECT * FROM agent_merge_record WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("merged_by")).isEqualTo("fixture-reviewer")
                    assertThat(rs.getString("merged_by_type")).isEqualTo("HUMAN")
                    assertThat(rs.getString("title")).isEqualTo("original title")
                    assertThat(rs.getString("approved_by")).isNull()
                    assertThat(rs.getObject("merged_by_id")).isNull()
                    assertThat(rs.getString("head_sha")).isNull()
                    assertThat(rs.getString("merge_commit_sha")).isNull()
                    assertThat(rs.next()).isFalse()
                }
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18.6-alpine")
    }
}
