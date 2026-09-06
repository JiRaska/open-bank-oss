// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.application.NotificationConsumer.Companion.GENERIC_FALLBACK_EMAIL_BODY
import com.openbank.notification.application.NotificationConsumer.Companion.GENERIC_PUSH_BODY
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.application.port.out.PushSender
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.PushResult
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.persistence.entity.DeviceTokenEntity
import com.openbank.notification.infrastructure.persistence.entity.NotificationOutboxEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationOutboxRepositoryImpl
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.MockMailbox
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Function
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
            InMemoryConnector.switchIncomingChannelsToInMemory("notification-events-in") +
                // Quarkus 3.38 dropped the quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers
                // alias, so the outgoing channel's kafka connector is unconfigured and the emitter
                // fails with "has no downstream" — killing consume() before the oversight side-channel
                // (OversightWebhookIT). Switching outgoing to in-memory keeps the out event flowing.
                InMemoryConnector.switchOutgoingChannelsToInMemory("notification-events-out") +
                // Exercise #4363 through the real consumer. The service config remains default-off;
                // production activation is a separately reviewed GitOps decision.
                mapOf("openbank.notification.push-fallback.enabled" to "true")

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

    private fun failureReasonFor(partyId: UUID): String? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.failureReason

    private fun sentAtFor(partyId: UUID): java.time.Instant? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.sentAt

    private fun notificationIdFor(partyId: UUID): UUID? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.notificationId

    private fun correlationIdFor(partyId: UUID): UUID? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).firstResult() }
    }?.correlationId

    private fun notificationsFor(partyId: UUID) = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("partyId", partyId).list() }
    }

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

    /**
     * Like [consumeAndAwait], but reports which of ack/nack the connector actually invoked,
     * instead of assuming ack (#5745). SmallRye calls the message's `nack` function when the
     * `@Incoming` handler's returned `Uni` fails, and its `ack` supplier when it succeeds —
     * exactly the distinction [NotificationConsumer.consume]'s dispatch failure handling used to
     * erase by recovering every failure into a successful (acked) `Uni`.
     */
    private fun sendAndAwaitOutcome(request: NotificationRequest): String {
        val source: InMemorySource<Message<String>> = connector.source("notification-events-in")
        source.runOnVertxContext(true)
        val outcome = CompletableFuture<String>()
        val message = Message.of(
            objectMapper.writeValueAsString(request),
            Metadata.empty(),
            Supplier<CompletionStage<Void>> {
                outcome.complete("acked")
                CompletableFuture.completedFuture<Void>(null)
            },
            Function<Throwable, CompletionStage<Void>> { _ ->
                outcome.complete("nacked")
                CompletableFuture.completedFuture<Void>(null)
            },
        )
        source.send(message)
        return outcome.get(20, TimeUnit.SECONDS)
    }

    /**
     * The core of #5745 (sweep of #5698): a processing failure must not be told to Kafka as "done".
     *
     * Before the fix, [NotificationConsumer.consume] caught this exact failure, logged it, and
     * returned a successful `Uni` — which SmallRye acks. An acked message and a delivered one are
     * indistinguishable from outside, so a transient failure here (this channel also carries
     * SECURITY-category sends: OTP, SCA approval) was silently lost with nothing to replay it.
     *
     * Fails against unmodified `main`: the old `.onFailure().recoverWithUni { ... ack }` makes this
     * assert `"nacked"` against an outcome of `"acked"`.
     */
    @Test
    fun `a dispatch failure is nacked, not acked, so the connector can dead-letter it (#5745)`() {
        val partyId = UUID.randomUUID()
        seedActiveDevice(partyId, OffContextPushSender.FAILING_TOKEN)

        val outcome = sendAndAwaitOutcome(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.WELCOME,
                recipient = "dispatch-failure@example.com",
                variables = mapOf("name" to "Retry"),
            ),
        )

        assertThat(outcome).isEqualTo("nacked")
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

        // …and the reactive transaction actually committed: exactly one row for this party. The
        // %test profile mocks the mailer, so the terminal status is SUPPRESSED / mailer_mocked
        // (issue #4737) — this used to assert SENT, which was the defect stated as an expectation.
        // The subject of THIS test is the ack and the commit, not the delivery outcome.
        assertThat(countFor(partyId)).isEqualTo(1L)
        assertThat(statusFor(partyId)).isEqualTo("SUPPRESSED")
        assertThat(failureReasonFor(partyId)).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
        assertThat(notificationIdFor(partyId)?.version()).isEqualTo(7)
    }

    @Test
    fun `same durable first-use fact is persisted only once across redelivery`() {
        val partyId = UUID.randomUUID()
        val grantId = UUID.randomUUID()
        val request = NotificationRequest(
            partyId = partyId,
            channel = NotificationChannel.PUSH,
            template = NotificationTemplate.DELEGATION_FIRST_USE,
            recipient = partyId.toString(),
            variables = emptyMap(),
            correlationId = grantId,
            deduplicationKey = grantId,
        )

        consumeAndAwait(request)
        // What ONE delivery leaves behind is not one row: this class runs with
        // push-fallback.enabled=true and the party has no device tokens, so the PUSH reroutes to a
        // generic EMAIL row (#4363). That second row is the fallback working, not a duplicate —
        // asserting a total of one row conflated "one deduplicated fact" with "one row" and failed
        // against correct behaviour.
        val afterFirstDelivery = notificationsFor(partyId).size
        consumeAndAwait(request)

        // The subject of this test: the durable fact exists exactly once, and the redelivery adds
        // nothing at all. The second assertion is what would catch a dedup regression even if the
        // fallback's row count changes later.
        assertThat(notificationsFor(partyId).count { it.deduplicationKey == grantId }).isEqualTo(1)
        assertThat(notificationsFor(partyId)).hasSize(afterFirstDelivery)
        assertThat(correlationIdFor(partyId)).isEqualTo(grantId)
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

        // Stored: the row exists, but its body is the placeholder — an operator reading it
        // through NotificationResource cannot recover the code. The status is SUPPRESSED because
        // the %test mailer is the mock (#4737); the redaction assertions below are the subject.
        assertThat(countFor(partyId)).isEqualTo(1L)
        assertThat(statusFor(partyId)).isEqualTo("SUPPRESSED")
        assertThat(bodyFor(partyId)).isEqualTo(TemplateSensitivity.REDACTED_BODY)
        assertThat(bodyFor(partyId)).doesNotContain(code)
    }

    /**
     * The #1325 hole, end to end: a secret-shaped variable on an ordinary, non-SECRET template.
     *
     * Before the closed schema this rendered through the `else` branch as `code: 483920` and was
     * persisted in cleartext — `TemplateSensitivity` could not help, because it classifies
     * templates and the template here is legitimately not secret. Now the request never reaches
     * `dispatch`, so no row exists at all.
     */
    @Test
    fun `undeclared variable is rejected before anything is stored (issue 1325)`() {
        val partyId = UUID.randomUUID()
        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.ACCOUNT_FROZEN,
                recipient = "frozen@example.com",
                variables = mapOf(
                    "accountNumber" to "CZ6508000000192000145399",
                    "reason" to "AML review",
                    "code" to "483920",
                ),
            ),
        )

        // Rejected, not stored-then-redacted: the row was never written.
        assertThat(countFor(partyId)).isEqualTo(0L)
    }

    /** The same template without the smuggled key goes through untouched. */
    @Test
    fun `declared variables on the same template are accepted and stored`() {
        val partyId = UUID.randomUUID()
        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.ACCOUNT_FROZEN,
                recipient = "frozen-ok@example.com",
                variables = mapOf("accountNumber" to "CZ6508000000192000145399", "reason" to "AML review"),
            ),
        )

        assertThat(countFor(partyId)).isEqualTo(1L)
        assertThat(bodyFor(partyId)).contains("AML review")
        assertThat(bodyFor(partyId)).doesNotContain("483920")
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

    // ── Delivery-outcome events (ADR-0239 D2, issue #3663) ──

    @Inject
    lateinit var outboxRepo: NotificationOutboxRepositoryImpl

    /** Outbox rows for one notification, read through a real reactive session. */
    private fun outcomeRowsFor(notificationId: UUID): List<NotificationOutboxEntity> =
        VertxContextSupport.subscribeAndAwait {
            Panache.withSession { outboxRepo.find("aggregateId", notificationId).list() }
        } ?: emptyList()

    /**
     * The whole of issue #3663 in one assertion: a producer that handed a request over has, until
     * now, had NOTHING to read back. The status write and this row commit in one transaction
     * (ADR-0003), so a row that exists is a transition that happened.
     *
     * Asserts the correlation id is ECHOED, not merely that some event was emitted — an outcome the
     * producer cannot join back to its own row is the same silence in a more expensive form.
     */
    @Test
    fun `a completed send emits a correlated delivery-outcome event (ADR-0239 D2)`() {
        val partyId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.WELCOME,
                recipient = "outcome-correlated@example.com",
                variables = mapOf("name" to "Alice"),
                correlationId = correlationId,
            ),
        )

        assertThat(statusFor(partyId)).isEqualTo("SUPPRESSED")
        val notificationId = notificationIdFor(partyId)!!
        val rows = outcomeRowsFor(notificationId)
        assertThat(rows)
            .withFailMessage("no delivery-outcome event was emitted for a completed send (issue #3663)")
            .hasSize(1)
        assertThat(rows.single().eventType).isEqualTo("NotificationOutcome")

        val event = objectMapper.readTree(rows.single().payload)
        // SUPPRESSED / mailer_mocked, not SENT: the %test mailer is the mock (#4737). The subject
        // here is that an outcome event is emitted at all and carries the correlation id — the
        // outcome VALUE is incidental to it, and asserting SENT asserted the defect.
        assertThat(event.path("outcome").asText()).isEqualTo("SUPPRESSED")
        assertThat(event.path("reason").asText()).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
        assertThat(event.path("correlationId").asText()).isEqualTo(correlationId.toString())
        assertThat(event.path("notificationId").asText()).isEqualTo(notificationId.toString())
        assertThat(event.path("partyId").asText()).isEqualTo(partyId.toString())
        // The row itself carries the correlation id too, so an operator can join it back without
        // replaying the topic.
        assertThat(correlationIdFor(partyId)).isEqualTo(correlationId)
    }

    /**
     * The uncorrelated case, which is the majority of this topic's traffic. An event is still
     * emitted — ADR-0239 D2 makes it a shared contract, not a private channel back to one consumer
     * — and its `correlationId` is null rather than a value nothing can match.
     */
    @Test
    fun `an uncorrelated send still emits an outcome, with a null correlation id`() {
        val partyId = UUID.randomUUID()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.ACCOUNT_OPENED,
                recipient = "outcome-uncorrelated@example.com",
                variables = mapOf("accountNumber" to "CZ6508000000192000145399"),
            ),
        )

        val rows = outcomeRowsFor(notificationIdFor(partyId)!!)
        assertThat(rows).hasSize(1)
        val event = objectMapper.readTree(rows.single().payload)
        assertThat(event.path("outcome").asText()).isEqualTo("SUPPRESSED")
        assertThat(event.path("correlationId").isNull).isTrue()
        assertThat(correlationIdFor(partyId)).isNull()
    }

    /**
     * A request rejected before a row exists has had no transition, so it must emit nothing. The
     * negative matters: an event for a request that was never dispatched would settle a producer's
     * row on the strength of something that never happened.
     */
    @Test
    fun `a rejected request emits no outcome at all`() {
        val partyId = UUID.randomUUID()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.ACCOUNT_FROZEN,
                recipient = "outcome-rejected@example.com",
                variables = mapOf(
                    "accountNumber" to "CZ6508000000192000145399",
                    "reason" to "AML review",
                    "code" to "483920",
                ),
                correlationId = UUID.randomUUID(),
            ),
        )

        assertThat(countFor(partyId)).isEqualTo(0L)
    }

    // ── PUSH channel end-to-end (issue #1548 hardening) ──

    @Inject
    lateinit var deviceTokenRepo: DeviceTokenRepository

    private fun seedActiveDevice(partyId: UUID, token: String) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                val now = Instant.now()
                val entity = DeviceTokenEntity().also {
                    it.deviceId = UUID.randomUUID()
                    it.partyId = partyId
                    it.appInstance = "it-instance-$token"
                    it.platform = "APNS"
                    it.token = token
                    it.status = "ACTIVE"
                    it.registeredAt = now
                    it.createdAt = now
                    it.updatedAt = now
                }
                deviceTokenRepo.persist(entity)
            }
        }
    }

    private fun deviceStatusFor(token: String): String? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { deviceTokenRepo.find("token", token).firstResult() }
    }?.status

    /**
     * End-to-end PUSH coverage (there was none before #1548): one device the adapter accepts, one
     * it rejects as invalid. Asserts the fan-out result is persisted — row flips to SENT (≥1
     * delivered) and the rejected token is retired. [OffContextPushSender] completes its send on a
     * worker thread to mirror ApnsPushSender's JDK HttpClient.
     *
     * NOTE: this exercises the post-send transaction but does NOT deterministically reproduce the
     * production "No current Vertx context found" failure — Quarkus' Mutiny context propagation
     * restores the context inside this Testcontainers harness, so the pre-fix code also passes here.
     * The fix (capture the Vert.x context, hop back onto it after the off-loop send) is verified
     * against the real APNs path in the sandbox: with the adapter enabled the row now commits SENT.
     */
    @Test
    fun `PUSH delivery persists SENT and retires provider-rejected tokens`() {
        val partyId = UUID.randomUUID()
        seedActiveDevice(partyId, OffContextPushSender.GOOD_TOKEN)
        seedActiveDevice(partyId, OffContextPushSender.BAD_TOKEN)

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.WELCOME,
                recipient = "push-recipient@example.com",
                variables = mapOf("name" to "Push"),
            ),
        )

        // At least one device accepted → row committed SENT (pre-fix: stuck PENDING).
        assertThat(statusFor(partyId)).isEqualTo("SENT")
        // The provider-rejected token was retired in the same transaction (pre-fix: stayed ACTIVE).
        assertThat(deviceStatusFor(OffContextPushSender.BAD_TOKEN)).isEqualTo("INVALID")
        assertThat(deviceStatusFor(OffContextPushSender.GOOD_TOKEN)).isEqualTo("ACTIVE")
        // A delivered push carries no failure reason — the column means "why this FAILED", so a
        // stale value on a SENT row would be worse than none.
        assertThat(failureReasonFor(partyId)).isNull()
    }

    /**
     * Issue #4512 — a PUSH for a party with NO device token must still leave a durable row.
     *
     * Measured in the sandbox on 2026-08-13: four SCA_APPROVAL pushes were logged on 2026-08-09
     * (`PUSH: no active devices`, four distinct parties, three hours apart) and the `notifications`
     * table holds no row for any of them — its newest row is 2026-08-08 11:35. Not erasure (no
     * `GDPR Art. 17` log line and no `party-events-in` erase in the window), not a restore (the
     * CNPG cluster has run on its original `initdb` bootstrap since 2026-06-01), and not a
     * dispatch failure (`Failed to process notification` never logged).
     *
     * This pins the code path the issue accuses: `dispatch` persists the row in its own
     * transaction BEFORE the channel fan-out, and the no-device branch then marks it FAILED with
     * `no_active_device`. If this passes, the persistence path is sound and the missing rows are
     * an operational fact about the deployment rather than a defect here — which is worth knowing
     * before anyone "fixes" the code.
     */
    @Test
    fun `a PUSH to a party with no device still persists a FAILED row and its reason`() {
        val partyId = UUID.randomUUID()
        // Deliberately seed nothing: this is the no-device case.

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.SCA_APPROVAL,
                recipient = "no-device@example.com",
                variables = mapOf("detail" to "Payment of 10.00 EUR"),
            ),
        )

        // The row is the point. A notification that was attempted and left no trace cannot be
        // counted, alerted on, or reconstructed after the fact.
        assertThat(countFor(partyId)).isEqualTo(1)
        assertThat(statusFor(partyId)).isEqualTo("FAILED")
        assertThat(failureReasonFor(partyId)).isEqualTo(NotificationOutcomeEvent.REASON_NO_DEVICE)
    }

    @Test
    fun `approved no-device template creates a separate generic EMAIL fallback`() {
        val partyId = UUID.randomUUID()
        mailbox.clear()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.ACCOUNT_FROZEN,
                recipient = "fallback@example.com",
                variables = mapOf("accountNumber" to "synthetic-account-token", "reason" to "synthetic-reason"),
            ),
        )

        val rows = notificationsFor(partyId)
        assertThat(rows).hasSize(2)
        val original = rows.single { it.channel == NotificationChannel.PUSH.name }
        val fallback = rows.single { it.channel == NotificationChannel.EMAIL.name }
        // The original never claims a different-channel success; the generic fallback has its own row.
        assertThat(original.status).isEqualTo("FAILED")
        assertThat(original.failureReason).isEqualTo(NotificationOutcomeEvent.REASON_REROUTED_NO_DEVICE)
        assertThat(fallback.status).isEqualTo("SUPPRESSED")
        assertThat(fallback.failureReason).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
        assertThat(fallback.body)
            .isEqualTo(GENERIC_FALLBACK_EMAIL_BODY)
            .doesNotContain("synthetic-account-token")
            .doesNotContain("synthetic-reason")
        assertThat(mailbox.getMailMessagesSentTo("fallback@example.com")).hasSize(1)

        val originalEvent = objectMapper.readTree(outcomeRowsFor(original.notificationId).single().payload)
        assertThat(originalEvent.path("outcome").asText()).isEqualTo("REROUTED")
        assertThat(originalEvent.path("reason").asText())
            .isEqualTo(NotificationOutcomeEvent.REASON_REROUTED_NO_DEVICE)
    }

    /**
     * Issue #4737 — a send through a MOCKED mailer must not read as a delivery.
     *
     * The EMAIL twin of the push case below, and the same defect. `quarkus.mailer.mock=true` makes
     * `ReactiveMailer.send` complete successfully without opening an SMTP connection; the consumer
     * asked only whether the call threw, so the row committed `SENT` **with `sent_at` populated**
     * and the outcome event announced a delivery for a message that never left the process. The
     * deployed sandbox carries `QUARKUS_MAILER_MOCK=true` deliberately (its gitops manifest says
     * so), which is exactly why the record has to disagree with the configuration.
     *
     * `sent_at` is the assertion that matters most: the column means "when did this leave the
     * process", and a timestamp there is the concrete false claim the old code committed. The
     * whole `%test` profile runs mocked, so this passes trivially *now* — the falsification lives
     * in `EmailSendOutcomeTest`, which shows the old predicate computing SENT from the same two
     * facts. Asserted through the real consumer path rather than on the mapping function alone:
     * the unit test pins the mapping, this pins that the email leg actually calls it.
     */
    @Test
    fun `EMAIL through a mocked mailer is SUPPRESSED with a null sent_at, never SENT`() {
        val partyId = UUID.randomUUID()
        mailbox.clear()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.EMAIL,
                template = NotificationTemplate.WELCOME,
                recipient = "mocked-mailer@example.com",
                variables = mapOf("name" to "Mock"),
            ),
        )

        // The message really did reach the mailer — this is not a suppression upstream of the
        // channel, which is what makes the status below a statement about the MAILER.
        assertThat(mailbox.getMailMessagesSentTo("mocked-mailer@example.com")).hasSize(1)

        assertThat(statusFor(partyId)).isEqualTo("SUPPRESSED")
        assertThat(failureReasonFor(partyId)).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
        // The core of #4737: no timestamp for a transmission that never happened.
        assertThat(sentAtFor(partyId)).isNull()

        // ...and the outcome stream says the same thing, so a downstream consumer (campaign's
        // funnel) cannot count this as delivered either.
        val event = objectMapper.readTree(outcomeRowsFor(notificationIdFor(partyId)!!).single().payload)
        assertThat(event.path("outcome").asText()).isEqualTo("SUPPRESSED")
        assertThat(event.path("reason").asText()).isEqualTo(NotificationOutcomeEvent.REASON_MAILER_MOCKED)
    }

    /**
     * ADR-0252 phase 0 — a fan-out where every adapter is DISABLED must not read as a delivery.
     *
     * This is the production shape that hid a dead push channel: `ApnsPushSender` is
     * `enabled=false` by default and returns `PushResult.skipped(...)`, which is `success = true`.
     * The fan-out counted `success`, so the row committed SENT with `sentAt` set, and an
     * environment holding no APNs credentials was indistinguishable from a working one — in the
     * status column, in the outcome stream, and in the logs.
     *
     * Asserted through the real consumer path rather than on the mapping function alone: the unit
     * test pins the mapping, this pins that the fan-out actually calls it.
     */
    @Test
    fun `PUSH with every adapter disabled is SUPPRESSED, never SENT`() {
        val partyId = UUID.randomUUID()
        seedActiveDevice(partyId, OffContextPushSender.DISABLED_TOKEN)

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.WELCOME,
                recipient = "push-disabled@example.com",
                variables = mapOf("name" to "Push"),
            ),
        )

        assertThat(statusFor(partyId)).isEqualTo("SUPPRESSED")
        // Nothing was rejected, so the token stays usable the moment the adapter is switched on.
        assertThat(deviceStatusFor(OffContextPushSender.DISABLED_TOKEN)).isEqualTo("ACTIVE")
    }

    /**
     * ADR-0135 §3 + issue #1182: the push payload that leaves the service must carry NO transaction
     * amount, account number, or other PII. Pre-fix `sendPush` shipped `htmlToPlain(body)` — the
     * fully-rendered "Transaction of 12345.67 EUR completed" — as the PushMessage body, landing on
     * the lock screen. This asserts the delivered payload's title is the PII-free subject and the
     * body is the fixed generic wake string, with the amount present in NEITHER. Fails against the
     * old behavior (which put the amount in the body).
     */
    @Test
    fun `PUSH payload carries no amount or PII (ADR-0135 section 3, issue 1182)`() {
        val partyId = UUID.randomUUID()
        val amount = "12345.67"
        val account = "CZ6508000000192000145399"
        // A token unique to this test — the (platform, token) unique constraint is shared across the
        // IT DB, so reusing GOOD_TOKEN would collide with the fan-out test's seed. Delivery success
        // is irrelevant here: send() captures the outbound PushMessage regardless of accept/reject.
        val piiToken = "apns-pii-payload-token-it"
        OffContextPushSender.SENT.clear()
        seedActiveDevice(partyId, piiToken)

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.TRANSACTION_COMPLETED,
                recipient = "push-tx@example.com",
                variables = mapOf("amount" to amount, "currency" to "EUR"),
            ),
        )

        val delivered = OffContextPushSender.SENT.filter { it.token == piiToken }
        assertThat(delivered).hasSize(1)
        val msg = delivered.first()
        // Title is the already-PII-free subject; body is the fixed generic wake string.
        assertThat(msg.title).isEqualTo("Transaction completed")
        assertThat(msg.body).isEqualTo(GENERIC_PUSH_BODY)
        // The amount and account never appear anywhere in the transported payload.
        assertThat(msg.title).doesNotContain(amount).doesNotContain(account)
        assertThat(msg.body).doesNotContain(amount).doesNotContain(account)
        assertThat(msg.data).containsKeys("template", "notificationId")
    }

    @Test
    fun `PUSH payload carries an allow-listed deep link and opaque interaction reference`() {
        val partyId = UUID.randomUUID()
        val interactionRef = UUID.randomUUID()
        val token = "apns-campaign-deep-link-token-it"
        OffContextPushSender.SENT.clear()
        seedActiveDevice(partyId, token)

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.WELCOME,
                recipient = "campaign@example.com",
                variables = mapOf("name" to "Campaign customer"),
                deepLink = "openbank://savings",
                interactionRef = interactionRef,
            ),
        )

        assertThat(OffContextPushSender.SENT.single { it.token == token }.data)
            .containsEntry("deepLink", "openbank://savings")
            .containsEntry("interactionRef", interactionRef.toString())
            .containsKey("notificationId")
    }

    @Test
    fun `a push to a party with no device records WHY it failed, not just that it did`() {
        // No seedActiveDevice: this party has never registered one, which is the overwhelmingly
        // common case in the live estate — 40 of the 43 parties with a failed push.
        val partyId = UUID.randomUUID()

        consumeAndAwait(
            NotificationRequest(
                partyId = partyId,
                channel = NotificationChannel.PUSH,
                template = NotificationTemplate.TRANSACTION_COMPLETED,
                recipient = "no-device@example.com",
                variables = mapOf("amount" to "10.00", "currency" to "CZK"),
            ),
        )

        assertThat(statusFor(partyId)).isEqualTo("FAILED")
        // The point of the change: FAILED alone cannot distinguish "no device registered" from
        // "the provider rejected the token", and those need entirely different fixes.
        assertThat(failureReasonFor(partyId)).isEqualTo("no_active_device")
    }
}

