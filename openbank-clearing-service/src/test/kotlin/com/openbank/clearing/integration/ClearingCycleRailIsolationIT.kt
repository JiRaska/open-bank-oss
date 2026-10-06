// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
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
import java.util.UUID
import javax.sql.DataSource

/**
 * #12004: a clearing cycle for one rail clears only that rail's items. Before the fix items did
 * not store their rail and `findPendingByRail` ignored it, so a SWIFT cycle swept a SEPA_SCT item
 * into a SWIFT batch. The oracle is the database row of each item after the cycle.
 *
 * Measured on origin/main before the fix: all three tests RED — the SEPA_SCT item was
 * IN_CLEARING in the SWIFT batch, a submit without `rail` answered 201 (defaulted to SEPA_SCT),
 * and the rail-less row could not even be written (no `rail` column).
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_it")],
)
@TestProfile(OutboxRepositoryIsolationProfile::class)
class ClearingCycleRailIsolationIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `a cycle for one rail leaves another rail's items PENDING and unbatched`() {
        val sct = submit("SEPA_SCT")
        val swift = submit("SWIFT")

        trigger("SWIFT")

        val (sctStatus, sctBatch) = itemRow(sct)
        assertThat(sctStatus)
            .describedAs("a SWIFT cycle swept a SEPA_SCT item (#12004)")
            .isEqualTo("PENDING")
        assertThat(sctBatch).isEqualTo(UNASSIGNED)

        val (swiftStatus, swiftBatch) = itemRow(swift)
        assertThat(swiftStatus).isEqualTo("IN_CLEARING")
        assertThat(batchRail(swiftBatch)).isEqualTo("SWIFT")
        assertThat(storedRail(swift)).isEqualTo("SWIFT")
        assertThat(storedRail(sct)).isEqualTo("SEPA_SCT")

        trigger("SEPA_SCT")

        val (sctAfter, sctBatchAfter) = itemRow(sct)
        assertThat(sctAfter).isEqualTo("IN_CLEARING")
        assertThat(batchRail(sctBatchAfter)).isEqualTo("SEPA_SCT")
        assertThat(itemRow(swift).second).describedAs("the SWIFT item moved batch").isEqualTo(swiftBatch)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `a submit without a rail is a 400 and leaves no item`() {
        val paymentId = UUID.randomUUID()
        Given {
            contentType("application/json")
            body(body(paymentId, rail = null))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(400)
        }
        dataSource.connection.use { conn ->
            conn.createStatement().executeQuery(
                "SELECT count(*) FROM clearing_items WHERE payment_id = '$paymentId'",
            ).use { rs ->
                rs.next()
                assertThat(rs.getLong(1)).isZero()
            }
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
    fun `an item with no recorded rail is selected by no cycle`() {
        val paymentId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.createStatement().executeUpdate(
                "INSERT INTO clearing_items (batch_id, payment_id, payment_reference, debtor_iban, " +
                    "creditor_iban, amount, currency, status, created_at, updated_at) VALUES " +
                    "('$UNASSIGNED', '$paymentId', 'PAY-LEGACY', 'CZ6508000000192000145399', " +
                    "'DE89370400440532013000', 10.00, 'EUR', 'PENDING', now() - interval '1 day', now())",
            )
        }

        listOf("SEPA_SCT", "SEPA_SCT_INST", "SWIFT", "DOMESTIC", "INTERNAL").forEach(::trigger)

        val (status, batch) = itemRow(paymentId)
        assertThat(status).describedAs("a rail-less item was cleared on a guessed rail").isEqualTo("PENDING")
        assertThat(batch).isEqualTo(UNASSIGNED)
    }

    private fun body(paymentId: UUID, rail: String?): String {
        val railMember = rail?.let { ""","rail": "$it"""" } ?: ""
        return """
            {
              "paymentId": "$paymentId",
              "paymentReference": "PAY-RAIL-${paymentId.toString().take(8)}",
              "debtorIban": "CZ6508000000192000145399",
              "creditorIban": "DE89370400440532013000",
              "amount": "25.00",
              "currency": "EUR"$railMember
            }
        """.trimIndent()
    }

    private fun submit(rail: String): UUID {
        val paymentId = UUID.randomUUID()
        Given {
            contentType("application/json")
            body(body(paymentId, rail))
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(201)
        }
        return paymentId
    }

    private fun trigger(rail: String) {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/cycle/trigger?rail=$rail")
        } Then {
            statusCode(200)
        }
    }

    private fun itemRow(paymentId: UUID): Pair<String, String> = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT status::text, batch_id::text FROM clearing_items WHERE payment_id = '$paymentId'",
        ).use { rs ->
            assertThat(rs.next()).isTrue()
            rs.getString(1) to rs.getString(2)
        }
    }

    private fun storedRail(paymentId: UUID): String? = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT rail::text FROM clearing_items WHERE payment_id = '$paymentId'",
        ).use { rs ->
            assertThat(rs.next()).isTrue()
            rs.getString(1)
        }
    }

    private fun batchRail(batchId: String): String = dataSource.connection.use { conn ->
        conn.createStatement().executeQuery("SELECT rail::text FROM clearing_batches WHERE id = '$batchId'").use { rs ->
            assertThat(rs.next()).isTrue()
            rs.getString(1)
        }
    }

    private companion object {
        const val UNASSIGNED = "00000000-0000-0000-0000-000000000000"
    }
}
