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
import com.openbank.notification.domain.model.NotificationOutcomeEvent
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

    private fun reasonFor(partyId: UUID): String? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.failureReason

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

    /**
     * The gate ALLOWS, so the message reaches the mailer — which under `%test` is the mock, so the
     * terminal status is SUPPRESSED / `mailer_mocked` (issue #4737), not SENT.
     *
     * This test used to assert `SENT`, and that single value was doing two unrelated jobs: naming
     * the terminal status, and being the ONLY thing separating an allowed send from a denied one.
     * Fixing #4737 makes every case in this class SUPPRESSED, so keeping status as the
     * discriminator would leave three green tests that no longer discriminate anything — the
     * failure mode this repo has already been bitten by (the fleet liveness `age > FIFTY_YEARS`
     * assertions). The observable is switched rather than the bound retuned: **the mailbox**.
     * `MockMailbox` records what was actually handed to the mailer, which is precisely the fact
     * the gate decides, and it stays discriminating whether or not the mailer is mocked.
     */
    @Test
    fun `a consented party's marketing send passes the gate and reaches the mailer`() {
        StubContactGateProducer.consented = true
        StubContactGateProducer.sendsInWindow = 0
        mailbox.clear()
        val request = marketingRequest("marketing-allowed@example.com")

        consumeAndAwait(request)

        // The gate let it through: the mailer was actually called. This is the assertion that
        // distinguishes this test from the two below, and it does not depend on the mock setting.
        assertThat(mailbox.getMailMessagesSentTo("marketing-allowed@example.com")).hasSize(1)
        // ...and because the mailer is the mock, nothing left the process, so the record says so.
        assertThat(statusFor(request.partyId)).isEqualTo("SUPPRESSED")
        assertThat(reasonFor(request.partyId)).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
    }

    @Test
    fun `no active consent denies with the pre-existing no_active_consent reason`() {
        StubContactGateProducer.consented = false
        StubContactGateProducer.sendsInWindow = 0
        mailbox.clear()
        val request = marketingRequest("marketing-no-consent@example.com")

        consumeAndAwait(request)

        assertThat(statusFor(request.partyId)).isEqualTo("SUPPRESSED")
        // The reason is what separates a refused send from a mocked one — both are SUPPRESSED, and
        // conflating "a GDPR control worked" with "this environment has no SMTP" would make the
        // suppression series unusable, the same distinction no_active_consent already draws
        // against consent_check_unavailable.
        assertThat(reasonFor(request.partyId)).isEqualTo(NotificationOutcomeEvent.REASON_NO_CONSENT)
        // Denied before the channel dispatch: the mailer was never called at all.
        assertThat(mailbox.getMailMessagesSentTo("marketing-no-consent@example.com")).isEmpty()
    }

    @Test
    fun `an exhausted send cap — a reason this service could not previously produce — suppresses too`() {
        StubContactGateProducer.consented = true
        StubContactGateProducer.sendsInWindow = Int.MAX_VALUE
        mailbox.clear()
        val request = marketingRequest("marketing-cap@example.com")

        consumeAndAwait(request)

        assertThat(statusFor(request.partyId)).isEqualTo("SUPPRESSED")
        assertThat(reasonFor(request.partyId)).isEqualTo(NotificationOutcomeEvent.REASON_SEND_CAP_REACHED)
        assertThat(mailbox.getMailMessagesSentTo("marketing-cap@example.com")).isEmpty()
    }

    /**
     * The falsification for the three above: every case in this class is SUPPRESSED, so status
     * alone can no longer tell them apart. Pinned as an explicit assertion so that anyone who
     * reintroduces a status-based discriminator here sees why it cannot work.
     */
    @Test
    fun `status alone no longer discriminates these cases — the reason and the mailbox do`() {
        val reasons = setOf(
            NotificationOutcomeEvent.REASON_MAILER_MOCKED,
            NotificationOutcomeEvent.REASON_NO_CONSENT,
            NotificationOutcomeEvent.REASON_SEND_CAP_REACHED,
        )
        assertThat(reasons).hasSize(3)
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
