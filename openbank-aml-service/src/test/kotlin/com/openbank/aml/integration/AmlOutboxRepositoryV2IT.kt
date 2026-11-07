// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.integration

import com.openbank.aml.infrastructure.persistence.repository.AmlOutboxRepositoryImpl
import com.openbank.aml.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxBackoff
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepositoryV2
import com.openbank.libs.testing.outbox.OutboxRepositoryV2ConformanceIT
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-0327 repository-semantics conformance (per-aggregate order under two concurrent
 * dispatchers, failed-head parking, backoff, stale reclaim, purge safety) against
 * [AmlOutboxRepositoryImpl] on the real Postgres outbox table and its outbox-v2 migration.
 */
@QuarkusTest
@TestProfile(AmlOutboxRepositoryV2IT.NoDispatchProfile::class)
@QuarkusTestResource(
    value = AmlOutboxDispatchConformanceIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlOutboxRepositoryV2IT : OutboxRepositoryV2ConformanceIT() {

    // The kit drives OutboxDispatch itself; the scheduled dispatcher would race it for seeded rows.
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(ClaimClock::class.java)
    }

    /** Enabled only by this repository-conformance profile; ordinary cases use real time. */
    @Alternative
    @Singleton
    class ClaimClock : Clock() {
        private val delegate = AtomicReference<Clock>(systemUTC())

        override fun instant(): Instant = delegate.get().instant()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = delegate.get().withZone(zone)

        fun freezeAt(instant: Instant) {
            delegate.set(fixed(instant, ZoneOffset.UTC))
        }

        fun reset() {
            delegate.set(systemUTC())
        }
    }

    @Inject
    lateinit var claimClock: ClaimClock

    override val setClaimTime: (Instant) -> Unit get() = claimClock::freezeAt

    @AfterEach
    fun restoreClaimClock() = claimClock.reset()

    @Test
    fun `claim eligibility stays before its deadline despite a delayed test thread`() {
        val failedAt = Instant.now()
        val message = OutboxMessage(
            aggregateId = UUID.randomUUID(),
            eventType = "v2.clock",
            payload = "{}",
            createdAt = failedAt.minusSeconds(60),
        )
        onEventLoop {
            seed(message)
            repo.markFailed(message.eventId, "retry", failedAt)
        }
        val nextAttemptAt = requireNotNull(onEventLoop { findEntry(message.eventId) }?.nextAttemptAt)
        val before = nextAttemptAt.minusNanos(1_000)
        claimClock.freezeAt(before)
        Thread.sleep(OutboxBackoff.delayBounds(1).endInclusive.plusMillis(1).toMillis())
        assertThat(Instant.now()).isAfter(nextAttemptAt)
        assertThat(claimClock.instant()).isEqualTo(before)
        assertThat(onEventLoop { repo.claimProcessable(25) }.filter { it.eventId == message.eventId }).isEmpty()

        claimClock.freezeAt(nextAttemptAt.plusNanos(1_000))
        assertThat(onEventLoop { repo.claimProcessable(25) }.filter { it.eventId == message.eventId }).hasSize(1)
    }

    @Inject
    lateinit var repo: AmlOutboxRepositoryImpl

    override val repository: OutboxRepositoryV2 get() = repo

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repo.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repo.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
