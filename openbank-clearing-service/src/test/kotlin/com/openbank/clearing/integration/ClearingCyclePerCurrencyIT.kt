// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * #11974: a clearing batch is per (rail, currency). A cycle that finds EUR and CZK items pending
 * must produce one batch per currency, each summing only its own currency, and each batch's
 * `net_settlement.post` command must carry that currency and that currency's total — never euros
 * added to koruny under whichever currency happened to be first.
 *
 * The oracle is the database, not the trigger response, so it reads the same on the pre-fix code
 * (one object) and the fixed code (a cycle result): for every batch holding one of this test's
 * items, all its items share the batch currency and `total_debit` equals their sum. Measured on
 * origin/main before the fix: both tests RED — the EUR and the CZK item landed in the SAME batch
 * id, and the PLN item (no settlement GL) was swept to IN_CLEARING instead of staying PENDING.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_it")],
)
@TestProfile(OutboxRepositoryIsolationProfile::class)
class ClearingCyclePerCurrencyIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var meterRegistry: MeterRegistry

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `a cycle with EUR and CZK items nets and settles each currency in its own batch`() {
        val eur = submit("100.00", "EUR")
        val czk = submit("1000.00", "CZK")

        trigger()

        val eurBatch = batchOf(eur)
        val czkBatch = batchOf(czk)
        assertThat(eurBatch)
            .describedAs("EUR and CZK items were netted into ONE batch — cross-currency arithmetic (#11974)")
            .isNotEqualTo(czkBatch)

        listOf(eurBatch to ("EUR" to "100.00"), czkBatch to ("CZK" to "1000.00")).forEach { (batch, expected) ->
            val (ccy, total) = expected
            assertBatchIsSingleCurrency(batch)
            val (batchCcy, totalDebit) = batchRow(batch)
            assertThat(batchCcy).isEqualTo(ccy)
            assertThat(totalDebit).isEqualByComparingTo(BigDecimal(total))
        }

        listOf(eurBatch, czkBatch).forEach { settle(it) }

        val posts = listOf(eurBatch, czkBatch).associateWith { netSettlementPost(it) }
        assertThat(posts[eurBatch]!!.path("currency").asText()).isEqualTo("EUR")
        assertThat(posts[eurBatch]!!.path("settlementAmount").decimalValue()).isEqualByComparingTo("100.00")
        assertThat(posts[czkBatch]!!.path("currency").asText()).isEqualTo("CZK")
        assertThat(posts[czkBatch]!!.path("settlementAmount").decimalValue()).isEqualByComparingTo("1000.00")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `an item in a currency with no settlement GL stays PENDING, unbatched, and is counted`() {
        val pln = submit("50.00", "PLN")

        trigger()

        dataSource.connection.use { conn ->
            conn.createStatement().executeQuery(
                "SELECT status::text, batch_id::text FROM clearing_items WHERE payment_id = '$pln'",
            ).use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString(1))
                    .describedAs("a PLN item has no settlement account; it must not enter a batch")
                    .isEqualTo("PENDING")
                assertThat(rs.getString(2)).isEqualTo(UNASSIGNED)
            }
        }
        val gauge = meterRegistry.find(UNSETTLEABLE_GAUGE).tag("currency", "PLN").gauge()
        assertThat(gauge).describedAs("no %s{currency=PLN} gauge registered", UNSETTLEABLE_GAUGE).isNotNull
        assertThat(gauge!!.value()).isGreaterThanOrEqualTo(1.0)
    }

    private fun submit(amount: String, currency: String): UUID {
        val paymentId = UUID.randomUUID()
        Given {
            contentType("application/json")
            body(
                """
                {
                  "paymentId": "$paymentId",
                  "paymentReference": "PAY-CCY-${paymentId.toString().take(8)}",
                  "debtorIban": "CZ6508000000192000145399",
                  "creditorIban": "DE89370400440532013000",
                  "amount": "$amount",
                  "currency": "$currency",
                  "rail": "SEPA_SCT"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
        }
        return paymentId
    }

    private fun trigger() {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
        } Then {
            statusCode(200)
        }
    }

    private fun settle(batchId: String) {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/batches/$batchId/settle")
        } Then {
            statusCode(200)
        }
    }

    private fun batchOf(paymentId: UUID): String = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT batch_id::text FROM clearing_items WHERE payment_id = '$paymentId'",
        ).use { rs ->
            assertThat(rs.next()).isTrue()
            rs.getString(1).also { assertThat(it).isNotEqualTo(UNASSIGNED) }
        }
    }

    private fun batchRow(batchId: String): Pair<String, BigDecimal> = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT currency, total_debit FROM clearing_batches WHERE id = '$batchId'",
        ).use { rs ->
            assertThat(rs.next()).isTrue()
            rs.getString(1) to rs.getBigDecimal(2)
        }
    }

    /** Every item in the batch carries the batch's currency, and the total is their sum. */
    private fun assertBatchIsSingleCurrency(batchId: String) = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT b.currency, b.total_debit, " +
                "array_to_string(array_agg(DISTINCT i.currency), ','), sum(i.amount) " +
                "FROM clearing_batches b JOIN clearing_items i ON i.batch_id = b.id " +
                "WHERE b.id = '$batchId' GROUP BY b.currency, b.total_debit",
        ).use { rs ->
            assertThat(rs.next()).describedAs("batch %s has no items", batchId).isTrue()
            assertThat(rs.getString(3))
                .describedAs("batch %s (%s) holds items of currencies %s", batchId, rs.getString(1), rs.getString(3))
                .isEqualTo(rs.getString(1))
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo(rs.getBigDecimal(4))
        }
    }

    private fun netSettlementPost(batchId: String) = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT payload FROM clearing_outbox WHERE aggregate_id = '$batchId' " +
                "AND event_type = 'openbank.clearing.net_settlement.post'",
        ).use { rs ->
            assertThat(rs.next()).describedAs("no net_settlement.post for batch %s", batchId).isTrue()
            mapper.readTree(rs.getString(1))
        }
    }

    private companion object {
        const val UNASSIGNED = "00000000-0000-0000-0000-000000000000"
        const val UNSETTLEABLE_GAUGE = "openbank.clearing.unsettleable.pending.items"
    }
}
