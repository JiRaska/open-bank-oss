// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.integration

import com.openbank.domestic.infrastructure.persistence.repository.DomesticPaymentOutboxRepositoryImpl
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** PostgreSQL coverage for concurrent claims, aggregate ordering and stale acknowledgements. */
@QuarkusTest
@TestProfile(DomesticPaymentOutboxClaimIT.NoDispatchProfile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class DomesticPaymentOutboxClaimIT {

    // This test owns the first claim. The production dispatcher runs on its own schedule and
    // would otherwise race it for the seeded row, making the first-claim assertion flaky.
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var repository: DomesticPaymentOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun clearOutbox() {
        onEventLoop { Panache.withTransaction { repository.deleteAll() }.awaitSuspending() }
    }

    // DomesticPaymentOutboxRepositoryImpl has no generic persistInTransaction override -- its
    // seed hook is the bespoke, non-interface persistWithinCurrentTransaction(OutboxMessage),
    // which returns the persisted entity (Uni<DomesticPaymentOutboxEntity>), not Uni<Void>.
    private fun seedPending(count: Int, aggregateId: UUID? = null): List<OutboxMessage> {
        val messages = (1..count).map {
            OutboxMessage(
                aggregateId = aggregateId ?: Ids.newId(),
                eventType = "test.event.claim",
                payload = """{"seq":$it}""",
                createdAt = Instant.now(),
            )
        }
        messages.forEach { msg ->
            onEventLoop {
                Panache.withTransaction { repository.persistWithinCurrentTransaction(msg) }.awaitSuspending()
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
            val a = async(Dispatchers.IO) { onEventLoop { repository.claimWithTokens(30) } }
            val b = async(Dispatchers.IO) { onEventLoop { repository.claimWithTokens(30) } }
            awaitAll(a, b)
        }

        val firstIds = first.map { it.entry.eventId }.toSet()
        val secondIds = second.map { it.entry.eventId }.toSet()

        assertThat(firstIds intersect secondIds)
            .describedAs("no row may be claimed by both concurrent calls")
            .isEmpty()
        assertThat(firstIds + secondIds)
            .describedAs("every claimed row came from this test's own seed")
            .isSubsetOf(seededIds)
        assertThat((first + second)).allSatisfy { assertThat(it.entry.status).isEqualTo(OutboxStatus.DISPATCHING) }
    }

    @Test
    fun `a stale DISPATCHING row is reclaimed instead of stranded forever`() {
        clearOutbox()
        val seeded = seedPending(1).single()

        val first = onEventLoop { repository.claimWithTokens(10, Duration.ofMinutes(5)) }.single()
        assertThat(first.entry.eventId).isEqualTo(seeded.eventId)

        // Simulate the claiming pod crashing before acknowledgement: back-date claimed_at
        // far enough that any staleAfter window has already elapsed.
        onEventLoop {
            Panache.withTransaction {
                repository.update("claimedAt = ?1 where eventId = ?2", Instant.EPOCH, seeded.eventId)
            }.awaitSuspending()
        }

        val replacement = onEventLoop { repository.claimWithTokens(10, Duration.ofSeconds(1)) }.single()
        assertThat(replacement.entry.eventId).isEqualTo(seeded.eventId)
        assertThat(replacement.token).isNotEqualTo(first.token)
        assertThat(onEventLoop { repository.markClaimsSent(listOf(first)) }).isEmpty()
        assertThat(onEventLoop { repository.markClaimFailed(first, "late failure") }).isNull()
        assertThat(onEventLoop { repository.markClaimsSent(listOf(replacement)) }).containsExactly(seeded.eventId)
        assertThat(onEventLoop { repository.markClaimFailed(first, "late failure after send") }).isNull()
    }

    @Test
    fun `an aggregate tail is claimable only after its head is acknowledged`() {
        clearOutbox()
        val (head, tail) = seedPending(2, Ids.newId())
        val claim = onEventLoop { repository.claimWithTokens(2) }.single()
        assertThat(claim.entry.eventId).isEqualTo(head.eventId)
        assertThat(onEventLoop { repository.claimWithTokens(2) }).isEmpty()

        assertThat(onEventLoop { repository.markClaimsSent(listOf(claim)) }).containsExactly(head.eventId)
        assertThat(onEventLoop { repository.claimWithTokens(2) }.map { it.entry.eventId }).containsExactly(tail.eventId)
    }

    @Test
    fun `a failed head backs off and its tail becomes eligible after the head is dead`() {
        clearOutbox()
        val (head, tail) = seedPending(2, Ids.newId())
        val claim = onEventLoop { repository.claimWithTokens(2) }.single()
        assertThat(claim.entry.eventId).isEqualTo(head.eventId)
        val failedAt = Instant.now().plusSeconds(30)

        assertThat(onEventLoop { repository.markClaimFailed(claim, "synthetic failure", failedAt) })
            .isEqualTo(OutboxStatus.FAILED)
        val delayed = onEventLoop {
            Panache.withSession { repository.find("eventId", head.eventId).singleResult() }.awaitSuspending()
        }
        assertThat(delayed.attemptCount).isOne()
        assertThat(delayed.nextAttemptAt).isAfter(failedAt)
        assertThat(delayed.claimToken).isNull()
        assertThat(onEventLoop { repository.claimWithTokens(2) }).isEmpty()

        onEventLoop {
            Panache.withTransaction {
                repository.update(
                    "attemptCount = ?1, nextAttemptAt = ?2 where eventId = ?3",
                    OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1,
                    Instant.EPOCH,
                    head.eventId,
                )
            }.awaitSuspending()
        }
        val retry = onEventLoop { repository.claimWithTokens(2) }.single()
        assertThat(retry.entry.eventId).isEqualTo(head.eventId)
        assertThat(onEventLoop { repository.markClaimFailed(retry, "synthetic failure") }).isEqualTo(OutboxStatus.DEAD)
        assertThat(onEventLoop { repository.claimWithTokens(2) }.map { it.entry.eventId }).containsExactly(tail.eventId)
    }
}
