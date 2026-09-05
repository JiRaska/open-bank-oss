// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.integration

import com.openbank.clearing.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * #8509: `ClearingService.settleBatch` must commit the batch, its items and the outbox row in
 * ONE transaction. The oracle is Postgres's own `xmin` — the id of the transaction that wrote
 * each row version — so "same transaction" is read from the database, never inferred from row
 * presence: a three-transaction implementation satisfies every "the outbox row landed"
 * assertion the fleet already had, while still being able to lose
 * `openbank.clearing.batch.settled` on a crash between commits.
 *
 * Measured against the pre-fix three-call composition: batch xmin = 750, outbox xmin = 752 —
 * three distinct transactions. After the fix (`ClearingBatchRepository.settleWithEvent`, one
 * `@WithTransaction` boundary owned by the repository) all THREE row kinds carry the same xmin.
 *
 * The item rows are asserted too, and that is not belt-and-braces. Measured: with the items
 * moved back into their own transaction while batch and outbox stayed together, a batch-vs-outbox
 * assertion alone stays GREEN — so it cannot see a batch that commits SETTLED while its items
 * commit separately, which is its own defect (a settled batch whose items are not settled, the
 * inconsistency `reconcileBatch` exists to report). The oracle covers every row the settle
 * writes, so the whole three-way property is pinned rather than two thirds of it.
 *
 * The dispatcher is OFF for this test ([OutboxRepositoryIsolationProfile]): its UPDATE would
 * rewrite the outbox row's xmin and destroy the evidence. The flow is driven through real HTTP
 * (RestAssured + @TestSecurity) because a bare @QuarkusTest thread has no Vert.x context for
 * the reactive repositories, and only a real request exercises the production wiring.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@TestProfile(OutboxRepositoryIsolationProfile::class)
class ClearingSettleOutboxAtomicityIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `settle commits the batch and its outbox row in the same transaction`() {
        // One payment in, one cycle triggered, one batch settled — all over HTTP.
        Given {
            contentType("application/json")
            body(
                """
                {
                  "paymentId": "${UUID.randomUUID()}",
                  "paymentReference": "PAY-XMIN-001",
                  "debtorIban": "CZ6508000000192000145399",
                  "creditorIban": "DE89370400440532013000",
                  "debtorBic": "GIBACZPX",
                  "creditorBic": "COBADEFF",
                  "amount": "100.50",
                  "currency": "EUR",
                  "rail": "SEPA_SCT",
                  "endToEndId": "E2E-PAY-XMIN-001",
                  "remittanceInfo": "xmin oracle"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
            body("status", equalTo("PENDING"))
        }

        val batchId = (
            Given { contentType("application/json") } When {
                post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
            } Then {
                statusCode(200)
                body("id", notNullValue())
                body("status", equalTo("IN_CLEARING"))
            }
            ).extract().body().jsonPath().getString("id")

        Given { contentType("application/json") } When {
            post("/api/v1/clearing/batches/$batchId/settle")
        } Then {
            statusCode(200)
            body("status", equalTo("SETTLED"))
        }

        assertSameWriterTransaction(batchId)
    }

    /**
     * The known-negative for the oracle above. Every assertion in this class is of the form
     * "these rows agree"; such an assertion also passes when there are no rows to disagree —
     * so without this test a settle that wrote NOTHING would read exactly like an atomic one.
     * An id that was never settled must therefore return no row of any of the three kinds,
     * which is what makes the green above a statement about rows that actually exist.
     */
    @Test
    fun `the xmin oracle returns no rows for a batch that was never settled`() {
        val neverWritten = UUID.randomUUID().toString()
        dataSource.connection.use { conn ->
            assertThat(xmins(conn, "SELECT xmin FROM clearing_batches WHERE id = '$neverWritten'"))
                .describedAs("a batch id that was never written must have no batch row")
                .isEmpty()
            assertThat(xmins(conn, "SELECT xmin FROM clearing_items WHERE batch_id = '$neverWritten'"))
                .describedAs("a batch id that was never written must have no item rows")
                .isEmpty()
            assertThat(
                xmins(
                    conn,
                    "SELECT xmin FROM clearing_outbox WHERE aggregate_id = '$neverWritten' " +
                        "AND event_type = 'openbank.clearing.batch.settled'",
                ),
            ).describedAs("a batch id that was never written must have no outbox row").isEmpty()
        }
    }

    /**
     * The oracle (#8496): Postgres stamps each row version with `xmin`, the id of the writing
     * transaction — so "the batch row, its item rows and its outbox rows were written by the SAME
     * transaction" is read from the database, never inferred from the rows merely existing.
     *
     * ADR-0281 widens the assertion to the second outbox row: the `net_settlement.post` command
     * must share the same xmin — a SETTLED batch whose settlement-leg intent committed in a
     * different transaction could lose it on a crash between the two commits.
     */
    private fun assertSameWriterTransaction(batchId: String) {
        dataSource.connection.use { conn ->
            val batchXmins = xmins(conn, "SELECT xmin FROM clearing_batches WHERE id = '$batchId'")
            // Each list is asserted non-empty before it is compared: "they all agree" is
            // vacuously true of an empty set, so an unwritten row would otherwise read as atomic.
            assertThat(batchXmins).describedAs("no settled batch row for %s", batchId).hasSize(1)
            val batchXmin = batchXmins.single()

            val itemXmins = xmins(conn, "SELECT xmin FROM clearing_items WHERE batch_id = '$batchId'")
            assertThat(itemXmins).describedAs("no item rows for batch %s", batchId).isNotEmpty()

            val outboxXmins = xmins(
                conn,
                "SELECT xmin FROM clearing_outbox WHERE aggregate_id = '$batchId' " +
                    "AND event_type = 'openbank.clearing.batch.settled'",
            )
            assertThat(outboxXmins).describedAs("no settled outbox row for batch %s", batchId).hasSize(1)

            assertThat(outboxXmins.single())
                .describedAs(
                    "batch row xmin (%d) and outbox row xmin (%d) differ — the state change and " +
                        "its event committed in DIFFERENT transactions (#8509)",
                    batchXmin,
                    outboxXmins.single(),
                )
                .isEqualTo(batchXmin)
            assertThat(itemXmins.distinct())
                .describedAs(
                    "batch row xmin (%d) and item row xmins (%s) differ — the batch committed " +
                        "SETTLED in a different transaction from its items (#8509)",
                    batchXmin,
                    itemXmins.distinct(),
                )
                .containsExactly(batchXmin)

            val netSettlementXmin = conn.createStatement().executeQuery(
                "SELECT xmin FROM clearing_outbox WHERE aggregate_id = '$batchId' " +
                    "AND event_type = 'openbank.clearing.net_settlement.post'",
            ).apply { assertThat(next()).isTrue() }.getLong(1)
            assertThat(netSettlementXmin)
                .describedAs(
                    "batch row xmin (%d) and net_settlement.post command xmin (%d) differ — the " +
                        "settlement-leg intent did not commit with the state change (ADR-0281)",
                    batchXmin,
                    netSettlementXmin,
                )
                .isEqualTo(batchXmin)
        }
    }

    /** Every `xmin` the query returns, so an EMPTY result is visible instead of being skipped. */
    private fun xmins(conn: Connection, sql: String): List<Long> = conn.createStatement().executeQuery(sql).use { rs ->
        buildList { while (rs.next()) add(rs.getLong(1)) }
    }
}
