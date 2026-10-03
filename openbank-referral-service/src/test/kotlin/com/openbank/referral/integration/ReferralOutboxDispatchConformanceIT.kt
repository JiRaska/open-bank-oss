// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.referral.integration

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.outbox.OutboxDispatchConformanceIT
import com.openbank.referral.domain.ReferralEvent
import com.openbank.referral.infrastructure.outbox.ReferralOutboxDispatcher
import com.openbank.referral.infrastructure.persistence.repository.ReferralOutboxRepositoryImpl
import com.openbank.referral.it.ReferralPostgresTestResource
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
 * [ReferralOutboxDispatcher] → [ReferralOutboxRepositoryImpl] → reactive Panache → in-memory Kafka wiring, now that the
 * repository is the kernel's `AbstractPanacheOutboxRepository`.
 */
@QuarkusTest
@TestProfile(ReferralOutboxDispatchConformanceIT.DispatchEnabledProfile::class)
@QuarkusTestResource(
    value = ReferralOutboxDispatchConformanceIT.InMemoryKafkaResource::class,
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(ReferralPostgresTestResource::class)
class ReferralOutboxDispatchConformanceIT : OutboxDispatchConformanceIT() {

    // The test config leaves the dispatcher off so other ITs own their rows; this suite drives
    // the real bean, whose dispatch() is a no-op unless the switch is on.
    class DispatchEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "true")
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("referral-qualified-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("account-created-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dispatcher: ReferralOutboxDispatcher

    @Inject
    lateinit var repository: ReferralOutboxRepositoryImpl

    @Inject
    @Connector("smallrye-in-memory")
    override lateinit var connector: InMemoryConnector

    override val channelName = "referral-qualified-out"

    // The referral publisher routes by event type and rejects any type it does not wire; the
    // Qualified type is the one whose emitter is the channel above.
    override fun eventType(suggested: String): String = ReferralEvent.Qualified::class.simpleName!!

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repository.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun triggerDispatch() {
        dispatcher.dispatch()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repository.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
