// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import com.openbank.fraud.application.port.out.FraudAssignedCandidates
import com.openbank.fraud.application.port.out.FraudCaseAccessDecision
import com.openbank.fraud.infrastructure.client.FraudCaseContextAccessAdapter
import com.openbank.fraud.it.PostgresRedisTestResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class FraudInvestigationCaseIT {
    private lateinit var access: FraudCaseContextAccessAdapter

    @BeforeEach
    fun contextCaseAccess() {
        access = mockk()
        coEvery { access.check(any(), BEARER) } returns FraudCaseAccessDecision.ALLOWED
        coEvery { access.assignedCandidates(any(), BEARER) } returns FraudAssignedCandidates(emptyList(), false)
        QuarkusMock.installMockForType(access, FraudCaseContextAccessAdapter::class.java)
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `review score opens one case and close never creates a fraud finding`() {
        val scoreId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        seedScore(scoreId, "REVIEW", accountId, counterpartyId)

        val opened = given().contentType("application/json")
            .header("X-Investigation-Purpose", PURPOSE)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"scoreId":"$scoreId"}""")
            .post("/api/v1/fraud/cases").then().statusCode(201)
            .header("Cache-Control", "no-store")
            .body("status", equalTo("OPEN"))
            .body("revision", equalTo(1))
            .extract().response()
        val caseId = UUID.fromString(opened.jsonPath().getString("caseId"))
        assertThat(opened.body.asString()).doesNotContain(accountId.toString(), counterpartyId.toString())

        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"scoreId":"$scoreId"}""")
            .post("/api/v1/fraud/cases").then().statusCode(201)
            .body("caseId", equalTo(caseId.toString()))
        assertThat(caseCount(scoreId)).isEqualTo(1)
        assertThat(outboxCount(caseId)).isEqualTo(2)
        assertReference(caseId, 1, "fraud.case_opened", accountId, counterpartyId)
        assertAuditEvent(caseId, 1, "fraud.case_opened.audit", accountId, counterpartyId)

        given().header("X-Investigation-Purpose", PURPOSE)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(403)
            .header("Cache-Control", "no-store")
        assertThat(outboxCount(caseId)).isEqualTo(2)

        given().header("X-Investigation-Purpose", PURPOSE)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", BEARER)
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(200)
            .body("status", equalTo("CLOSED_NO_FINDING"))
            .body("revision", equalTo(2))
        given().header("X-Investigation-Purpose", PURPOSE)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", BEARER)
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(200)
            .body("revision", equalTo(2))
        assertThat(outboxCount(caseId)).isEqualTo(4)
        assertReference(caseId, 2, "fraud.case_closed", accountId, counterpartyId)
        assertAuditEvent(caseId, 2, "fraud.case_closed.audit", accountId, counterpartyId)
        given().header("X-Investigation-Purpose", PURPOSE).header("Authorization", BEARER)
            .get("/api/v1/fraud/cases/$caseId").then().statusCode(200)
            .header("Cache-Control", "no-store")
            .body("status", equalTo("CLOSED_NO_FINDING"))
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `human administrator cannot invoke service-only matching even with guessed case IDs`() {
        given().header("X-Investigation-Purpose", PURPOSE)
            .header("X-Investigator-Authorization", BEARER)
            .get("/api/v1/fraud/cases/${UUID.randomUUID()}/match-assigned").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_CONTEXT_INVESTIGATION"])
    fun `a shared service principal cannot masquerade as Context`() {
        given().header("X-Investigation-Purpose", PURPOSE)
            .header("X-Investigator-Authorization", BEARER)
            .get("/api/v1/fraud/cases/${UUID.randomUUID()}/match-assigned").then().statusCode(403)
    }

    @Test
    @TestSecurity(
        user = "service-account-openbank-context-investigation",
        roles = ["ROLE_CONTEXT_INVESTIGATION"],
    )
    fun `dedicated Context identity matches only assigned open same-role cases`() {
        val account = UUID.randomUUID()
        val counterparty = UUID.randomUUID()
        val root = seedCase(account, counterparty)
        val accountMatch = seedCase(account, UUID.randomUUID())
        val counterpartyMatch = seedCase(UUID.randomUUID(), counterparty)
        val crossed = seedCase(counterparty, account)
        val notSupplied = seedCase(account, null)
        val candidates = listOf(crossed, counterpartyMatch, accountMatch)
        coEvery { access.assignedCandidates(root, BEARER) } returns FraudAssignedCandidates(candidates, false)

        val response = given()
            .header("X-Investigation-Purpose", PURPOSE)
            .header("X-Investigator-Authorization", BEARER)
            .get("/api/v1/fraud/cases/$root/match-assigned").then().statusCode(200)
            .header("Cache-Control", "no-store").extract().response()
        assertThat(response.jsonPath().getList<String>("candidateIds"))
            .containsExactlyInAnyOrder(accountMatch.toString(), counterpartyMatch.toString())
        assertThat(response.jsonPath().getBoolean("truncated")).isFalse()
        assertThat(response.body.asString())
            .doesNotContain(account.toString(), counterparty.toString(), crossed.toString(), notSupplied.toString())
        coVerify(exactly = 1) { access.assignedCandidates(root, BEARER) }
        coVerify(exactly = 0) { access.check(root, BEARER) }

        given().header("X-Investigation-Purpose", PURPOSE)
            .header("X-Investigator-Authorization", BEARER)
            .queryParam("candidateIds", notSupplied.toString())
            .get("/api/v1/fraud/cases/$root/match-assigned").then().statusCode(200)
            .body("candidateIds.size()", equalTo(2))

        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/$root/match-assigned").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `a score without REVIEW or an account cannot anchor a case`() {
        val allowedScoreId = UUID.randomUUID()
        val missingAccountScoreId = UUID.randomUUID()
        seedScore(allowedScoreId, "ALLOW", UUID.randomUUID(), null)
        seedScore(missingAccountScoreId, "REVIEW", null, null)
        for (scoreId in listOf(allowedScoreId, missingAccountScoreId, UUID.randomUUID())) {
            given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("""{"scoreId":"$scoreId"}""")
                .post("/api/v1/fraud/cases").then().statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "fraud-reader", roles = ["ROLE_API"])
    fun `service identity cannot open or read cases`() {
        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"${UUID.randomUUID()}"}""")
            .post("/api/v1/fraud/cases").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/${UUID.randomUUID()}").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/${UUID.randomUUID()}/evidence").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "fraud-operator", roles = ["ROLE_OPERATOR"])
    fun `operator cannot read or close another case through the admin source API`() {
        val caseId = UUID.randomUUID()
        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"${UUID.randomUUID()}"}""")
            .post("/api/v1/fraud/cases").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/$caseId").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `missing purpose is rejected before case lookup`() {
        given().get("/api/v1/fraud/cases/${UUID.randomUUID()}").then().statusCode(400)
        given().get("/api/v1/fraud/cases/${UUID.randomUUID()}/evidence").then().statusCode(400)
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/${UUID.randomUUID()}/evidence").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `missing idempotency key is rejected before case mutation`() {
        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"${UUID.randomUUID()}"}""")
            .post("/api/v1/fraud/cases").then().statusCode(400)
        given().header("X-Investigation-Purpose", PURPOSE)
            .post("/api/v1/fraud/cases/${UUID.randomUUID()}/close-without-finding").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `close racing a committed transition returns current status without a second reference`() {
        val scoreId = UUID.randomUUID()
        seedScore(scoreId, "REVIEW", UUID.randomUUID(), null)
        val caseId = given().contentType("application/json")
            .header("X-Investigation-Purpose", PURPOSE)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"scoreId":"$scoreId"}""")
            .post("/api/v1/fraud/cases").then().statusCode(201)
            .extract().jsonPath().getString("caseId")

        val pool = Executors.newSingleThreadExecutor()
        connection().use { lock ->
            lock.autoCommit = false
            try {
                // Hold an uncommitted winner update. The losing HTTP request reads the old
                // committed OPEN snapshot, then waits on this row at its conditional UPDATE.
                lock.prepareStatement(
                    """UPDATE fraud_investigation_cases SET status = 'CLOSED_NO_FINDING',
                       revision = 2, closed_by = 'winning-admin', closed_at = now()
                       WHERE case_id = ?
                    """.trimIndent(),
                )
                    .use { stmt ->
                        stmt.setObject(1, UUID.fromString(caseId))
                        assertThat(stmt.executeUpdate()).isEqualTo(1)
                    }
                val close = CompletableFuture.supplyAsync({
                    given().header("X-Investigation-Purpose", PURPOSE)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", BEARER)
                        .post("/api/v1/fraud/cases/$caseId/close-without-finding")
                }, pool)
                waitForBlockedClose(lock, close)
                lock.commit()
                val response = close.get(15, TimeUnit.SECONDS)
                assertThat(response.statusCode).isEqualTo(200)
                assertThat(response.jsonPath().getInt("revision")).isEqualTo(2)
                assertThat(response.jsonPath().getString("status")).isEqualTo("CLOSED_NO_FINDING")
            } finally {
                lock.rollback()
                pool.shutdownNow()
            }
        }
        // This fixture performs the winner transition directly to force the race; the
        // losing application request must not publish a duplicate close reference.
        assertThat(outboxCount(UUID.fromString(caseId))).isEqualTo(2)
    }

    private fun waitForBlockedClose(
        connection: java.sql.Connection,
        close: CompletableFuture<io.restassured.response.Response>,
    ) {
        // A cold Quarkus/OPA client can delay this request before it reaches PostgreSQL.
        // Keep the assertion about the lock, but allow its startup path to settle in CI.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (blockedCloseCount(connection) >= 1) return
            if (close.isDone) error("close finished before the row lock with HTTP ${close.get().statusCode}")
            Thread.sleep(20)
        }
        error("close request did not reach a database lock")
    }

    private fun blockedCloseCount(connection: java.sql.Connection): Int = connection.prepareStatement(
        """SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid()
           AND wait_event_type = 'Lock' AND datname = current_database()
        """.trimIndent(),
    ).use { stmt ->
        stmt.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private fun seedScore(scoreId: UUID, verdict: String, accountId: UUID?, counterpartyId: UUID?) {
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO fraud_scores (id, score_id, amount, currency, rail, account_id,
                   counterparty_id, verdict, score, reasons_json, rule_version)
                   VALUES (nextval('fraud_scores_seq'), ?, 100.00, 'CZK', 'TEST', ?, ?, ?, 1, '[]', 'test')
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, scoreId)
                stmt.setObject(2, accountId)
                stmt.setObject(3, counterpartyId)
                stmt.setString(4, verdict)
                stmt.executeUpdate()
            }
        }
    }

    private fun seedCase(accountId: UUID, counterpartyId: UUID?): UUID {
        val scoreId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        seedScore(scoreId, "REVIEW", accountId, counterpartyId)
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO fraud_investigation_cases
                   (case_id, score_id, account_id, counterparty_id, status, revision, opened_by, opened_at)
                   VALUES (?, ?, ?, ?, 'OPEN', 1, 'case-seed', now())""",
            ).use { statement ->
                statement.setObject(1, caseId)
                statement.setObject(2, scoreId)
                statement.setObject(3, accountId)
                statement.setObject(4, counterpartyId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        return caseId
    }

    private fun caseCount(scoreId: UUID): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM fraud_investigation_cases WHERE score_id = ?").use { stmt ->
            stmt.setObject(1, scoreId)
            stmt.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun outboxCount(caseId: UUID): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM fraud_outbox WHERE aggregate_id = ?").use { stmt ->
            stmt.setObject(1, caseId)
            stmt.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun assertReference(caseId: UUID, revision: Int, type: String, accountId: UUID, counterpartyId: UUID) {
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT payload FROM fraud_outbox WHERE aggregate_id = ? AND event_type = ?",
            ).use { stmt ->
                stmt.setObject(1, caseId)
                stmt.setString(2, type)
                stmt.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    val payload = rows.getString(1)
                    val fields = com.fasterxml.jackson.databind.ObjectMapper().readTree(payload)
                    assertThat(fields.fieldNames().asSequence().toSet())
                        .containsExactlyInAnyOrder("eventType", "caseId", "revision", "occurredAt")
                    assertThat(fields.get("caseId").asText()).isEqualTo(caseId.toString())
                    assertThat(fields.get("revision").asInt()).isEqualTo(revision)
                    assertThat(payload).doesNotContain(accountId.toString(), counterpartyId.toString())
                }
            }
        }
    }

    private fun assertAuditEvent(caseId: UUID, revision: Int, type: String, accountId: UUID, counterpartyId: UUID) {
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT event_id, payload FROM fraud_outbox WHERE aggregate_id = ? AND event_type = ?",
            ).use { stmt ->
                stmt.setObject(1, caseId)
                stmt.setString(2, type)
                stmt.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    val eventId = rows.getObject(1, UUID::class.java)
                    val payload = rows.getString(2)
                    val fields = com.fasterxml.jackson.databind.ObjectMapper().readTree(payload)
                    assertThat(fields.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "aggregateId",
                        "actorId",
                        "revision",
                        "occurredAt",
                        "aggregateType",
                        "sourceService",
                    )
                    assertThat(fields.get("eventId").asText()).isEqualTo(eventId.toString())
                    assertThat(fields.get("aggregateId").asText()).isEqualTo(caseId.toString())
                    assertThat(fields.get("revision").asInt()).isEqualTo(revision)
                    assertThat(fields.get("actorId").asText()).isEqualTo("fraud-admin")
                    assertThat(payload).doesNotContain(accountId.toString(), counterpartyId.toString())
                }
            }
        }
    }

    private fun connection(): java.sql.Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val PURPOSE = "FRAUD_INVESTIGATION"
        const val BEARER = "Bearer fraud-case-it-token"
    }
}
