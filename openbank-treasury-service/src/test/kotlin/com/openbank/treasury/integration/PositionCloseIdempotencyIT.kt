// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.treasury.application.port.out.PositionClose
import com.openbank.treasury.application.port.out.ViewsRepository
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Real Postgres (#14310): a day-close Idempotency-Key is bound to the request. The same request
 * replays the stored close; the key reused for another date, scenario, endpoint or actor is refused
 * (IllegalStateException, 409) instead of answering with the OLD close; and two concurrent first
 * calls with one key both resolve to the same close rather than one surfacing the unique violation.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PositionCloseIdempotencyIT.OwnDatabase::class)
class PositionCloseIdempotencyIT {
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var views: ViewsRepository

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun close(
        date: LocalDate = LocalDate.of(2031, 3, 3),
        scenario: String? = null,
        special: Boolean = false,
        by: String = "olga.office",
    ) = PositionClose(UUID.randomUUID(), date, "BOOK1", scenario, special, by, Instant.now(), "{}")

    private fun key() = "close-" + UUID.randomUUID()

    @Test
    fun `the same request under the same key replays the stored close`() {
        val k = key()
        val first = onContext { views.recordClose(close(), k) }
        val replay = onContext { views.recordClose(close(), k) }
        assertThat(replay.snapshotId).isEqualTo(first.snapshotId)
    }

    @Test
    fun `a key reused for another date, scenario, endpoint or actor is a conflict`() {
        val k = key()
        onContext { views.recordClose(close(), k) }
        listOf(
            close(date = LocalDate.of(2031, 3, 4)),
            close(scenario = "STRESS"),
            close(special = true),
            close(by = "other.operator"),
        ).forEach { other ->
            assertThatThrownBy { onContext { views.recordClose(other, k) } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("another request")
        }
    }

    @Test
    fun `two concurrent first calls with one key both resolve to one close`() {
        val k = key()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit(
                    Callable {
                        barrier.await()
                        onContext { views.recordClose(close(), k) }
                    },
                )
            }.map { it.get() }
            assertThat(results.map { it.snapshotId }.toSet()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }
}
