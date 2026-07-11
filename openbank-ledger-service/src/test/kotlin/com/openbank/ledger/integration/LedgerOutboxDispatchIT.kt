// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.integration

import com.openbank.ledger.infrastructure.outbox.LedgerOutboxDispatcher
import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.outbox.OutboxDispatchConformanceIT
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import java.util.UUID

/**
 * Ledger's own instance of the shared outbox dispatch conformance suite (issue #467). Was
 * previously the ONLY service with this coverage, hand-written directly against Panache/Kafka;
 * migrated to [OutboxDispatchConformanceIT] so the same N1-N3 + idempotent-replay assertions are
 * available to every other outbox-bearing service in one line. See
 * [com.openbank.ledger.infrastructure.outbox.LedgerOutboxDispatchTest] for the pure-function
 * unit coverage this IT complements (real Panache/Kafka wiring vs. logic-only fakes).
 */
@QuarkusTest
@QuarkusTestResource(LedgerOutboxDispatchIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.ledger.it.PostgresTestResource::class)
class LedgerOutboxDispatchIT : OutboxDispatchConformanceIT() {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("ledger-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dispatcher: LedgerOutboxDispatcher

    @Inject
    lateinit var repository: LedgerOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    override lateinit var connector: InMemoryConnector

    override val channelName = "ledger-events-out"

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun triggerDispatch() {
        dispatcher.dispatch()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repository.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
