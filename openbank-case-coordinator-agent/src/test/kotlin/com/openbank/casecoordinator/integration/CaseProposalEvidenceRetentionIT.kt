// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.integration

import com.openbank.casecoordinator.PostgresTestResource
import com.openbank.casecoordinator.infrastructure.persistence.CaseThreadReadRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Real PostgreSQL proof that proposal evidence survives deletion of a SENT delivery-buffer row. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseProposalEvidenceRetentionIT {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var readRepository: CaseThreadReadRepository

    private val eventId = UUID.randomUUID()
    private val caseId = UUID.nameUUIDFromBytes(WORKFLOW_ID.toByteArray(StandardCharsets.UTF_8))

    @AfterEach
    fun cleanUp() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM case_outbox WHERE event_id = ?").use { ps ->
                ps.setObject(1, eventId)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM case_proposal_evidence WHERE event_id = ?").use { ps ->
                ps.setObject(1, eventId)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM case_workflow WHERE id = ?").use { ps ->
                ps.setObject(1, caseId)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `proposal metadata survives SENT outbox purge`() {
        val createdAt = Timestamp.from(Instant.parse("2026-09-01T12:00:00Z"))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO case_workflow
                    (id, workflow_id, case_class, disposition_target, opened_at, deadline_at,
                     status, budget_tokens, budget_contributions, contested_rate)
                VALUES (?, ?, 'INCIDENT_RESPONSE', 'test', ?, ?, 'OPEN', 100, 5, 0.0)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, caseId)
                ps.setString(2, WORKFLOW_ID)
                ps.setTimestamp(3, createdAt)
                ps.setTimestamp(4, createdAt)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO case_outbox
                    (event_id, aggregate_id, event_type, payload, status, attempt_count, created_at, updated_at)
                VALUES (?, ?, 'case-synthesis', '{}', 'PENDING', 0, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, eventId)
                ps.setObject(2, caseId)
                ps.setTimestamp(3, createdAt)
                ps.setTimestamp(4, createdAt)
                ps.executeUpdate()
            }
            conn.prepareStatement("UPDATE case_outbox SET status = 'SENT', sent_at = ? WHERE event_id = ?").use { ps ->
                ps.setTimestamp(1, createdAt)
                ps.setObject(2, eventId)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM case_outbox WHERE event_id = ?").use { ps ->
                ps.setObject(1, eventId)
                ps.executeUpdate()
            }
        }

        val proposal = readRepository.listProposalEvents(WORKFLOW_ID).single()
        assertThat(proposal.proposalId).isEqualTo(eventId.toString())
        assertThat(proposal.proposalType).isEqualTo("case-synthesis")
        assertThat(proposal.status).isEqualTo("SENT")
        assertThat(proposal.emittedAtEpochMs).isEqualTo(createdAt.toInstant().toEpochMilli())
    }

    @Test
    fun `V11 backfills existing PENDING and SENT rows`() {
        val schema = "proposal_evidence_${UUID.randomUUID().toString().replace("-", "")}"
        val oldFlyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target("10")
            .load()
        try {
            oldFlyway.migrate()
            val pendingId = UUID.randomUUID()
            val sentId = UUID.randomUUID()
            seedHistoricalOutbox(schema, pendingId, sentId)
            val newFlyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target("11")
                .load()
            assertThat(newFlyway.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(readEvidenceStatuses(schema)).containsEntry(pendingId, "PENDING").containsEntry(sentId, "SENT")
        } finally {
            dropSchema(schema)
        }
    }

    private fun seedHistoricalOutbox(schema: String, pendingId: UUID, sentId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO $schema.case_outbox
                    (event_id, aggregate_id, event_type, payload, status, created_at, updated_at)
                VALUES (?, ?, 'case-synthesis', '{}', ?, NOW(), NOW())
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(2, UUID.randomUUID())
                ps.setObject(1, pendingId)
                ps.setString(3, "PENDING")
                ps.executeUpdate()
                ps.setObject(1, sentId)
                ps.setString(3, "SENT")
                ps.executeUpdate()
            }
        }
    }

    private fun readEvidenceStatuses(schema: String): Map<UUID, String> = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT event_id, delivery_status FROM $schema.case_proposal_evidence").use { ps ->
            ps.executeQuery().use(::collectStatuses)
        }
    }

    private fun collectStatuses(rs: ResultSet): Map<UUID, String> {
        val rows = mutableMapOf<UUID, String>()
        while (rs.next()) rows[rs.getObject(1, UUID::class.java)] = rs.getString(2)
        return rows
    }

    private fun dropSchema(schema: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private companion object {
        const val WORKFLOW_ID = "case-proposal-evidence-retention-it"
    }
}
