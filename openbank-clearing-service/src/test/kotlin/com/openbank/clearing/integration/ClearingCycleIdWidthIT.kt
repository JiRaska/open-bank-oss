// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.integration

import com.openbank.clearing.application.usecase.ClearingService
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import javax.sql.DataSource

/**
 * #12005: the longest cycle id the service can build, for EVERY rail, persists in every column
 * that stores it — `clearing_batches.cycle_id`, the batch reference `<cycleId>-<CCY>`, and
 * `settlement_positions.cycle_id` — against the real schema. Before V12 `cycle_id` was
 * VARCHAR(32) and SEPA_SCT_INST's 33-character id failed with `value too long` (22001);
 * measured RED on the pre-V12 schema.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_it")],
)
@TestProfile(OutboxRepositoryIsolationProfile::class)
class ClearingCycleIdWidthIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `the longest cycle id of every rail persists in batches and positions`() {
        // Widest date and the widest suffix (millis % 10000 = 9999). A unique year per run keeps
        // the UNIQUE batch reference / position key free across re-runs on a reused database.
        val year = 2000 + (System.nanoTime() % 7000).toInt()
        PaymentRail.entries.forEach { rail ->
            val cycleId = ClearingService.cycleIdFor(rail, LocalDate.of(year, 12, 31), 9_999L)
            insertBatch(rail, cycleId)
            insertPosition(cycleId)
            assertThat(storedCycleId("$cycleId-EUR")).describedAs("%s cycle id was truncated", rail).isEqualTo(cycleId)
        }
    }

    private fun insertBatch(rail: PaymentRail, cycleId: String) = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO clearing_batches (batch_reference, rail, cycle_id) VALUES (?, ?::payment_rail, ?)",
        ).use {
            it.setString(1, "$cycleId-EUR")
            it.setString(2, rail.name)
            it.setString(3, cycleId)
            assertThat(it.executeUpdate()).isEqualTo(1)
        }
    }

    private fun insertPosition(cycleId: String) = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO settlement_positions (participant_bic, currency, cycle_id) VALUES ('OPENCZPP', 'EUR', ?)",
        ).use {
            it.setString(1, cycleId)
            assertThat(it.executeUpdate()).isEqualTo(1)
        }
    }

    private fun storedCycleId(batchReference: String): String = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT cycle_id FROM clearing_batches WHERE batch_reference = ?").use {
            it.setString(1, batchReference)
            it.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                rs.getString(1)
            }
        }
    }
}
