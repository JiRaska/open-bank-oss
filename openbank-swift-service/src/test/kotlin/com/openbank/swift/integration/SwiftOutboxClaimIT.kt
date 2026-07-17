// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.swift.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.swift.infrastructure.persistence.repository.SwiftOutboxRepositoryImpl
import com.openbank.swift.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Regression coverage for #1201: two dispatcher instances racing [SwiftOutboxRepositoryImpl]'s
 * `claimProcessable` at the same instant (an Argo Rollouts canary window running old + new pod)
 * must never both claim the same row, and a claim that never reaches `markSent`/`markFailed`
 * (the claiming pod crashed or was evicted) must not strand the row forever.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class SwiftOutboxClaimIT {

    @Inject
    lateinit var repository: SwiftOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    // SwiftOutboxRepositoryImpl.persistInTransaction is `override suspend fun
    // persistInTransaction(message: SwiftOutboxMessage) { persist(...).awaitSuspending() }` -- a
    // suspend fun with no Uni return, relying on an ambient reactive session/transaction already
    // open on the current Vert.x duplicated context (unlike most siblings' Uni<Void>-returning
    // variant, which nests straight inside a Panache.withTransaction { } Java Supplier lambda).
    // Calling a suspend fun from inside that non-suspend Supplier lambda does not compile, and
    // calling it with no ambient transaction at all throws "No current Mutiny.Session found" (no
    // @WithSession/@WithTransaction is active outside a JAX-RS resource method) -- confirmed by
    // running this empirically. So: bridge the suspend call back into a Uni with `uni(scope) {}`
    // *inside* the withTransaction Supplier lambda, which both opens the transaction and type
    // -checks against the Java Supplier<Uni<T>> signature.
    private fun seedPending(count: Int): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = Ids.newId(),
                eventType = "test.event.claim",
                payload = """{"seq":$it}""",
                createdAt = Instant.now(),
            )
        }
        messages.forEach { msg ->
            onEventLoop {
                Panache.withTransaction {
                    uni(CoroutineScope(Dispatchers.Unconfined)) { repository.persistInTransaction(msg) }
                }.awaitSuspending()
            }
        }
        return messages
    }

    @Test
    fun `two concurrent claims never return the same row`() {
        clearOutbox()
        val seeded = seedPending(50)
        val seededIds = seeded.map { it.eventId }.toSet()

        val (first, second) = runBlocking {
            val a = async(Dispatchers.IO) { onEventLoop { repository.claimProcessable(30) } }
            val b = async(Dispatchers.IO) { onEventLoop { repository.claimProcessable(30) } }
            awaitAll(a, b)
        }

        val firstIds = first.map { it.eventId }.toSet()
        val secondIds = second.map { it.eventId }.toSet()

        assertThat(firstIds intersect secondIds)
            .describedAs("no row may be claimed by both concurrent calls")
            .isEmpty()
        assertThat(firstIds + secondIds)
            .describedAs("every claimed row came from this test's own seed")
            .isSubsetOf(seededIds)
        assertThat((first + second)).allSatisfy { assertThat(it.status).isEqualTo(OutboxStatus.DISPATCHING) }
    }

    @Test
    fun `a stale DISPATCHING row is reclaimed instead of stranded forever`() {
        clearOutbox()
        val seeded = seedPending(1).single()

        val firstClaim = onEventLoop { repository.claimProcessable(10, Duration.ofMinutes(5)) }
        assertThat(firstClaim.map { it.eventId }).containsExactly(seeded.eventId)

        onEventLoop {
            Panache.withTransaction {
                repository.update("claimedAt = ?1 where eventId = ?2", Instant.EPOCH, seeded.eventId)
            }.awaitSuspending()
        }

        val reclaim = onEventLoop { repository.claimProcessable(10, Duration.ofSeconds(1)) }
        assertThat(reclaim.map { it.eventId })
            .describedAs("stale DISPATCHING row must be reclaimable, not stranded")
            .containsExactly(seeded.eventId)
    }
}
