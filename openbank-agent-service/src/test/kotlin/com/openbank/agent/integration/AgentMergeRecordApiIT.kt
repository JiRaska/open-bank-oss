// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.agent.application.port.`in`.MergeRecordCommand
import com.openbank.agent.application.port.`in`.RecordMergeUseCase
import com.openbank.agent.domain.mergerecord.MergeActorType
import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeAuthorizationKind
import com.openbank.agent.domain.mergerecord.MergeAuthorizationScope
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

    @Inject
    lateinit var objectMapper: ObjectMapper

    private fun appAuthorization() = MergeAuthorization(
        approvedBy = "fixture-owner",
        approvedById = 1234,
        approvedAt = Instant.parse("2026-10-01T11:59:00Z"),
        evidenceUrl = "https://github.com/JiRaska/open-bank-oss/actions/runs/4321",
        statusId = 5678,
        workflowRunId = 4321,
    )

    private fun appBody(prNumber: Long): Map<String, Any> = ingestBody(prNumber = prNumber) + mapOf(
        "mergedBy" to "openbank-gitops-bot[bot]",
        "mergedByType" to "GITHUB_APP",
        "mergedById" to 9876,
        "headSha" to "a".repeat(40),
        "mergeCommitSha" to "b".repeat(40),
        "authorization" to appAuthorization(),
    )

    private fun legacyAuthorization(prNumber: Long) = MergeAuthorization(
        approvedBy = "fixture-owner",
        approvedById = 1234,
        approvedAt = Instant.parse("2026-10-01T11:59:00Z"),
        evidenceUrl = "https://github.com/JiRaska/open-bank-oss/pull/$prNumber",
        kind = MergeAuthorizationKind.LEGACY_PR_AUTO_MERGE,
        scope = MergeAuthorizationScope.PR,
        pullRequestNodeId = "PR_fixture",
        mergeEventNodeId = "ME_fixture",
        mergeEventAt = Instant.parse("2026-10-01T12:00:01Z"),
        observedAt = Instant.parse("2026-10-24T12:00:00Z"),
    )

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
        assertThat(retry.record.mergedByType).isEqualTo(command.mergedByType)
        assertThat(retry.record.authorization).isEqualTo(command.authorization)
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
        val original = mergeCommand(prNumber = 9005).copy(
            mergedBy = "openbank-gitops-bot[bot]",
            mergedByType = MergeActorType.GITHUB_APP,
            mergedById = 9876,
            headSha = "a".repeat(40),
            mergeCommitSha = "b".repeat(40),
            authorization = appAuthorization(),
        )
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
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `ingest persists a row and enqueues exactly one audit event`() {
        given()
            .contentType("application/json")
            .body(
                ingestBody() + mapOf(
                    "authorization" to null,
                    "mergedById" to null,
                    "headSha" to null,
                    "mergeCommitSha" to null,
                ),
            )
            .`when`().post("/api/v1/agent-merge-records")
            .then()
            .statusCode(200)
            .body("agentId", `is`("flaky-test-hunter"))
            .body("prNumber", `is`(9001))
            .body("mergedBy", `is`("octo-reviewer"))
            .body("mergedByType", `is`("HUMAN"))
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
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
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
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
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
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
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
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
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
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `ingest with ROLE_ADMIN but without ROLE_MERGE_RECORDER is rejected`() {
        given().get("/api/v1/agent-merge-records/capabilities").then().statusCode(403)
        given().get("/api/v1/agent-merge-records/9030").then().statusCode(403)
        given().contentType("application/json").body(appBody(9007))
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(403)
        // Least-privilege guard (ADR-0031 phase 3 hardening): an interactive bank
        // administrator must NOT be able to write merge evidence — only the dedicated
        // ROLE_MERGE_RECORDER carried by the CI recorder client may.
        given()
            .contentType("application/json")
            .body(ingestBody(prNumber = 9007))
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
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `App executor and human authorization persist separately and conflicting retries fail closed`() {
        given().get("/api/v1/agent-merge-records/capabilities").then().statusCode(200)
            .body("evidenceSchemaVersion", `is`(2))
        given().get("/api/v1/agent-merge-records/9030").then().statusCode(404)
        val body = appBody(9030)
        given().contentType("application/json").body(body)
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(200)
            .body("mergedBy", `is`("openbank-gitops-bot[bot]"))
            .body("mergedByType", `is`("GITHUB_APP"))
            .body("authorization.approvedBy", `is`("fixture-owner"))
            .body("headSha", `is`("a".repeat(40)))
            .body("inserted", `is`(true))
        given().contentType("application/json").body(body + ("title" to "redelivered title"))
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(200)
            .body("inserted", `is`(false))
        val conflicts = listOf(
            body + ("headSha" to "c".repeat(40)),
            body + ("mergeCommitSha" to "d".repeat(40)),
            body + ("mergedById" to 9999),
            body + ("authorization" to appAuthorization().copy(statusId = 9999)),
        )
        conflicts.forEach {
            given().contentType("application/json").body(it)
                .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(409)
        }
        given().contentType("application/json").body(ingestBody(prNumber = 9030))
            .`when`().post("/api/v1/agent-merge-records").then().statusCode(409)
        given().get("/api/v1/agent-merge-records/9030").then().statusCode(200)
            .body("mergedBy", `is`("openbank-gitops-bot[bot]"))
            .body("authorization.approvedBy", `is`("fixture-owner"))
            .body("headSha", `is`("a".repeat(40)))
        assertMergeCount(9030, 1)
        assertThat(auditCountFor(9030)).isEqualTo(1)
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT payload FROM agent_audit_outbox WHERE payload::jsonb ->> 'pr_number' = '9030'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    val payload = objectMapper.readTree(rs.getString(1))
                    assertThat(payload.path("actorId").asText()).isEqualTo("openbank-gitops-bot[bot]")
                    assertThat(payload.path("actorType").asText()).isEqualTo("SERVICE")
                    assertThat(payload.path("authorization").path("approvedBy").asText()).isEqualTo("fixture-owner")
                    assertThat(payload.path("head_sha").asText()).isEqualTo("a".repeat(40))
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `invalid or missing App provenance and bot masquerading as human are rejected without writes`() {
        val body = appBody(9031)
        val authorization = objectMapper.convertValue(appAuthorization(), Map::class.java)
        val forgedUrl = "https://example.invalid/actions/runs/4321"
        val invalid = listOf(
            body - "authorization", body - "headSha", body - "mergeCommitSha", body - "mergedById",
            body - "mergedByType", body + ("mergedByType" to "HUMAN"),
            body + ("mergedById" to 0), body + ("headSha" to "a".repeat(39)),
            body + ("mergeCommitSha" to "B".repeat(40)),
            body + ("mergedBy" to "untrusted[bot]"),
            body + ("authorization" to appAuthorization().copy(approvedAt = Instant.parse("2026-10-01T12:01:00Z"))),
            body + ("authorization" to (authorization + ("approvedBy" to "some[bot]"))),
            body + ("authorization" to (authorization + ("approvedById" to 0))),
            body + ("authorization" to (authorization + ("statusId" to -1))),
            body + ("authorization" to (authorization + ("workflowRunId" to 0))),
            body + ("authorization" to (authorization + ("evidenceUrl" to forgedUrl))),
            body + ("authorization" to (authorization + ("workflowRunId" to 4322))),
            ingestBody(prNumber = 9031, mergedBy = "openbank-gitops-bot[bot]"),
            ingestBody(prNumber = 9031) + ("authorization" to appAuthorization()),
        )
        invalid.forEach {
            given().contentType("application/json").body(it)
                .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(400)
            assertMergeCount(9031, 0)
            assertThat(auditCountFor(9031)).isZero()
        }
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `legacy App reconstruction remains PR scoped and refresh retries preserve original observation`() {
        val authorization = legacyAuthorization(9032)
        val body = appBody(9032) + ("authorization" to authorization)
        given().contentType("application/json").body(body)
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(200)
            .body("mergedByType", `is`("GITHUB_APP"))
            .body("authorization.kind", `is`("LEGACY_PR_AUTO_MERGE"))
            .body("authorization.scope", `is`("PR"))
        given().contentType("application/json")
            .body(body + ("authorization" to authorization.copy(observedAt = Instant.parse("2026-10-24T12:01:00Z"))))
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(200)
            .body("inserted", `is`(false))
        val stored = given().get("/api/v1/agent-merge-records/9032").then().statusCode(200).extract().asString()
        val storedProof = objectMapper.readTree(stored).path("authorization")
        assertThat(Instant.parse(storedProof.path("observedAt").asText())).isEqualTo(authorization.observedAt)
        assertThat(storedProof.path("scope").asText()).isEqualTo("PR")
        given().contentType("application/json")
            .body(body + ("authorization" to authorization.copy(mergeEventNodeId = "ME_other")))
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(409)
        assertThat(auditCountFor(9032)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `legacy reconstruction cannot authorize current merges or claim exact-head scope`() {
        val proof = objectMapper.convertValue(legacyAuthorization(9033), Map::class.java)
        val body = appBody(9033) + ("authorization" to proof)
        val wrongPrUrl = "https://github.com/JiRaska/open-bank-oss/pull/9034"
        val invalid = listOf(
            body + ("mergedAt" to "2026-10-23T19:42:18Z"),
            body + ("authorization" to (proof + ("scope" to "HEAD"))),
            body + ("authorization" to (proof + ("statusId" to 1))),
            body + ("authorization" to (proof + ("observedAt" to "2026-10-01T11:59:00Z"))),
            body + ("authorization" to (proof + ("observedAt" to "9999-01-01T00:00:00Z"))),
            body + ("authorization" to (proof + ("observedAt" to "2026-10-01T12:00:00Z"))),
            body + ("authorization" to (proof + ("mergeEventAt" to "2026-10-01T11:58:00Z"))),
            body + ("authorization" to (proof + ("pullRequestNodeId" to ""))),
            body + ("authorization" to (proof + ("mergeEventNodeId" to ""))),
            body + ("authorization" to (proof + ("evidenceUrl" to wrongPrUrl))),
        )
        invalid.forEach {
            given().contentType("application/json").body(it)
                .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(400)
            assertMergeCount(9033, 0)
            assertThat(auditCountFor(9033)).isZero()
        }
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `nanosecond App evidence normalizes to database precision before replay comparison`() {
        val body = appBody(9034) + mapOf(
            "mergedAt" to "2026-10-01T12:00:00.123456789Z",
            "authorization" to appAuthorization().copy(
                approvedAt = Instant.parse("2026-10-01T11:59:00.123456789Z"),
            ),
        )
        given().contentType("application/json").body(body)
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(200)
            .body("inserted", `is`(true))
        given().contentType("application/json").body(body)
            .`when`().post("/api/v1/agent-merge-records/app").then().statusCode(200)
            .body("inserted", `is`(false))
        val stored = given().get("/api/v1/agent-merge-records/9034").then().statusCode(200).extract().asString()
        val evidence = objectMapper.readTree(stored)
        assertThat(Instant.parse(evidence.path("mergedAt").asText()))
            .isEqualTo(Instant.parse("2026-10-01T12:00:00.123456Z"))
        assertThat(Instant.parse(evidence.path("authorization").path("approvedAt").asText()))
            .isEqualTo(Instant.parse("2026-10-01T11:59:00.123456Z"))
        assertThat(auditCountFor(9034)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "pipeline", roles = ["ROLE_MERGE_RECORDER"])
    fun `legacy human input ignores unknown fields without interpreting them as App authorization`() {
        val legacy = ingestBody(prNumber = 9036) + mapOf(
            "mergedByType" to "GITHUB_APP",
            "mergedById" to -1,
            "authorization" to "ignored legacy extension",
            "headSha" to "not-a-sha",
        )
        val response = given().contentType("application/json").body(legacy)
            .`when`().post("/api/v1/agent-merge-records").then().statusCode(200).extract().asString()
        val record = objectMapper.readTree(response)
        assertThat(record.path("mergedBy").asText()).isEqualTo("octo-reviewer")
        assertThat(record.path("mergedByType").asText()).isEqualTo("HUMAN")
        assertThat(record.path("authorization").isNull).isTrue()
        assertThat(record.path("headSha").isNull).isTrue()
        assertThat(auditCountFor(9036)).isEqualTo(1)
        given().contentType("application/json").body(appBody(9037))
            .`when`().post("/api/v1/agent-merge-records").then().statusCode(400)
        assertMergeCount(9037, 0)
        assertThat(auditCountFor(9037)).isZero()
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
        recordMerge.record(mergeCommand(agentId = "compliance-fixture", prNumber = 9020))

        given()
            .`when`().get("/api/v1/agent-merge-records?agentId=compliance-fixture")
            .then()
            .statusCode(200)
            .body("size()", `is`(1))
            .body("[0].prNumber", `is`(9020))
    }
}
