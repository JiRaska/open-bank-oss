// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * Drives the REAL scheduler (a direct call would supply the Vert.x context the scheduler must
 * create itself): after a tick the five treasury instruments and the eight DPS/DIP strategy
 * offerings exist, each with exactly one DRAFT revision and none published (ADR-0341: seeding must
 * not make a revision pinnable without a human checker).
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_reference_seed")],
    restrictToAnnotatedClass = true,
)
@TestProfile(CatalogReferenceOfferingSeederIT.FastSeedProfile::class)
class CatalogReferenceOfferingSeederIT {
    @Inject
    lateinit var dataSource: DataSource

    class FastSeedProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.catalog.reference-offering-seed-interval" to "1s",
            "openbank.catalog.reference-offering-seed-initial-delay" to "0s",
        )
    }

    @Test
    fun `scheduled seeding creates treasury and pension offerings as drafts only`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        var rows = offeringStates()
        while (rows.size < EXPECTED_OFFERINGS && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            rows = offeringStates()
        }
        assertThat(rows.keys).contains(
            "TREASURY_MM_PLACEMENT",
            "TREASURY_MM_BORROWING",
            "TREASURY_CNB_DEPOSIT_FACILITY",
            "TREASURY_CNB_LOMBARD",
            "TREASURY_FX_SPOT",
            "CZ_DPS_CONSERVATIVE",
            "CZ_DIP_LIFECYCLE",
        )
        assertThat(rows.keys.filter { it.startsWith("TREASURY_") }).hasSize(TREASURY_COUNT)
        assertThat(rows.keys.filter { it.startsWith("CZ_D") }).hasSize(PENSION_COUNT)
        assertThat(rows.values).allMatch { it == listOf("DRAFT") }
    }

    @Test
    fun `a second pass seeds nothing and does not duplicate`() {
        Thread.sleep(SETTLE_MILLIS)
        val first = offeringStates()
        Thread.sleep(SETTLE_MILLIS)
        assertThat(offeringStates()).isEqualTo(first)
    }

    private fun offeringStates(): Map<String, List<String>> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT o.code, r.state FROM catalog_offerings o JOIN catalog_revisions r ON r.offering_id = o.id " +
                "WHERE o.code LIKE 'TREASURY\\_%' OR o.code LIKE 'CZ\\_D%' ORDER BY o.code, r.revision_no",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildMap<String, MutableList<String>> {
                    while (rows.next()) getOrPut(rows.getString(1)) { mutableListOf() }.add(rows.getString(2))
                }
            }
        }
    }

    private companion object {
        const val TREASURY_COUNT = 5
        const val PENSION_COUNT = 8
        const val EXPECTED_OFFERINGS = TREASURY_COUNT + PENSION_COUNT
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L
        const val SETTLE_MILLIS = 2_500L
    }
}
