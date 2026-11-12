// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.integration

import com.openbank.fraud.infrastructure.outbox.FraudOutboxDispatcher
import com.openbank.fraud.infrastructure.persistence.FraudOutboxRepositoryImpl
import com.openbank.fraud.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.outbox.OutboxDispatchConformanceIT
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import java.util.UUID

/**
 * The shared outbox dispatch conformance suite (ADR-0050 N1-N3, ADR-0327 D9) against the real
 * [FraudOutboxDispatcher] → [FraudOutboxRepositoryImpl] → reactive Panache → in-memory Kafka wiring, now that the
 * repository is the kernel's `AbstractPanacheOutboxRepository`.
 */
@QuarkusTest
@TestProfile(FraudOutboxDispatchConformanceIT.DispatchEnabledProfile::class)
@QuarkusTestResource(
    value = FraudOutboxDispatchConformanceIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(PostgresRedisTestResource::class)
class FraudOutboxDispatchConformanceIT : OutboxDispatchConformanceIT() {

    // The test config leaves the dispatcher off so other ITs own their rows; this suite drives
    // the real bean, whose dispatch() is a no-op unless the switch is on.
    class DispatchEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "true",
            // %test disables the outgoing channel; this suite needs it, in memory.
            "mp.messaging.outgoing.fraud-outbox-out.enabled" to "true",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("fraud-outbox-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dispatcher: FraudOutboxDispatcher

    @Inject
    lateinit var repository: FraudOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    override lateinit var connector: InMemoryConnector

    override val channelName = "fraud-outbox-out"

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun triggerDispatch() {
        dispatcher.dispatchForTest()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repository.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
