// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.openbank.notification.application.OperatorDispatchStatus
import com.openbank.notification.application.OperatorMessageRequest
import com.openbank.notification.application.OperatorMessageService
import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import com.openbank.notification.it.PostgresTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** A real refused SMTP connection must never turn a durable PENDING fact into a false SENT. */
@QuarkusTest
@TestProfile(OperatorMessageHandoffFailureIT.RefusedSmtpProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(OperatorMessageHandoffFailureIT.InMemoryKafkaResource::class)
class OperatorMessageHandoffFailureIT {

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

    class RefusedSmtpProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.mailer.mock" to "false",
            "quarkus.mailer.host" to "127.0.0.1",
            "quarkus.mailer.port" to "1",
        )
    }

    @Inject lateinit var service: OperatorMessageService

    @Inject lateinit var repository: NotificationRepository

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    @Test
    fun `refused SMTP handoff stays pending and returns a reconciliation id`() {
        val partyId = UUID.randomUUID()
        val result = onVertxContext {
            service.compose(
                OperatorMessageRequest(
                    partyId = partyId,
                    template = OperatorMessageTemplate.SUPPORT_FOLLOWUP,
                    recipient = "synthetic@example.test",
                    variables = mapOf("ticketReference" to "TCK-SYNTHETIC"),
                ),
            )
        }

        val row = onVertxContext {
            Panache.withSession { repository.find("notificationId", result.id).firstResult() }.awaitSuspending()
        }
        assertThat(result.dispatchStatus).isEqualTo(OperatorDispatchStatus.IN_DOUBT)
        assertThat(row).isNotNull()
        assertThat(row!!.status).isEqualTo("PENDING")
        assertThat(row.sentAt).isNull()
    }
}
