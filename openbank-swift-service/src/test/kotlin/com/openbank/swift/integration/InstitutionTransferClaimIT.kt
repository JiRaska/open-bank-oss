// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.out.InstitutionTransferRejection
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.institution.InstitutionTransferFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The claim table on a REAL Postgres: a valid release (transfer path) and an invalid replay
 * (rejection path) of the same payment, racing in two independent transactions, must produce
 * exactly one claim and exactly one outcome — one row and one status event, never both and never
 * a REJECTED for a payment that became a transfer. Each path runs on its own duplicated Vert.x
 * context (its own reactive session and connection), released by one latch so they really overlap.
 */
@QuarkusTest
@QuarkusTestResource(InstitutionTransferClaimIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class InstitutionTransferClaimIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var vertx: Vertx

    @Inject
    lateinit var repo: InstitutionTransferRepository

    @Inject
    lateinit var dataSource: DataSource

    private fun <T> onOwnContext(start: CountDownLatch, block: suspend () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS)
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }

    @Test
    fun `a concurrent valid release and invalid replay of one payment yield exactly one claim and one outcome`() {
        repeat(ROUNDS) {
            val id = UUID.randomUUID()
            val m = InstitutionTransferFixtures.release(paymentId = id)
            val transfer = InstitutionTransferFixtures.transfer(m)
            val key = m["idempotencyKey"] as String
            val start = CountDownLatch(1)
            val transferWon = onOwnContext(start) {
                repo.insertClaimed(
                    transfer,
                    listOf(OutboxMessage(aggregateId = id, eventType = EVENT, payload = "{\"status\":\"ACCEPTED\"}")),
                )
            }
            val rejectionWon = onOwnContext(start) {
                repo.recordRejectionOnce(
                    InstitutionTransferRejection(
                        id,
                        key,
                        "DT01",
                        "valueDate is in the past",
                        false,
                        InstitutionTransferFixtures.NOW,
                    ),
                    OutboxMessage(aggregateId = id, eventType = EVENT, payload = "{\"status\":\"REJECTED\"}"),
                )
            }
            start.countDown()
            val outcomes =
                listOf(
                    transferWon.get(WAIT_SECONDS, TimeUnit.SECONDS),
                    rejectionWon.get(WAIT_SECONDS, TimeUnit.SECONDS),
                )

            assertThat(outcomes.count { it }).describedAs("exactly one path wins (round %s)", it).isEqualTo(1)
            assertThat(
                count("SELECT count(*) FROM swift_institution_transfer_claims WHERE payment_id = ?", id),
            ).isEqualTo(1)
            val rows = count("SELECT count(*) FROM swift_institution_transfers WHERE payment_id = ?", id) +
                count("SELECT count(*) FROM swift_institution_transfer_rejections WHERE payment_id = ?", id)
            assertThat(rows).describedAs("one outcome row").isEqualTo(1)
            assertThat(
                count("SELECT count(*) FROM swift_outbox WHERE aggregate_id = ?", id),
            ).describedAs("one event").isEqualTo(1)
            val outcome = string("SELECT outcome FROM swift_institution_transfer_claims WHERE payment_id = ?", id)
            assertThat(outcome).isEqualTo(if (outcomes[0]) "TRANSFER" else "REJECTION")
        }
    }

    @Test
    fun `the idempotency key is claimed too - a second payment id under the same key loses`() {
        val m = InstitutionTransferFixtures.release()
        val first = InstitutionTransferFixtures.transfer(m)
        val second = InstitutionTransferFixtures.transfer(m + ("paymentId" to UUID.randomUUID().toString()))
        val start = CountDownLatch(1)
        val a = onOwnContext(start) { repo.insertClaimed(first, emptyList()) }
        start.countDown()
        assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        val start2 = CountDownLatch(1)
        val b = onOwnContext(start2) { repo.insertClaimed(second, emptyList()) }
        start2.countDown()
        assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS)).isFalse()
        assertThat(
            count("SELECT count(*) FROM swift_institution_transfers WHERE payment_id = ?", second.paymentId),
        ).isZero()
    }

    private fun count(sql: String, id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            st.setObject(1, id)
            st.executeQuery().use { r ->
                r.next()
                r.getInt(1)
            }
        }
    }

    private fun string(sql: String, id: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            st.setObject(1, id)
            st.executeQuery().use { r ->
                r.next()
                r.getString(1)
            }
        }
    }

    private companion object {
        const val ROUNDS = 20
        const val WAIT_SECONDS = 30L
        const val EVENT = "swift.institution-transfer.status.v1"
    }
}
