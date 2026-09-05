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
 * Measured on unmodified origin/main before the fix: batch xmin = 750, outbox xmin = 752 —
 * three distinct transactions. After the fix (the settle chain wrapped in one ambient
 * `Panache.withTransaction`, which every `@WithTransaction` repo method and the publisher's own
 * `Panache.withTransaction` JOIN), both rows carry the same xmin.
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
     * The oracle (#8496): Postgres stamps each row version with `xmin`, the id of the writing
     * transaction — so "the batch row and its outbox row were written by the SAME transaction"
     * is read from the database, never inferred from both rows merely existing.
     *
     * ADR-0281 widens the assertion to the second outbox row: the `net_settlement.post` command
     * must share the same xmin — a SETTLED batch whose settlement-leg intent committed in a
     * different transaction could lose it on a crash between the two commits.
     */
    private fun assertSameWriterTransaction(batchId: String) {
        dataSource.connection.use { conn ->
            val batchXmin = conn.createStatement().executeQuery(
                "SELECT xmin FROM clearing_batches WHERE id = '$batchId'",
            ).apply { assertThat(next()).isTrue() }.getLong(1)
            val outboxXmin = conn.createStatement().executeQuery(
                "SELECT xmin FROM clearing_outbox WHERE aggregate_id = '$batchId' " +
                    "AND event_type = 'openbank.clearing.batch.settled'",
            ).apply { assertThat(next()).isTrue() }.getLong(1)
            assertThat(outboxXmin)
                .describedAs(
                    "batch row xmin (%d) and outbox row xmin (%d) differ — the state change and " +
                        "its event committed in DIFFERENT transactions (#8509)",
                    batchXmin,
                    outboxXmin,
                )
                .isEqualTo(batchXmin)
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
}
