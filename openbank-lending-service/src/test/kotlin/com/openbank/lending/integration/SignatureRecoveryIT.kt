// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class SignatureRecoveryIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = SIGNER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `the scheduler recovers a staged signature after the application transaction rolls back`() {
        val application = seedApplication()
        execute(
            "ALTER TABLE lending_outbox ADD CONSTRAINT test_signature_interruption " +
                "CHECK (aggregate_id <> '$application'::uuid)",
        )
        try {
            assertThat(sign(application, ARTEFACT)).isGreaterThanOrEqualTo(400)
            assertThat(scalar("SELECT status::text FROM loan_application WHERE id = ?", application))
                .isEqualTo("AWAITING_SIGNATURE")
            val intentSql = "SELECT artefact_hash FROM lending_signature_intent WHERE application_id = ?"
            assertThat(scalar(intentSql, application)).isEqualTo(ARTEFACT)
        } finally {
            execute("ALTER TABLE lending_outbox DROP CONSTRAINT test_signature_interruption")
        }
        execute(
            "UPDATE lending_signature_intent SET next_attempt_at = clock_timestamp() WHERE application_id = ?",
            application,
        )

        awaitSignature(application)
        assertThat(outboxCount(application)).isEqualTo(1L)
        assertAtomicCompletion(application)
        assertThat(sign(application, ARTEFACT)).isEqualTo(200)
        assertThat(sign(application, "bb".repeat(32))).isEqualTo(422)
        assertThat(outboxCount(application)).isEqualTo(1L)
        assertIntentOutlivesDeleteAttempt(application)
    }

    /** The intent is signature evidence: deleting its application is refused, never cascaded. */
    private fun assertIntentOutlivesDeleteAttempt(application: UUID) {
        assertThatThrownBy { execute("DELETE FROM loan_application WHERE id = ?", application) }
            .isInstanceOfSatisfying(SQLException::class.java) {
                assertThat(it.sqlState).isEqualTo(RESTRICT_VIOLATION)
            }
        val intentSql = "SELECT artefact_hash FROM lending_signature_intent WHERE application_id = ?"
        assertThat(scalar(intentSql, application)).isEqualTo(ARTEFACT)
    }

    private fun sign(application: UUID, artefactHash: String): Int = given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(mapOf("artefactHash" to artefactHash))
        .post("/api/v1/lending/applications/$application/sign")
        .statusCode

    private fun awaitSignature(application: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos()
        val sql = "SELECT completed_at IS NOT NULL FROM lending_signature_intent WHERE application_id = ?"
        while (scalar(sql, application) != true && System.nanoTime() < deadline) Thread.sleep(50)
        assertThat(scalar(sql, application)).isEqualTo(true)
        assertThat(scalar("SELECT status::text FROM loan_application WHERE id = ?", application)).isEqualTo("SIGNED")
    }

    private fun assertAtomicCompletion(application: UUID) {
        val sql = """
            SELECT i.xmin = a.xmin AND o.xmin = a.xmin FROM lending_signature_intent i
            JOIN loan_application a ON a.id = i.application_id
            JOIN lending_outbox o ON o.aggregate_id = a.id
            WHERE a.id = ? AND o.event_type = 'credit.application.transition'
                AND o.payload::jsonb ->> 'toState' = 'SIGNED'
        """.trimIndent()
        assertThat(scalar(sql, application)).isEqualTo(true)
    }

    private fun outboxCount(application: UUID): Any = checkNotNull(
        scalar(
            """
            SELECT count(*) FROM lending_outbox WHERE aggregate_id = ? AND event_type = 'credit.application.transition'
                AND payload::jsonb ->> 'toState' = 'SIGNED'
            """.trimIndent(),
            application,
        ),
    )

    private fun seedApplication(): UUID {
        val application = UUID.randomUUID()
        execute(
            """
            INSERT INTO loan_application (
                id, party_id, requested_amount, currency, nominal_annual_rate, term_periods,
                first_due_date, status, proposed_by, decided_by, decided_at, jurisdiction,
                catalog_offering_id, catalog_revision_id, catalog_content_hash, catalog_schema_version
            ) VALUES (?, ?, 10000, 'CZK', 0.05, 12, CURRENT_DATE + 30, 'AWAITING_SIGNATURE',
                'synthetic-maker', 'synthetic-checker', CURRENT_TIMESTAMP, 'CZ', ?, ?, ?, 1)
            """.trimIndent(),
            application,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "11".repeat(32),
        )
        return application
    }

    private fun execute(sql: String, vararg args: Any) = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeUpdate()
        }
    }

    private fun scalar(sql: String, id: UUID): Any? = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use(::firstColumn)
        }
    }

    private fun firstColumn(rows: ResultSet): Any? = if (rows.next()) rows.getObject(1) else null

    private companion object {
        const val SIGNER = "synthetic-signature-operator"
        val ARTEFACT = "aa".repeat(32)

        // ON DELETE RESTRICT raises restrict_violation, distinct from a generic foreign_key_violation (23503).
        const val RESTRICT_VIOLATION = "23001"
    }
}
