// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * End-to-end evidence proof: the statutory lifecycle crosses HTTP as two different principals;
 * JDBC observes only committed state and installs a deliberately failing DB trigger for rollback.
 * It never calls a reactive repository directly, because that would bypass REST/authn/transaction
 * wiring and cannot prove the production workflow.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClosedPeriodEvidenceIT {

    @Inject
    lateinit var dataSource: DataSource

    private companion object {
        const val PERIOD_DATE = "2026-05-31"
        const val ROLLBACK_PERIOD_DATE = "2026-04-30"
        const val HASH_ONLY_PERIOD_DATE = "2026-03-31"
        val journalTransactionId: UUID = UUID.randomUUID()
        var periodId: UUID? = null
        var rollbackPeriodId: UUID? = null
    }

    @Test
    @Order(1)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts the source journal and creates a DRAFT through HTTP`() {
        val journal = """
            {
              "idempotencyKey":"${UUID.randomUUID()}","transactionId":"$journalTransactionId",
              "entryDate":"2026-05-15","valueDate":"2026-05-15","description":"Frozen evidence IT",
              "createdBy":"00000000-0000-0000-0000-000000000701","lines":[
                {"glAccountId":"a0000000-0000-0000-0000-000000000001","side":"DEBIT","amount":"1000.00","currencyCode":"CZK","baseAmount":"1000.00","baseCurrencyCode":"CZK"},
                {"glAccountId":"a0000000-0000-0000-0000-000000000002","side":"CREDIT","amount":"1000.00","currencyCode":"CZK","baseAmount":"1000.00","baseCurrencyCode":"CZK"}
              ]
            }
        """.trimIndent()
        given().contentType(
            "application/json",
        ).body(journal).`when`().post("/api/v1/journals").then().log().all().statusCode(201)

        periodId = UUID.fromString(
            given().contentType(
                "application/json",
            ).`when`().post("/api/v1/ledger/periods/MONTH/$PERIOD_DATE").then().statusCode(200)
                .extract().jsonPath().getString("id"),
        )
        assertThat(periodId).isNotNull()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `different checker freezes exact V23 evidence through HTTP`() {
        given().contentType(
            "application/json",
        ).`when`().post("/api/v1/ledger/periods/MONTH/$PERIOD_DATE/freeze").then().statusCode(200)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select status, evidence_state from ledger_closed_period where id = ?",
            ).use { statement ->
                statement.setObject(1, requireNotNull(periodId))
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    assertThat(rows.getString("status")).isEqualTo("FROZEN")
                    assertThat(rows.getString("evidence_state")).isEqualTo("LINES_V1")
                }
            }
            assertThat(
                count(connection, "ledger_closed_period_trial_balance_line", requireNotNull(periodId)),
            ).isEqualTo(2)
            assertThat(count(connection, "ledger_outbox", requireNotNull(periodId), "PeriodFrozen")).isEqualTo(1)
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `regulatory endpoint returns the frozen immutable lines`() {
        given().accept("application/json").`when`()
            .get("/api/v1/ledger/periods/MONTH/$PERIOD_DATE/frozen-trial-balance")
            .then().statusCode(200).body("lines.size()", org.hamcrest.Matchers.equalTo(2))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "maker-rollback", roles = ["ROLE_OPERATOR"])
    fun `persistence failure during HTTP freeze rolls back status evidence and outbox`() {
        val journal = """
            {"idempotencyKey":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}",
             "entryDate":"2026-04-15","valueDate":"2026-04-15","description":"Rollback evidence IT","createdBy":"00000000-0000-0000-0000-000000000702","lines":[
             {"glAccountId":"a0000000-0000-0000-0000-000000000001","side":"DEBIT","amount":"500.00","currencyCode":"CZK","baseAmount":"500.00","baseCurrencyCode":"CZK"},
             {"glAccountId":"a0000000-0000-0000-0000-000000000002","side":"CREDIT","amount":"500.00","currencyCode":"CZK","baseAmount":"500.00","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
        given().contentType("application/json").body(journal).`when`().post("/api/v1/journals").then().statusCode(201)
        rollbackPeriodId = UUID.fromString(
            given().contentType(
                "application/json",
            ).`when`().post("/api/v1/ledger/periods/MONTH/$ROLLBACK_PERIOD_DATE").then().statusCode(200)
                .extract().jsonPath().getString("id"),
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """create function fail_closed_period_evidence_it() returns trigger as $$ begin raise exception 'controlled evidence failure'; end; $$ language plpgsql""",
                )
                statement.execute(
                    "create trigger fail_closed_period_evidence_it before insert on ledger_closed_period_trial_balance_line for each row execute function fail_closed_period_evidence_it()",
                )
            }
        }

        // A different principal is required for the HTTP freeze; TestSecurity identities are fixed
        // per method, so the checker request is deliberately exercised in the next ordered test.
    }

    @Test
    @Order(5)
    @TestSecurity(user = "checker-rollback", roles = ["ROLE_OPERATOR"])
    fun `controlled persistence failure leaves the HTTP draft completely uncommitted`() {
        given().contentType(
            "application/json",
        ).`when`().post("/api/v1/ledger/periods/MONTH/$ROLLBACK_PERIOD_DATE/freeze").then().statusCode(500)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "drop trigger fail_closed_period_evidence_it on ledger_closed_period_trial_balance_line",
                )
                statement.execute("drop function fail_closed_period_evidence_it()")
            }
            connection.prepareStatement(
                "select status, evidence_state from ledger_closed_period where id = ?",
            ).use { statement ->
                statement.setObject(1, requireNotNull(rollbackPeriodId))
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    assertThat(rows.getString("status")).isEqualTo("DRAFT")
                    assertThat(rows.getString("evidence_state")).isEqualTo("NONE")
                }
            }
            assertThat(
                count(connection, "ledger_closed_period_trial_balance_line", requireNotNull(rollbackPeriodId)),
            ).isZero()
            assertThat(count(connection, "ledger_outbox", requireNotNull(rollbackPeriodId), "PeriodFrozen")).isZero()
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `legacy HASH_ONLY is rejected while empty LINES_V1 is valid frozen evidence`() {
        dataSource.connection.use { connection ->
            insertFrozen(connection, HASH_ONLY_PERIOD_DATE, "HASH_ONLY", UUID.randomUUID())
            insertFrozen(connection, "2026-02-28", "LINES_V1", UUID.randomUUID())
        }
        given().`when`().get(
            "/api/v1/ledger/periods/MONTH/$HASH_ONLY_PERIOD_DATE/frozen-trial-balance",
        ).then().statusCode(409)
        given().`when`().get("/api/v1/ledger/periods/MONTH/2026-02-28/frozen-trial-balance").then().statusCode(200)
            .body("lines.size()", org.hamcrest.Matchers.equalTo(0))
    }

    private fun count(connection: Connection, table: String, aggregateId: UUID, eventType: String? = null): Long {
        val sql = if (eventType ==
            null
        ) {
            "select count(*) from $table where period_id = ?"
        } else {
            "select count(*) from $table where aggregate_id = ? and event_type = ?"
        }
        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, aggregateId)
            if (eventType != null) statement.setString(2, eventType)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun insertFrozen(connection: Connection, date: String, evidenceState: String, id: UUID) {
        connection.prepareStatement(
            """insert into ledger_closed_period (id, period_type, period_from, period_to, status, evidence_state, computed_at, total_debits, total_credits, account_count, content_hash, drafted_by, frozen_by, frozen_at, created_at, updated_at)
               values (?, 'MONTH', ?::date, (?::date + interval '1 month - 1 day')::date, 'FROZEN', ?, ?, 0, 0, 0, ?, 'maker', 'checker', ?, ?, ?)""",
        ).use { statement ->
            val now = Instant.parse("2026-08-01T00:00:00Z")
            val periodStart = date.substring(0, 8) + "01"
            val emptyHash = PeriodTrialBalance(
                PeriodType.MONTH.of(java.time.LocalDate.parse(date)),
                emptyList(),
            ).contentHash()
            statement.setObject(1, id)
            statement.setString(2, periodStart)
            statement.setString(3, periodStart)
            statement.setString(4, evidenceState)
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setString(6, emptyHash)
            statement.setTimestamp(7, Timestamp.from(now))
            statement.setTimestamp(8, Timestamp.from(now))
            statement.setTimestamp(9, Timestamp.from(now))
            statement.executeUpdate()
        }
    }
}
