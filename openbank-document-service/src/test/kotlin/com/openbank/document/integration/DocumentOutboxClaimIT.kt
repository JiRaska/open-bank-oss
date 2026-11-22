// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.document.integration

import com.openbank.document.infrastructure.persistence.repository.DocumentOutboxRepositoryImpl
import com.openbank.document.it.PostgresRedisTestResource
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

/**
 * Regression coverage for #1201: two dispatcher instances racing [DocumentOutboxRepositoryImpl]'s
 * `claimProcessable` at the same instant (an Argo Rollouts canary window running old + new pod)
 * must never both claim the same row, and a claim that never reaches `markSent`/`markFailed`
 * (the claiming pod crashed or was evicted) must not strand the row forever.
 */
@QuarkusTest
@TestProfile(DocumentOutboxClaimIT.NoDispatchProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DocumentOutboxClaimIT {

    // This test owns the first claim. The production dispatcher runs on its own schedule and
    // would otherwise race it for the seeded row, making the first-claim assertion flaky.
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var repository: DocumentOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    // The @QuarkusTest Postgres is shared across test classes in one JVM session — without this,
    // backlog rows other IT classes left behind get scooped up by this test's claim too.
    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

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
            onEventLoop { Panache.withTransaction { repository.persistInTransaction(msg) }.awaitSuspending() }
        }
        return messages
    }

    @ParameterizedTest(name = "mapped trace extension: {0}")
    @ValueSource(strings = ["peek", "claim"])
    fun `mapped trace extension survives reads with an extra unmapped table column`(operation: String) {
        clearOutbox()
        changeTestSchema("ADD COLUMN future_claim_token UUID")
        try {
            val message = OutboxMessage(
                aggregateId = Ids.newId(),
                eventType = "test.event.projection",
                payload = """{"projection":"traced"}""",
                traceparent = "00-11111111111111111111111111111111-2222222222222222-01",
                synthetic = true,
            )
            onEventLoop { Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending() }
            val row = onEventLoop {
                if (operation == "claim") repository.claimProcessable(1) else repository.listProcessable(1)
            }.single()
            assertThat(row.eventId).isEqualTo(message.eventId)
            assertThat(row.payload).isEqualTo(message.payload)
            assertThat(row.synthetic).isTrue()
            // PanacheOutboxEntityTraced.toEntry is an override; scalar/base-only hydration loses this.
            assertThat(row.traceparent).isEqualTo(message.traceparent)
        } finally {
            try {
                clearOutbox()
            } finally {
                changeTestSchema("DROP COLUMN future_claim_token")
            }
        }
    }

    private fun changeTestSchema(clauses: String) {
        onEventLoop {
            Panache.withTransaction {
                Panache.getSession().chain { session ->
                    session.createNativeQuery<Int>("ALTER TABLE document_outbox $clauses").executeUpdate()
                }
            }.awaitSuspending()
        }
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

        // Simulate the claiming pod crashing before markSent/markFailed: back-date claimed_at
        // far enough that any staleAfter window has already elapsed.
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
