// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.integration

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.outbox.OutboxDispatchConformanceIT
import com.openbank.sdd.infrastructure.outbox.SddOutboxDispatcher
import com.openbank.sdd.infrastructure.persistence.repository.SddOutboxRepositoryImpl
import com.openbank.sdd.it.PostgresTestResource
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
 * [SddOutboxDispatcher] → [SddOutboxRepositoryImpl] → reactive Panache → in-memory Kafka wiring,
 * now that the repository is the kernel's `AbstractPanacheOutboxRepository`.
 *
 * The in-memory Kafka resource is `restrictToAnnotatedClass` on purpose (#8676: a resource meant
 * for one class leaked into the others and broke this module's dispatch IT).
 */
@QuarkusTest
@TestProfile(SddOutboxDispatchConformanceIT.DispatchEnabledProfile::class)
@QuarkusTestResource(
    value = SddOutboxDispatchConformanceIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(PostgresTestResource::class)
class SddOutboxDispatchConformanceIT : OutboxDispatchConformanceIT() {

    // %test already sets dispatch-enabled=true (with the @Scheduled tick off); pinned here so the
    // suite does not silently become a no-op if that default changes.
    class DispatchEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "true")
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dispatcher: SddOutboxDispatcher

    @Inject
    lateinit var repository: SddOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    override lateinit var connector: InMemoryConnector

    override val channelName = "sdd-events-out"

    // sdd's transaction-joining write is append(message): Uni<Void>, not persistInTransaction.
    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repository.append(message) }.awaitSuspending()
    }

    override suspend fun triggerDispatch() {
        dispatcher.dispatch()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repository.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
