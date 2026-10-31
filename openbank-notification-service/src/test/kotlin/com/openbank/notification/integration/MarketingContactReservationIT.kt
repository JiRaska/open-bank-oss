// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.openbank.notification.infrastructure.contact.MarketingContactReservationStore
import com.openbank.notification.infrastructure.contact.MarketingReservationDecision
import com.openbank.notification.it.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Concurrent real-DB proof of the cross-pod per-party reservation boundary. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(MarketingContactReservationIT.InMemoryKafkaResource::class)
class MarketingContactReservationIT {

    private val syntheticRowId = AtomicLong(-1)

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory(
            "notification-events-in",
            "party-events-in",
            "delegation-events-in",
            "kyc-events-in",
            "consent-events-in",
            "approval-events-in",
        ) + InMemoryConnector.switchOutgoingChannelsToInMemory("notification-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var store: MarketingContactReservationStore

    @Inject lateinit var dataSource: AgroalDataSource

    private fun pending(partyId: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO notifications
                  (id, notification_id, party_id, channel, template, recipient, body, status)
                VALUES (?, ?, ?, 'EMAIL', 'MARKETING_PRODUCT_OFFER', 'synthetic@example.test', 'test', 'PENDING')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, syntheticRowId.getAndDecrement())
                statement.setObject(2, id)
                statement.setObject(3, partyId)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun reserve(id: UUID, partyId: UUID): MarketingReservationDecision =
        store.reserve(id, partyId).await().atMost(Duration.ofSeconds(15))

    @Test
    fun `three concurrent intents never reserve more than the two-contact window`() {
        val partyId = UUID.randomUUID()
        val ids = List(3) { pending(partyId) }
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        val decisions = try {
            val futures = ids.map { id ->
                workers.submit<MarketingReservationDecision> {
                    start.await()
                    reserve(id, partyId)
                }
            }
            start.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }

        assertThat(decisions.count { it == MarketingReservationDecision.RESERVED }).isEqualTo(2)
        assertThat(decisions.count { it == MarketingReservationDecision.CAP_REACHED }).isEqualTo(1)
        val reservedId = ids[decisions.indexOf(MarketingReservationDecision.RESERVED)]
        assertThat(reserve(reservedId, partyId)).isEqualTo(MarketingReservationDecision.ALREADY_RESERVED)

        // A known pre-handoff rejection frees its slot; an ambiguous PENDING reservation does not.
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE notifications SET status = 'FAILED' WHERE notification_id = ?")
                .use { statement ->
                    statement.setObject(1, reservedId)
                    statement.executeUpdate()
                }
        }
        val nextId = pending(partyId)
        assertThat(reserve(nextId, partyId)).isEqualTo(MarketingReservationDecision.RESERVED)
        assertThat(reserve(pending(partyId), partyId)).isEqualTo(MarketingReservationDecision.CAP_REACHED)
    }
}
