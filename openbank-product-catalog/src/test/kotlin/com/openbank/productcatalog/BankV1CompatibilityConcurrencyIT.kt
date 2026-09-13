// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.persistence.BankV1CompatibilityBackfill
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Two reconcilers mapping the same legacy products at once must not collide (issue #4896).
 *
 * The production shape is a rolling deploy or a KEDA 0 -> N scale-up: every replica runs the
 * [BankV1CompatibilityBackfill] startup pass, and each pod also runs the scheduled reconciliation.
 * `ensureMapped` reads `bank_v1_product_mapping` and inserts when it is absent, so without a row
 * lock on `products` both reconcilers read "unmapped" under READ COMMITTED and both insert
 * `catalog_specifications` keyed by the canonical product id — the loser dies with
 * `duplicate key value violates unique constraint "catalog_specifications_pkey" (23505)`.
 *
 * Its own profile, so this gets its own application and its own database: the truncate below strips
 * the whole v2 projection, which would destroy every other test class's fixtures if it shared one.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_concurrency")],
)
@TestProfile(BankV1CompatibilityConcurrencyIT.ConcurrentReconciliationProfile::class)
@TestSecurity(user = "concurrency-operator", roles = ["ROLE_OPERATOR"])
class BankV1CompatibilityConcurrencyIT {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var backfill: BankV1CompatibilityBackfill

    /** Keeps the scheduled reconciliation out of the way — this test drives the concurrency itself. */
    class ConcurrentReconciliationProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.catalog.bank-v1-reconcile-initial-delay" to "24h",
        )
    }

    @Test
    fun `concurrent reconcilers map every legacy product exactly once`() {
        stripCompatibilityProjection()
        val products = scalar("SELECT COUNT(*) FROM products")
        assertThat(products)
            .describedAs("the seeded catalogue must give both reconcilers real work to collide over")
            .isGreaterThan(0)

        val pool = Executors.newFixedThreadPool(RECONCILERS)
        try {
            val barrier = CyclicBarrier(RECONCILERS)
            val runs: List<Future<Int>> = (1..RECONCILERS).map {
                pool.submit<Int> {
                    barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    backfill.run()
                }
            }
            runs.forEach { run ->
                assertThatCode { run.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    .describedAs("a reconciler that loses the race must wait for the winner, not fail")
                    .doesNotThrowAnyException()
            }
        } finally {
            pool.shutdownNow()
        }

        assertThat(scalar("SELECT COUNT(*) FROM bank_v1_product_mapping")).isEqualTo(products)
        assertThat(scalar("SELECT COUNT(*) FROM catalog_specifications")).isEqualTo(products)
    }

    /** Drops the whole v2 projection so the next reconciliation has to rebuild it from `products`. */
    private fun stripCompatibilityProjection() = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("TRUNCATE TABLE bank_v1_product_mapping, catalog_specifications CASCADE")
        }
    }

    private fun scalar(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private companion object {
        const val RECONCILERS = 2
        const val BARRIER_TIMEOUT_SECONDS = 30L
        const val RUN_TIMEOUT_SECONDS = 120L
    }
}
