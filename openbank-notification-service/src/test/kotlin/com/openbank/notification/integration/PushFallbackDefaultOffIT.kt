// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for license information.

package com.openbank.notification.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.infrastructure.persistence.entity.NotificationOutboxEntity
import com.openbank.notification.infrastructure.persistence.repository.NotificationOutboxRepositoryImpl
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Proves #4363 is inert until a separately reviewed deployment enables it. */
@QuarkusTest
@TestProfile(PushFallbackDefaultOffIT.DefaultOffProfile::class)
@QuarkusTestResource(PushFallbackDefaultOffIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.notification.it.PostgresTestResource::class)
class PushFallbackDefaultOffIT {

    /** Profile overrides outrank ambient shell variables, preserving the production default-off contract. */
    class DefaultOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.notification.push-fallback.enabled" to "false")
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("notification-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory("notification-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject lateinit var repository: NotificationRepository

    @Inject lateinit var outboxRepo: NotificationOutboxRepositoryImpl

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Test
    fun `approved no-device template remains PUSH-only while fallback is disabled`() {
        val partyId = UUID.randomUUID()
        val request = NotificationRequest(
            partyId = partyId,
            channel = NotificationChannel.PUSH,
            template = NotificationTemplate.ACCOUNT_FROZEN,
            recipient = "fallback-default-off@example.com",
            variables = mapOf("accountNumber" to "synthetic-account-token"),
        )
        val source: InMemorySource<Message<String>> = connector.source("notification-events-in")
        source.runOnVertxContext(true)
        val acked = CompletableFuture<Void>()
        source.send(
            Message.of(
                objectMapper.writeValueAsString(request),
                Supplier<CompletionStage<Void>> {
                    acked.complete(null)
                    CompletableFuture.completedFuture<Void>(null)
                },
            ),
        )
        acked.get(20, TimeUnit.SECONDS)

        val rows = VertxContextSupport.subscribeAndAwait {
            Panache.withSession { repository.find("partyId", partyId).list() }
        }
        assertThat(rows).hasSize(1)
        assertThat(rows.single().channel).isEqualTo(NotificationChannel.PUSH.name)
        assertThat(rows.single().status).isEqualTo("FAILED")
        assertThat(rows.single().failureReason).isEqualTo(NotificationOutcomeEvent.REASON_NO_DEVICE)

        val outcomes = VertxContextSupport.subscribeAndAwait {
            Panache.withSession {
                outboxRepo.find("aggregateId", rows.single().notificationId).list()
            }
        }
        assertThat(outcomes.map(NotificationOutboxEntity::payload))
            .noneMatch { objectMapper.readTree(it).path("outcome").asText() == "REROUTED" }
    }
}
