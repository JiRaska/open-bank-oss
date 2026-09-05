// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.persistence

import com.openbank.casecoordinator.PostgresTestResource
import com.openbank.casecoordinator.domain.model.CaseSignalEvidence
import com.openbank.casecoordinator.domain.model.CaseSignalEvidenceStage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseSignalEvidenceQuotaIT {
    @Inject
    lateinit var repository: CaseSignalEvidenceRepository

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun seedCase() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO case_workflow
                    (id, workflow_id, case_class, disposition_target, opened_at, deadline_at,
                     status, budget_tokens, budget_contributions, contested_rate)
                VALUES (?, ?, 'INCIDENT_RESPONSE', 'quota-it', ?, ?, 'OPEN', 200000, 40, 0.0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, CASE_UUID)
                statement.setString(2, CASE_ID)
                statement.setTimestamp(3, NOW)
                statement.setTimestamp(4, NOW)
                statement.executeUpdate()
            }
        }
    }

    @AfterEach
    fun cleanCase() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM case_workflow WHERE id = ?").use { statement ->
                statement.setObject(1, CASE_UUID)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `concurrent authorizations cannot exceed the policy quota`() {
        val executor = Executors.newFixedThreadPool(ATTEMPT_COUNT)
        val attempts = (1..ATTEMPT_COUNT).map { attempt ->
            Callable {
                repository.tryRecordAuthorized(
                    authorizationEvidence(attempt),
                    MAX_SIGNALS,
                )
            }
        }

        val accepted = try {
            executor.invokeAll(attempts).count { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertThat(accepted).isEqualTo(MAX_SIGNALS)
        assertThat(storedAuthorizationCount()).isEqualTo(MAX_SIGNALS)
    }

    private fun authorizationEvidence(attempt: Int) = CaseSignalEvidence(
        signalId = UUID.nameUUIDFromBytes("signal-$attempt".toByteArray(StandardCharsets.UTF_8)).toString(),
        caseId = CASE_ID,
        agentId = "rca-investigator",
        capability = "case.contribute",
        stage = CaseSignalEvidenceStage.AUTHORIZED,
        observedAtEpochMs = NOW.time,
        rolloutId = "shadow-v1",
        policyDecisionId = "decision-$attempt",
        policyReason = "shadow grant",
    )

    private fun storedAuthorizationCount(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM case_signal_evidence WHERE case_id = ? AND stage = 'AUTHORIZED'",
        ).use { statement ->
            statement.setObject(1, CASE_UUID)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private companion object {
        const val CASE_ID = "case-signal-quota-it"
        const val MAX_SIGNALS = 8
        const val ATTEMPT_COUNT = 16
        val CASE_UUID: UUID = UUID.nameUUIDFromBytes(CASE_ID.toByteArray(StandardCharsets.UTF_8))
        val NOW: Timestamp = Timestamp.from(Instant.parse("2026-08-22T12:00:00Z"))
    }
}
