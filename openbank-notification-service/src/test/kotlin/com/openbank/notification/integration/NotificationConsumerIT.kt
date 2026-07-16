// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
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

/**
 * End-to-end coverage of the notification consumer's reactive persist chain that the pure-function
 * unit tests cannot reach: a real reactive Panache session drives [NotificationConsumer.consume]
 * against the dedicated IT Postgres (ADR-0043), while the Kafka inbound leg is swapped to the
 * in-memory connector so we can inject a message and observe its acknowledgement.
 *
 * What this proves — and what the silent-drop bug it guards against violated:
 *  - The `@Incoming` handler runs to completion on a proper Vert.x context: the
 *    `Panache.withTransaction { … }` chain commits, so a row is actually persisted
 *    (the bug left the table empty).
 *  - The message is **acked**. In production the SmallRye Kafka connector turns that ack into a
 *    consumer-group offset commit, so acking here is the in-memory stand-in for "the offset
 *    advanced" (the bug never acked → `CURRENT-OFFSET = -` forever).
 *
 * If the handler were still a stalling `suspend` function, the ack future below would never
 * complete and this test would fail on the `get(…)` timeout — exactly the production symptom.
 */
@QuarkusTest
@QuarkusTestResource(NotificationConsumerIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.notification.it.PostgresTestResource::class)
class NotificationConsumerIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("notification-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var repository: NotificationRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var mailbox: MockMailbox

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    // Reactive Panache must run on a Vert.x duplicated context; the JUnit thread is not one,
    // so every DB read is driven through VertxContextSupport.subscribeAndAwait.
    private fun countFor(partyId: UUID): Long = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).count() }
    }

    private fun statusFor(partyId: UUID): String? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.status

    private fun bodyFor(partyId: UUID): String? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.body

    /** Drive one request through the in-memory inbound channel and wait for the ack. */
    private fun consumeAndAwait(request: NotificationRequest) {
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
    }

    @Test
    fun `consumed message is persisted and acked (drives the offset commit)`() {
        val partyId = UUID.randomUUID()
        val request = NotificationRequest(
            partyId = partyId,
            channel = NotificationChannel.EMAIL,
            template = NotificationTemplate.WELCOME,
            recipient = "alice@example.com",
            variables = mapOf("name" to "Alice"),
        )
        val payload = objectMapper.writeValueAsString(request)

        // Emit on a Vert.x context to mirror how the real Kafka connector delivers records — the
        // very condition under which the old `suspend` handler stalled.
        val source: InMemorySource<Message<String>> = connector.source("notification-events-in")
        source.runOnVertxContext(true)

        // Send the payload as a Message whose ack callback completes `acked`. SmallRye invokes the
        // ack supplier exactly when the handler's returned Uni completes successfully.
        val acked = CompletableFuture<Void>()
        val message = Message.of(
            payload,
            Supplier<CompletionStage<Void>> {
                acked.complete(null)
                CompletableFuture.completedFuture<Void>(null)
            },
        )
        source.send(message)

        // The handler ran to completion → the message was acked. The 20s budget makes a regression
        // (a stalling suspend handler that never acks) fail loudly instead of hanging the build.
        acked.get(20, TimeUnit.SECONDS)

        // …and the reactive transaction actually committed: exactly one row for this party, marked
        // SENT (the %test profile mocks the mailer, so the email leg succeeds).
        assertThat(countFor(partyId)).isEqualTo(1L)
        assertThat(statusFor(partyId)).isEqualTo("SENT")
    }

    /**
     * The redaction must bite at the storage boundary and nowhere earlier: the customer still
     * receives the real OTP, the database never holds it. Asserting both halves in one test is
     * the point — redacting at render time would also make the stored body safe, while silently
     * mailing the customer a useless placeholder.
     */
    @Test
    fun `OTP is delivered to the customer but never stored (ADR-0021, GDPR Art 5(1)(c))`() {
        val partyId = UUID.randomUUID()
        val code = "828913"
        mailbox.clear()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.OTP_CODE,
                recipient = "otp-recipient@example.com",
                variables = mapOf("code" to code),
            ),
        )

        // Delivered: the mail that left the service carries the real code.
        val sent = mailbox.getMailMessagesSentTo("otp-recipient@example.com")
        assertThat(sent).hasSize(1)
        assertThat(sent.first().html).contains(code)

        // Stored: the row exists and is SENT, but its body is the placeholder — an operator
        // reading it through NotificationResource cannot recover the code.
        assertThat(countFor(partyId)).isEqualTo(1L)
        assertThat(statusFor(partyId)).isEqualTo("SENT")
        assertThat(bodyFor(partyId)).isEqualTo(TemplateSensitivity.REDACTED_BODY)
        assertThat(bodyFor(partyId)).doesNotContain(code)
    }

    /** An ordinary template is untouched — redaction is an allow-list, not a blanket. */
    @Test
    fun `non-secret template still stores its rendered body`() {
        val partyId = UUID.randomUUID()
        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.WELCOME,
                recipient = "welcome-recipient@example.com",
                variables = mapOf("name" to "Alice"),
            ),
        )

        assertThat(bodyFor(partyId)).contains("Alice")
        assertThat(bodyFor(partyId)).isNotEqualTo(TemplateSensitivity.REDACTED_BODY)
    }
}
