// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.fixtures.journalTransaction
import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.libs.api.pagination.CursorEncoder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LedgerJournalKeysetIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `journal cursor crosses tied timestamps and dates without skipping entries`() {
        val transaction = UUID.randomUUID()
        val from = LocalDate.parse("2042-04-18")
        val to = from.plusDays(1)
        val timestamp = OffsetDateTime.parse("2042-04-19T10:00:00Z")
        val tied = List(3) { UUID.randomUUID() }.sortedByDescending(UUID::toString)
        val olderTimestamp = UUID.randomUUID()
        val olderDate = UUID.randomUUID()
        val outsideRange = UUID.randomUUID()

        try {
            tied.reversed().forEach { seed(it, transaction, to, timestamp) }
            seed(olderTimestamp, transaction, to, timestamp.minusHours(1))
            seed(olderDate, transaction, from, timestamp.plusHours(1))
            seed(outsideRange, transaction, from.minusDays(1), timestamp)

            val first = page(from, to)
            assertThat(first.getList<String>("data.id")).containsExactlyElementsOf(tied.take(2).map(UUID::toString))
            assertThat(first.getInt("data[0].lines.size()")).isEqualTo(2)
            assertThat(first.getInt("data[1].lines.size()")).isEqualTo(2)
            val second = page(from, to, first.getString("pagination.nextCursor"))
            assertThat(second.getList<String>("data.id"))
                .containsExactly(tied.last().toString(), olderTimestamp.toString())
            val last = page(from, to, second.getString("pagination.nextCursor"))
            assertThat(last.getList<String>("data.id")).containsExactly(olderDate.toString())
            assertThat(last.getBoolean("pagination.hasNextPage")).isFalse()
            assertThat(page(from, to, CursorEncoder.encode(outsideRange.toString())).getList<String>("data.id"))
                .isEqualTo(first.getList<String>("data.id"))
        } finally {
            cleanup(transaction)
        }
    }

    private fun cleanup(transaction: UUID) {
        // Posted journals are append-only (ADR-0372): removing the fixture is a declared repair, and
        // lines and journal rows go in one transaction so no posted journal is left without lines.
        dataSource.connection.use { connection ->
            connection.journalTransaction(KEYSET_FIXTURE_REPAIR) {
                listOf(
                    "DELETE FROM journal_lines WHERE journal_id IN (SELECT id FROM journal_entries WHERE transaction_id = ?)",
                    "DELETE FROM journal_entries WHERE transaction_id = ?",
                ).forEach { sql ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setObject(1, transaction)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun page(from: LocalDate, to: LocalDate, cursor: String? = null): JsonPath {
        val request = given()
            .queryParam("fromDate", from.toString())
            .queryParam("toDate", to.toString())
            .queryParam("limit", 2)
        if (cursor != null) request.queryParam("cursor", cursor)
        return request.get("/api/v1/journals").then().statusCode(200).extract().jsonPath()
    }

    /**
     * The journal row and both of its lines commit together: the database refuses a POSTED journal
     * that commits without a balanced debit and credit line (ADR-0372), which a statement-by-statement
     * autocommit seed would do on the journal row alone.
     */
    private fun seed(id: UUID, transaction: UUID, day: LocalDate, timestamp: OffsetDateTime) {
        dataSource.connection.use { connection ->
            connection.journalTransaction { insertBalancedJournal(connection, id, transaction, day, timestamp) }
        }
    }

    private fun insertBalancedJournal(
        connection: Connection,
        id: UUID,
        transaction: UUID,
        day: LocalDate,
        timestamp: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO journal_entries(id, transaction_id, entry_date, value_date, status, created_at, created_by)
            VALUES (?, ?, ?, ?, 'POSTED', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, transaction)
            statement.setObject(3, day)
            statement.setObject(4, day)
            statement.setObject(5, timestamp)
            statement.setObject(6, UUID.randomUUID())
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO journal_lines(
                journal_id, gl_account_id, side, amount, currency_code, base_amount, base_currency, sequence
            )
            SELECT ?, id, CASE code WHEN '1001' THEN 'D' ELSE 'C' END, 10, 'CZK', 10, 'CZK',
                CASE code WHEN '1001' THEN 1 ELSE 2 END FROM gl_accounts WHERE code IN ('1001', '2001')
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            assertThat(statement.executeUpdate()).isEqualTo(2)
        }
    }
}

private const val KEYSET_FIXTURE_REPAIR = "LedgerJournalKeysetIT fixture teardown"
