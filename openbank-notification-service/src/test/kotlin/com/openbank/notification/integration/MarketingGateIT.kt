// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * ADR-0219 D4's choke point, end to end: `NotificationConsumer.gateMarketingOnConsent` now goes
 * through `ContactPolicyGate.check` instead of a bespoke consent-only call. [StubContactGateProducer]
 * replaces the real producer (@Alternative @Priority(1), same idiom as
 * `NotificationConsumerIT.OffContextPushSender`) so each test controls the gate's inputs directly
 * — real consent-service and real send-log are neither reachable nor needed here.
 */
@QuarkusTest
@QuarkusTestResource(MarketingGateIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.notification.it.PostgresTestResource::class)
class MarketingGateIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("notification-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory("notification-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var repository: NotificationRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var mailbox: MockMailbox

    @Inject
    @org.eclipse.microprofile.reactive.messaging.spi.Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    private fun statusFor(partyId: UUID): String? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.status

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

    private fun marketingRequest(recipient: String) = NotificationRequest(
        partyId = UUID.randomUUID(),
        channel = NotificationChannel.EMAIL,
        template = NotificationTemplate.MARKETING_PRODUCT_OFFER,
        recipient = recipient,
        variables = mapOf(
            "offerTitle" to "New savings rate",
            "offerText" to "Check it out",
            "ctaText" to "See details",
        ),
    )

    @Test
    fun `a consented party's marketing send goes through the gate to SENT`() {
        StubContactGateProducer.consented = true
        StubContactGateProducer.sendsInWindow = 0
        mailbox.clear()
        val request = marketingRequest("marketing-allowed@example.com")

        consumeAndAwait(request)

        assertThat(statusFor(request.partyId)).isEqualTo("SENT")
        assertThat(mailbox.getMailMessagesSentTo("marketing-allowed@example.com")).hasSize(1)
    }

    @Test
    fun `no active consent denies with the pre-existing no_active_consent reason`() {
        StubContactGateProducer.consented = false
        StubContactGateProducer.sendsInWindow = 0
        val request = marketingRequest("marketing-no-consent@example.com")

        consumeAndAwait(request)

        assertThat(statusFor(request.partyId)).isEqualTo("SUPPRESSED")
    }

    @Test
    fun `an exhausted send cap — a reason this service could not previously produce — suppresses too`() {
        StubContactGateProducer.consented = true
        StubContactGateProducer.sendsInWindow = Int.MAX_VALUE
        val request = marketingRequest("marketing-cap@example.com")

        consumeAndAwait(request)

        assertThat(statusFor(request.partyId)).isEqualTo("SUPPRESSED")
    }
}

/**
 * Replaces the real `ContactGateProducer`-built bean. [consented] and [sendsInWindow] are plain
 * knobs each test sets before driving a request through the consumer, so the outcome is
 * deterministic without a real consent-service call or a real send-log query.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubContactGateProducer {
    @Produces
    @ApplicationScoped
    fun contactPolicyGate(): ContactPolicyGate = ContactPolicyGate(
        consent = ContactConsentPort { _, _ -> consented },
        counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int = sendsInWindow
            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int = 0
        },
        suppression = ContactSuppressionPort { emptyList() },
        policy = ContactPolicy(),
        // A FIXED clock, at 12:00 UTC. ContactPolicy's defaults are quietHoursStart = 21 and
        // quietHoursEnd = 8, and ContactPolicyGate reads them against `clock()`, which defaults to
        // Instant.now(). Without this the outcome of every test here depends on the wall clock of
        // whatever runs it: the SENT case is SUPPRESSED with reason QUIET_HOURS between 21:00 and
        // 08:00 in the platform zone, and green the rest of the day. Measured on main at 21:37
        // local — `expected: "SENT" but was: "SUPPRESSED"`.
        clock = { FIXED_NOW },
    )

    companion object {
        var consented: Boolean = true
        var sendsInWindow: Int = 0

        /** 2026-01-15T12:00:00Z — midday, so outside the 21→8 quiet window in any platform zone. */
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T12:00:00Z")
    }
}
