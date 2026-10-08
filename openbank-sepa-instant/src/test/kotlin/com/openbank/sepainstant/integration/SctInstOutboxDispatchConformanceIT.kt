// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.integration

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.outbox.OutboxDispatchConformanceIT
import com.openbank.sepainstant.infrastructure.outbox.SctInstOutboxDispatcher
import com.openbank.sepainstant.infrastructure.persistence.repository.SctInstOutboxRepositoryImpl
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import java.util.UUID

/** Real Panache-to-Kafka dispatch proof for the SCT Inst event stream (aggregate-keyed, CloudEvents headers). */
@QuarkusTest
@TestProfile(SctInstOutboxDispatchConformanceIT.DispatchEnabledProfile::class)
@QuarkusTestResource(
    value = SctInstOutboxDispatchConformanceIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_conformance_it")],
    restrictToAnnotatedClass = true,
)
class SctInstOutboxDispatchConformanceIT : OutboxDispatchConformanceIT() {
    class DispatchEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "true",
            // The test drives dispatch itself; the cron must not claim a seeded row first.
            "openbank.outbox.initial-delay" to "1h",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("sct-inst-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var dispatcher: SctInstOutboxDispatcher

    @Inject lateinit var repository: SctInstOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    override lateinit var connector: InMemoryConnector

    override val channelName: String = "sct-inst-events-out"

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun triggerDispatch() {
        dispatcher.dispatch()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repository.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