/**
 * Test push adapter that completes its send on a worker thread — off the Vert.x event loop —
 * mirroring ApnsPushSender's JDK HttpClient completion. `@Alternative` at `@Priority(1)` replaces
 * the real PushSenderRouter for this test module; the non-PUSH tests never invoke it. The good
 * token is accepted, any other token is rejected as invalid.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class OffContextPushSender : PushSender {
    override fun send(message: PushMessage): Uni<PushResult> {
        // Record what actually crosses the transport boundary so a test can assert the payload
        // is PII-free (ADR-0135 §3, issue #1182).
        SENT.add(message)
        if (message.token == FAILING_TOKEN) {
            // A dependency FAILING — not a provider rejection like BAD_TOKEN, which still returns
            // a normal (if unsuccessful) PushResult. This Uni fails, exactly the shape of the
            // transient error #5745 is about (a downstream call throwing), so it can drive
            // NotificationConsumer.consume()'s dispatch() Uni to failure end to end.
            return Uni.createFrom().completionStage(
                CompletableFuture.supplyAsync(
                    { throw IllegalStateException("simulated transient push failure (#5745 test)") },
                    EXECUTOR,
                ),
            )
        }
        val result = when (message.token) {
            GOOD_TOKEN -> PushResult.ok("apns-id-it")
            // ADR-0252 phase 0: what a DISABLED adapter returns — a successful no-op.
            DISABLED_TOKEN -> PushResult.skipped("adapter disabled")
            else -> PushResult.failed("BadDeviceToken", "invalid token", invalidToken = true)
        }
        return Uni.createFrom().completionStage(CompletableFuture.supplyAsync({ result }, EXECUTOR))
    }

    companion object {
        const val GOOD_TOKEN = "apns-good-token-it"
        const val BAD_TOKEN = "apns-bad-token-it"

        /** Token whose send comes back *skipped*, i.e. the adapter is switched off. */
        const val DISABLED_TOKEN = "apns-disabled-adapter-token-it"

        /** Token whose send Uni FAILS outright — a dependency error, not a provider rejection. */
        const val FAILING_TOKEN = "apns-failing-token-it"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        /** Messages the adapter was asked to deliver, in order — inspected by the PII assertion. */
        val SENT: MutableList<PushMessage> = java.util.concurrent.CopyOnWriteArrayList()
    }
}
