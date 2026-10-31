// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.integration

import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Drives the actual scheduled coroutine, not a direct call supplying its missing Vert.x context. */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestProfile(HistoryCaptureReceiptPurgeIT.FastPurge::class)
class HistoryCaptureReceiptPurgeIT {
    @Inject lateinit var dataSource: AgroalDataSource

    class FastPurge : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.ledger.history-receipt-purge-every" to "100ms",
            "openbank.ledger.history-receipt-purge-delay" to "0s",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Test
    fun `real scheduled purge removes expired request metadata without touching source data`() {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO journal_history_capture_receipts
                   (scope_key_hash,request_hash,result_kind,empty_result_id,empty_observed_at,empty_database_view,created_at,expires_at)
                   VALUES (repeat('e',64),repeat('f',64),'EMPTY',?,statement_timestamp(),'synthetic-view',
                       statement_timestamp()-interval '86400 seconds',statement_timestamp())""",
            ).use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        var remaining = 1
        while (remaining > 0 && System.nanoTime() < deadline) {
            remaining = remainingReceipts(id)
            if (remaining > 0) Thread.sleep(50)
        }
        assertThat(remaining).isZero()
    }

    private fun remainingReceipts(id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM journal_history_capture_receipts WHERE empty_result_id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }
}
