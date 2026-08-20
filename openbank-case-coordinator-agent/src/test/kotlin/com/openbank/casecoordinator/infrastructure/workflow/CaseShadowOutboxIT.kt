// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.workflow

import com.openbank.casecoordinator.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Real-DB proof that a shadow result cannot enter the dispatcher selection set. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseShadowOutboxIT {

    @Inject
    lateinit var activities: CaseActivitiesImpl

    @Inject
    lateinit var dataSource: DataSource

    @AfterEach
    fun cleanUp() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM case_outbox WHERE aggregate_id = ?").use {
                it.setObject(1, CASE_UUID)
                it.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM case_workflow WHERE id = ?").use {
                it.setObject(1, CASE_UUID)
                it.executeUpdate()
            }
        }
    }

    @Test
    fun `shadow proposal is recorded but is not processable by the outbox dispatcher`() {
        seedCase()

        activities.emitProposalWithDelivery(WORKFLOW_ID, "case-synthesis", "shadow only", false, shadow = true)

        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM case_outbox WHERE aggregate_id = ?").use { ps ->
                ps.setObject(1, CASE_UUID)
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("status")).isEqualTo("SHADOW")
                }
            }
            conn.prepareStatement(
                "SELECT count(*) FROM case_outbox WHERE aggregate_id = ? AND status IN ('PENDING', 'FAILED')",
            ).use { ps ->
                ps.setObject(1, CASE_UUID)
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getInt(1)).isZero()
                }
            }
        }
    }

    private fun seedCase() {
        val now = Timestamp.from(Instant.parse("2026-08-20T09:00:00Z"))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO case_workflow
                    (id, workflow_id, case_class, disposition_target, opened_at, deadline_at,
                     status, budget_tokens, budget_contributions, contested_rate)
                VALUES (?, ?, 'INCIDENT_RESPONSE', 'alert-7', ?, ?, 'OPEN', 200000, 40, 0.0)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, CASE_UUID)
                ps.setString(2, WORKFLOW_ID)
                ps.setTimestamp(3, now)
                ps.setTimestamp(4, now)
                ps.executeUpdate()
            }
        }
    }

    private companion object {
        const val WORKFLOW_ID = "case-incident-response-shadow-it"
        val CASE_UUID: UUID = UUID.nameUUIDFromBytes(WORKFLOW_ID.toByteArray(StandardCharsets.UTF_8))
    }
}
