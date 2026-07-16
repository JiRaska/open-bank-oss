// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.notification.application.port.out.OversightWebhookPublisher
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.application.port.out.PushSender
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.Mail
import io.quarkus.mailer.reactive.ReactiveMailer
import io.smallrye.mutiny.Uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class NotificationConsumer {

    @Inject lateinit var mailer: ReactiveMailer

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var notificationRepo: NotificationRepository

    @Inject lateinit var deviceTokenRepo: DeviceTokenRepository

    @Inject lateinit var pushSender: PushSender

    @Inject lateinit var clock: Clock

    @Inject lateinit var audit: AuditEventPublisher

    private val log = Logger.getLogger(NotificationConsumer::class.java)

    /**
     * Cache of oversight webhook adapters resolved via CDI at startup.
     *
     * `@Inject @All Instance<OversightWebhookPublisher>` on a Kotlin `lateinit var` field
     * returns an empty list in Quarkus 3.33.2 — a quirk of how ArC resolves `@All` on Kotlin
     * property-synthesised field injection points. `CDI.current().select()` resolves all beans
     * of the type correctly and is used as a workaround. The list is populated once at
     * `@PostConstruct` time (CDI container is fully initialised) and is effectively immutable.
     */
    private var cdiOversightAdapters: List<OversightWebhookPublisher> = emptyList()

    @PostConstruct
    fun init() {
        cdiOversightAdapters = jakarta.enterprise.inject.spi.CDI.current()
            .select(OversightWebhookPublisher::class.java).toList()
        log.infof("notification.consumer.init oversight_adapters=%d", cdiOversightAdapters.size)
    }

    /**
     * Reactive (Mutiny `Uni`), deliberately **not** a `suspend` function.
     *
     * Hibernate Reactive's `Panache.withTransaction {}` requires a Vert.x *duplicated*
     * context. SmallRye's Kotlin `suspend @Incoming` invoker captures
     * `Vertx.currentContext()` at dispatch time and re-dispatches the coroutine onto it;
     * on the Kafka polling thread that context is not the one Hibernate Reactive needs, so
     * `awaitSuspending()` never resumes. The handler stalls *before* acking — no
     * consumer-group offset is committed, demand for the next record is never signalled,
     * and because the stall happens around (not inside) the handler body, the `catch`
     * block logs nothing. That is exactly the silent-drop symptom seen on the T2 pilot.
     *
     * Returning a `Uni` hands SmallRye the reactive pipeline directly: it subscribes on a
     * proper context, the chain runs to completion, the message is acked, and the Kafka
     * offset advances. Same convention as openbank-statement-service ("Reactive Uni, not
     * suspend"). Delivery is at-least-once; a redelivery re-persists a fresh row, which is
     * acceptable for notifications (no money path).
     */
    @Incoming("notification-events-in")
    fun consume(payload: String): Uni<Void> {
        val req = try {
            objectMapper.readValue(payload, NotificationRequest::class.java)
        } catch (e: Exception) {
            // Un-parseable (poison) payload: log and ack so one bad record can't wedge the partition.
            log.errorf(e, "Failed to parse notification payload: %s", payload)
            return Uni.createFrom().voidItem()
        }
        return dispatch(req)
            .onFailure().recoverWithUni { e ->
                // Processing failure (e.g. transient DB error): log and ack. Preserves the prior
                // swallow semantics so the consumer keeps draining; redelivery is safe (see above).
                log.errorf(e, "Failed to process notification: %s", payload)
                Uni.createFrom().voidItem()
            }
    }

    private fun dispatch(req: NotificationRequest): Uni<Void> {
        val (subject, body) = renderTemplate(req.template, req.variables)
        val entity = NotificationEntity().also {
            it.notificationId = UUID.randomUUID()
            it.partyId = req.partyId
            it.channel = req.channel.name
            it.template = req.template.name
            it.recipient = req.recipient
            it.subject = subject
            // Secret-bearing templates persist a placeholder; `body` below still carries the
            // rendered secret to the delivery adapters, so the customer receives it as usual.
            it.body = TemplateSensitivity.bodyForStorage(req.template, body)
            it.status = "PENDING"
            it.createdAt = Instant.now(clock)
        }
        return Panache.withTransaction { notificationRepo.persist(entity) }
            .chain { _ ->
                when (req.channel) {
                    NotificationChannel.EMAIL -> sendEmail(req, subject, body, entity)
                    NotificationChannel.SMS -> {
                        log.infof("SMS stub: to=%s template=%s", req.recipient, req.template)
                        Uni.createFrom().voidItem()
                    }
                    NotificationChannel.PUSH -> sendPush(req, subject, body, entity)
                    NotificationChannel.IN_APP -> {
                        log.infof("IN_APP stub: to=%s template=%s", req.recipient, req.template)
                        Uni.createFrom().voidItem()
                    }
                }
            }
            // Oversight side-channel (ADR-0059): for allow-listed risk templates, also
            // emit an ANONYMIZED signal to Slack/Teams. Best-effort — the publisher
            // never throws and only the PII-free OversightSignal schema is passed, so
            // this can neither leak customer data nor break notification dispatch.
            .call { _ -> publishOversight(req) }
    }

    private fun publishOversight(req: NotificationRequest): Uni<Void> {
        if (!OversightWebhook.isOversight(req.template)) return Uni.createFrom().voidItem()
        val signal = OversightSignal(
            template = req.template,
            primaryChannel = req.channel,
            status = NotificationStatus.PENDING,
            occurredAt = Instant.now(clock),
        )
        // Fan-out to all registered adapters (Slack, Teams, …) sequentially.
        // Each adapter is self-guarded (disabled → no-op, failure → false) so no adapter
        // can break dispatch for the others.
        // Note: @Inject @All Instance<OversightWebhookPublisher> returns empty on Kotlin lateinit-var
        // fields due to a Quarkus ArC quirk; adapters are cached from CDI.current().select() at
        // @PostConstruct time as a workaround (CDI.current().select() finds all 2 adapters correctly).
        val adapters = cdiOversightAdapters
        log.infof("notification.oversight.fanout template=%s adapters=%d", req.template.name, adapters.size)
        val fanOut = adapters.fold(Uni.createFrom().item(false) as Uni<Boolean>) { chain, adapter ->
            chain.flatMap { anyDelivered ->
                adapter.publish(signal).map { delivered -> anyDelivered || delivered }
            }
        }
        return fanOut
            .invoke { anyDelivered ->
                if (anyDelivered) {
                    // Emit audit as a side-effect inside .invoke{} (still on the Vert.x context).
                    // LoggingAuditEventPublisher is a synchronous log write — no I/O, no Vert.x
                    // event-loop involvement. runBlocking here wraps only the `suspend fun publish`
                    // signature; the body returns immediately after Logger.infof(). This is
                    // intentionally NOT used for database- or Kafka-backed publishers; if the
                    // service ever wires a Kafka audit publisher it must switch to a proper
                    // Uni.createFrom().completionStage { coroutineScope.future { audit.publish() } }
                    // pattern. The comment is deliberate: it makes the trade-off visible to reviewers.
                    auditWebhookSent(req)
                }
            }
            .replaceWithVoid()
    }

    /**
     * Emits an audit event for a successful oversight webhook delivery.
     *
     * Calls [AuditEventPublisher.publish] (a `suspend fun`) via [runBlocking] because
     * [publishOversight] operates inside a Mutiny [Uni] chain (not a coroutine context).
     * This is acceptable **only** because the default [com.openbank.libs.audit.LoggingAuditEventPublisher]
     * is a synchronous logger — it never blocks a thread or touches the Vert.x event loop.
     * If a durable Kafka-backed publisher is wired in the future, this call site must be
     * converted to a proper reactive/coroutine pattern.
     */
    private fun auditWebhookSent(req: NotificationRequest) {
        try {
            runBlocking {
                audit.publish(
                    AuditEvent(
                        actorId = "system",
                        actorType = "SYSTEM",
                        operation = "notification.oversight.webhook.sent",
                        resourceType = "notification.oversight",
                        resourceId = req.template.name,
                        result = AuditResult.SUCCESS,
                        payload = mapOf("template" to req.template.name, "channel" to req.channel.name),
                    ),
                )
            }
        } catch (e: Exception) {
            // Audit failure must never break the notification dispatch path.
            log.warnf(e, "notification.oversight.webhook.audit FAILED template=%s", req.template.name)
        }
    }

    private fun sendEmail(
        req: NotificationRequest,
        subject: String,
        body: String,
        entity: NotificationEntity,
    ): Uni<Void> = mailer.send(Mail.withHtml(req.recipient, subject, body))
        .chain { _ ->
            Panache.withTransaction {
                notificationRepo.find("notificationId", entity.notificationId).firstResult()
                    .map { e ->
                        e?.also {
                            it.status = "SENT"
                            it.sentAt = Instant.now(clock)
                        }
                    }
            }
        }
        .onItem().invoke { _ -> log.infof("Email sent: to=%s template=%s", req.recipient, req.template) }
        .onFailure().recoverWithUni { e ->
            log.warnf("Email failed (stub mode): %s", e.message)
            Panache.withTransaction {
                notificationRepo.find("notificationId", entity.notificationId).firstResult()
                    .map { ent -> ent?.also { it.status = "FAILED" } }
            }
        }
        .replaceWithVoid()

    /**
     * Deliver a PUSH notification by fanning out to every ACTIVE device token registered for
     * the party. The inbound request carries no token (the producer does not know it) — the
     * registry is the source of truth. Status is SENT if at least one device accepted the push,
     * FAILED if there were no devices or every send failed. Tokens the provider rejects
     * (UNREGISTERED / BadDeviceToken) are retired so they drop out of future fan-out.
     *
     * Adapters are off by default; a disabled adapter returns a *skipped* (successful) result,
     * so in the sandbox a push is recorded SENT without any egress (mirrors the EMAIL stub).
     */
    private fun sendPush(
        req: NotificationRequest,
        subject: String,
        body: String,
        entity: NotificationEntity,
    ): Uni<Void> {
        val pushText = htmlToPlain(body)
        return Panache.withTransaction { deviceTokenRepo.findActiveByParty(req.partyId) }
            .chain { tokens ->
                if (tokens.isEmpty()) {
                    log.infof("PUSH: no active devices for party=%s template=%s", req.partyId, req.template)
                    return@chain markStatus(entity, "FAILED")
                }
                // Snapshot detached values before crossing the async send boundary — the
                // managed entities belong to the (now closed) read transaction.
                val targets = tokens.map { Triple(it.deviceId, PushPlatform.valueOf(it.platform), it.token) }
                val sends = targets.map { (deviceId, platform, token) ->
                    pushSender.send(
                        PushMessage(platform, token, subject, pushText, mapOf("template" to req.template.name)),
                    ).map { result -> deviceId to result }
                }
                Uni.join().all(sends).andCollectFailures()
                    .chain { results ->
                        val delivered = results.count { it.second.success }
                        val invalidIds = results.filter { it.second.invalidToken }.map { it.first }
                        log.infof(
                            "PUSH fan-out party=%s template=%s devices=%d delivered=%d invalidated=%d",
                            req.partyId,
                            req.template,
                            results.size,
                            delivered,
                            invalidIds.size,
                        )
                        Panache.withTransaction {
                            deviceTokenRepo.invalidate(invalidIds).chain { _ ->
                                notificationRepo.find("notificationId", entity.notificationId).firstResult()
                                    .map { e ->
                                        e?.also {
                                            if (delivered > 0) {
                                                it.status = "SENT"
                                                it.sentAt = Instant.now(clock)
                                            } else {
                                                it.status = "FAILED"
                                            }
                                        }
                                    }
                            }
                        }
                    }
                    .replaceWithVoid()
            }
    }

    private fun markStatus(entity: NotificationEntity, status: String): Uni<Void> = Panache.withTransaction {
        notificationRepo.find("notificationId", entity.notificationId).firstResult()
            .map { e -> e?.also { it.status = status } }
    }.replaceWithVoid()

    /** Strip HTML so the rich email body renders as a plain push alert. */
    private fun htmlToPlain(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun renderTemplate(template: NotificationTemplate, vars: Map<String, String>): Pair<String, String> =
        when (template) {
            NotificationTemplate.ACCOUNT_OPENED ->
                "Your OpenBank account is ready" to
                    "<h2>Welcome to OpenBank!</h2><p>Your account <b>${vars["accountNumber"] ?: ""}</b> has been opened.</p>"
            NotificationTemplate.TRANSACTION_COMPLETED ->
                "Transaction completed" to
                    "<p>Transaction of <b>${vars["amount"] ?: ""} ${vars["currency"] ?: ""}</b> completed successfully.</p>"
            NotificationTemplate.KYC_APPROVED ->
                "Identity verification approved" to
                    "<h2>KYC Approved</h2><p>Your identity has been verified. You can now use all OpenBank services.</p>"
            NotificationTemplate.KYC_REJECTED ->
                "Identity verification failed" to
                    "<h2>KYC Rejected</h2><p>We could not verify your identity. Reason: ${vars["reason"] ?: ""}. Please contact support.</p>"
            NotificationTemplate.OTP_CODE ->
                "Your OpenBank verification code" to
                    "<h2>Verification Code</h2><p>Your code is: <b>${vars["code"] ?: ""}</b>. Valid for 5 minutes.</p>"
            NotificationTemplate.WELCOME ->
                "Welcome to OpenBank" to
                    "<h2>Welcome!</h2><p>Thank you for joining OpenBank, ${vars["name"] ?: ""}.</p>"
            else -> template.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() } to
                "<p>${vars.entries.joinToString("<br>") { "${it.key}: ${it.value}" }}</p>"
        }
}
