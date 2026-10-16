// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.application.port.`in`.MergeRecordCommand
import com.openbank.agent.application.port.`in`.RecordMergeUseCase
import com.openbank.agent.infrastructure.audit.AgentAuditOutbox
import com.openbank.agent.it.PostgresTestResource
import io.mockk.every
import io.mockk.spyk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Recorded-human-merge surface (ADR-0031 phase 3) against the real V6 schema: boots the full app
 * on an isolated PostgreSQL (Flyway migrates) and drives POST/GET /api/v1/agent-merge-records over
 * HTTP, asserting rows via plain JDBC reads — a direct resource call could not see routing or RBAC.
 * The audit outbox is asserted row-level too: exactly one `agent.pr.merged` event per PR. Injected
 * runtime and checked failures prove both writes roll back and redelivery retries the operation.
 */
@QuarkusTest
// restrictToAnnotatedClass=true — same guard as ProposalApiIT: without it,
// PostgresTestResource's injected `agent.model.openai.api-key=test-not-used` placeholder can leak
// into other @QuarkusTest classes sharing this test JVM whose own @TestProfile expects a
// different value.
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class AgentMergeRecordApiIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var recordMerge: RecordMergeUseCase

    private fun mergeCommand(agentId: String = "fixture-agent", prNumber: Long) = MergeRecordCommand(
        agentId = agentId,
        prNumber = prNumber,
        prUrl = "https://github.com/openbank/open-bank-oss/pull/$prNumber",
        headBranch = "agent/$agentId-fixture",
        mergedBy = "fixture-reviewer",
        mergedAt = Instant.parse("2026-10-01T12:00:00Z"),
        title = "fixture merge $prNumber",
        proposalId = null,
        source = "github-actions",
    )

    private fun ingestBody(
        agentId: String = "flaky-test-hunter",
        prNumber: Long = 9001,
        mergedBy: String = "octo-reviewer",
        title: String = "fix flaky ledger sweep",
    ) = mapOf(
        "agentId" to agentId,
        "prNumber" to prNumber,
        "prUrl" to "https://github.com/openbank/open-bank-oss/pull/$prNumber",
        "headBranch" to "agent/$agentId-flaky-sweep",
        "mergedBy" to mergedBy,
        "mergedAt" to "2026-10-01T12:00:00Z",
        "title" to title,
        "source" to "github-actions",
    )

    private fun auditCountFor(prNumber: Long): Long = dataSource.connection.use { c ->
        c.prepareStatement(
            """
            SELECT count(*) FROM agent_audit_outbox
             WHERE payload::jsonb ->> 'pr_number' = ?
               AND payload::jsonb ->> 'eventType' = 'agent.pr.merged'
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, prNumber.toString())
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun installAuditFailureSpy(): MutableMap<Long, Int> {
        val attempts = mutableMapOf<Long, Int>()
        val outbox = spyk(AgentAuditOutbox(dataSource))
        every { outbox.enqueue(any(), any()) } answers {
            val payload = secondArg<String>()
            val prNumber = when {
                payload.contains("\"pr_number\":9005") -> 9005L
                payload.contains("\"pr_number\":9006") -> 9006L
                else -> null
            }
            if (prNumber == null) {
                callOriginal()
            } else {
                val attempt = (attempts[prNumber] ?: 0) + 1
                attempts[prNumber] = attempt
                when {
                    prNumber == 9005L && attempt == 1 -> {
                        callOriginal()
                        error("injected post-enqueue failure")
                    }
                    prNumber == 9006L && attempt == 1 -> throw SQLException("injected checked enqueue failure")
                    else -> callOriginal()
                }
            }
        }
        QuarkusMock.installMockForType(outbox, AgentAuditOutbox::class.java)
        return attempts
    }

    private fun assertMergeCount(prNumber: Long, expected: Long) {
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM agent_merge_record WHERE pr_number = ?").use { ps ->
                ps.setLong(1, prNumber)
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isEqualTo(expected)
                }
            }
        }
    }

    private fun assertFailureRollsBackThenRetry(
        command: MergeRecordCommand,
        exception: Class<out Throwable>,
        message: String,
    ) {
        assertThatThrownBy { recordMerge.record(command) }
            .isInstanceOf(exception)
            .hasMessage(message)
        assertMergeCount(command.prNumber, 0)
        assertThat(auditCountFor(command.prNumber)).isZero()

        val retry = recordMerge.record(command)
        assertThat(retry.inserted).isTrue()
        assertThat(retry.record.agentId).isEqualTo(command.agentId)
        assertThat(retry.record.mergedBy).isEqualTo(command.mergedBy)
        assertThat(retry.record.title).isEqualTo(command.title)
        assertMergeCount(command.prNumber, 1)
        assertThat(auditCountFor(command.prNumber)).isEqualTo(1)
    }

    private fun deleteMergeFixture(prNumber: Long) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "DELETE FROM agent_audit_outbox WHERE payload::jsonb ->> 'pr_number' = ? " +
                    "AND payload::jsonb ->> 'eventType' = 'agent.pr.merged'",
            ).use { ps ->
                ps.setString(1, prNumber.toString())
                ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM agent_merge_record WHERE pr_number = ?").use { ps ->
                ps.setLong(1, prNumber)
                ps.executeUpdate()
            }
        }
    }

    private fun assertRuntimeFailureRollback(attempts: Map<Long, Int>) {
        val original = mergeCommand(prNumber = 9005)
        assertFailureRollsBackThenRetry(original, IllegalStateException::class.java, "injected post-enqueue failure")
        assertThat(attempts[9005L]).isEqualTo(2)
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT event_id, payload FROM agent_audit_outbox " +
                    "WHERE payload::jsonb ->> 'pr_number' = '9005' " +
                    "AND payload::jsonb ->> 'eventType' = 'agent.pr.merged'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    val eventId = rs.getObject("event_id", UUID::class.java)
                    val payload = rs.getString("payload")
                    assertThat(payload)
                        .contains(
                            eventId.toString(),
                            "agent.pr.merged",
                            original.mergedBy,
                            original.agentId,
                            original.headBranch,
                            original.source,
                        )
                    assertThat(rs.next()).isFalse()
                }
            }
        }
        assertThat(auditCountFor(9005)).isEqualTo(1)
    }

    private fun assertCheckedFailureRollback(attempts: Map<Long, Int>) {
        // Checked failures also need rollback: Jackson serialization and JDBC writes can throw
        // checked exceptions before the outbox acknowledges its insert.
        val checkedFailure = mergeCommand(prNumber = 9006)
        assertFailureRollsBackThenRetry(checkedFailure, SQLException::class.java, "injected checked enqueue failure")
        assertThat(attempts[9006L]).isEqualTo(2)
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT count(*) FROM agent_audit_outbox " +
                    "WHERE payload::jsonb ->> 'pr_number' = '9006' " +
                    "AND payload::jsonb ->> 'eventType' = 'agent.pr.merged'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_ADMIN"])
    fun `ingest persists a row and enqueues exactly one audit event`() {
        given()
            .contentType("application/json")
            .body(ingestBody())
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(200)
            .body("agentId", `is`("flaky-test-hunter"))
            .body("prNumber", `is`(9001))
            .body("mergedBy", `is`("octo-reviewer"))
            .body("source", `is`("github-actions"))
            .body("inserted", `is`(true))

        dataSource.connection.use { c ->
            c.prepareStatement("SELECT agent_id, merged_by, source FROM agent_merge_record WHERE pr_number = 9001")
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getString("agent_id")).isEqualTo("flaky-test-hunter")
                        assertThat(rs.getString("merged_by")).isEqualTo("octo-reviewer")
                        assertThat(rs.getString("source")).isEqualTo("github-actions")
                    }
                }
        }
        assertThat(auditCountFor(9001)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_ADMIN"])
    fun `duplicate redelivery returns the stored record without a second audit event`() {
        given()
            .contentType("application/json")
            .body(ingestBody(prNumber = 9002, title = "original title"))
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(200)
            .body("inserted", `is`(true))

        given()
            .contentType("application/json")
            .body(ingestBody(prNumber = 9002, title = "tampered redelivery title"))
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(200)
            .body("inserted", `is`(false))
            .body("title", `is`("original title"))

        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM agent_merge_record WHERE pr_number = 9002").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getLong(1)).isEqualTo(1)
                }
            }
        }
        assertThat(auditCountFor(9002)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_ADMIN"])
    fun `runtime and checked audit failures roll back merge row so redelivery retries both`() {
        try {
            val attempts = installAuditFailureSpy()
            assertRuntimeFailureRollback(attempts)
            assertCheckedFailureRollback(attempts)
        } finally {
            // Keep this integration fixture isolated even when an assertion or retry fails.
            try {
                deleteMergeFixture(9005)
            } finally {
                deleteMergeFixture(9006)
            }
        }
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_ADMIN"])
    fun `missing required field returns 400`() {
        val body = ingestBody().toMutableMap()
        body.remove("mergedBy")
        given()
            .contentType("application/json")
            .body(body)
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(400)
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_ADMIN"])
    fun `malformed proposalId returns 400`() {
        given()
            .contentType("application/json")
            .body(ingestBody(prNumber = 9003) + ("proposalId" to "not-a-uuid"))
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(400)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `ingest with a non-admin role is rejected`() {
        given()
            .contentType("application/json")
            .body(ingestBody(prNumber = 9004))
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(403)
    }

    @Test
    fun `GET without an authenticated role is rejected`() {
        given()
            .`when`().get("/api/v1/agent-merge-records")
            .then()
            .statusCode(anyOf(equalTo(401), equalTo(403)))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `GET filters by agentId`() {
        recordMerge.record(mergeCommand(agentId = "merge-filter-a", prNumber = 9010))
        recordMerge.record(mergeCommand(agentId = "merge-filter-b", prNumber = 9011))

        given()
            .`when`().get("/api/v1/agent-merge-records?agentId=merge-filter-a")
            .then()
            .statusCode(200)
            .body("size()", `is`(1))
            .body("[0].agentId", `is`("merge-filter-a"))
            .body("[0].prNumber", `is`(9010))
    }

    @Test
    @TestSecurity(user = "compliance", roles = ["ROLE_COMPLIANCE"])
    fun `GET is open to compliance`() {
        recordMerge.record(mergeCommand(prNumber = 9020))

        given()
            .`when`().get("/api/v1/agent-merge-records")
            .then()
            .statusCode(200)
            .body("size()", `is`(1))
            .body("[0].prNumber", `is`(9020))
    }
}
