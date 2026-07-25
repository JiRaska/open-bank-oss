// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.notification.application.port.out.OversightWebhookPublisher
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.application.port.out.PushSender
import com.openbank.notification.domain.HtmlEscape
import com.openbank.notification.domain.model.NotificationCategory
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationPreferenceRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.Mail
import io.quarkus.mailer.reactive.ReactiveMailer
import io.smallrye.mutiny.Uni
import io.vertx.core.Vertx
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executor

@ApplicationScoped
@Suppress("TooManyFunctions") // one delivery path per channel + shared helpers; grows with channels
class NotificationConsumer {

    companion object {
        /**
         * Generic, PII-free push body (ADR-0135 §3, issue #1182). Lock-screen-visible push
         * payloads must never carry the transaction amount, account number, or any PII — the
         * subject alone (already PII-free, e.g. "Transaction completed") is the title and this
         * fixed string is the body. Full detail is fetched on tap via the authenticated,
         * party-scoped GET /api/v1/notifications/{id}/self.
         */
        const val GENERIC_PUSH_BODY = "Open the OpenBank app to view details."
    }

    @Inject lateinit var mailer: ReactiveMailer

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var notificationRepo: NotificationRepository

    @Inject lateinit var deviceTokenRepo: DeviceTokenRepository

    @Inject lateinit var preferenceRepo: NotificationPreferenceRepository

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
        // Closed variable schema (ADR-0176 D1, issue #1325). A key the template does not declare
        // is rejected here, before it can be rendered into a body and persisted — this is what
        // stops a secret-shaped variable riding an ordinary template into storage, which
        // TemplateSensitivity cannot catch because it classifies templates, not variables.
        //
        // Logs the offending KEYS only, never the values: a rejected payload is exactly the case
        // where a value is most likely to be a secret, and writing it to a log would recreate the
        // leak this rejection exists to close.
        val unknown = req.template.unknownVariables(req.variables)
        if (unknown.isNotEmpty()) {
            log.errorf(
                "Rejected notification: template=%s declares %s but request carried undeclared %s",
                req.template.name,
                req.template.variables.sorted(),
                unknown.sorted(),
            )
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
            it.notificationId = Ids.newId()
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
                    NotificationChannel.PUSH -> maybeSendPush(req, subject, entity)
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

    // Two `.onFailure()` handlers, deliberately at two different points in the chain — not one
    // after both stages (issue #1392, mirroring the identical fix applied to
    // OperatorMessageService.compose() in PR #1368's own code review, which this method
    // predates and was the uncorrected model for). A single trailing handler cannot tell "the
    // mail never went out" from "the mail went out, but recording SENT failed" — Mutiny's
    // Uni#chain composes onto ONE failure channel, so it would catch both: a transient Postgres
    // error AFTER a successful send would silently mark the row FAILED for a message the
    // customer actually received, and the "stub mode" log message conflated the two failure
    // cases into one line.
    private fun sendEmail(
        req: NotificationRequest,
        subject: String,
        body: String,
        entity: NotificationEntity,
    ): Uni<Void> = mailer.send(Mail.withHtml(req.recipient, subject, body))
        .onFailure().invoke { e ->
            log.warnf(e, "Email send failed: to=%s template=%s", req.recipient, req.template)
        }
        .onFailure().recoverWithUni { _ ->
            Panache.withTransaction {
                notificationRepo.find("notificationId", entity.notificationId).firstResult()
                    .map { ent -> ent?.also { it.status = "FAILED" } }
            }.replaceWithVoid()
        }
        .chain { _ ->
            Panache.withTransaction {
                notificationRepo.find("notificationId", entity.notificationId).firstResult()
                    .map { e ->
                        e?.also {
                            it.status = "SENT"
                            it.sentAt = Instant.now(clock)
                        }
                    }
            }.replaceWithVoid()
        }
        .onItem().invoke { _ -> log.infof("Email sent: to=%s template=%s", req.recipient, req.template) }
        // Only reachable if the mail genuinely went out (the FAILED path above already recovered
        // any send failure into a completed Uni). Never marks the row FAILED — that would be a
        // lie about a message that was actually delivered — logs loudly instead, and swallows so
        // dispatch still completes normally: the customer already has the message, this is a
        // bookkeeping problem for an operator to notice via the log, not a reason to fail dispatch.
        .onFailure().invoke { e ->
            log.warnf(
                e,
                "Email sent but recording SENT status failed: to=%s template=%s — row left as-is, " +
                    "NOT marked FAILED (the email was actually delivered)",
                req.recipient,
                req.template,
            )
        }
        .onFailure().recoverWithUni { _ -> Uni.createFrom().voidItem() }

    /**
     * Gate a PUSH by the party's preferences (#2). SECURITY-category notifications (OTP, SCA, KYC,
     * account freeze) always send. For a togglable category, a missing preference row means "on";
     * a muted category records the notification as SUPPRESSED and skips egress.
     */
    private fun maybeSendPush(req: NotificationRequest, subject: String, entity: NotificationEntity): Uni<Void> {
        val category = req.template.category
        if (category == NotificationCategory.SECURITY) return sendPush(req, subject, entity)
        return Panache.withTransaction { preferenceRepo.findByParty(req.partyId) }.chain { pref ->
            val enabled = when (category) {
                NotificationCategory.PAYMENTS -> pref?.paymentsPush ?: true
                NotificationCategory.PRODUCT -> pref?.productPush ?: true
                NotificationCategory.MARKETING -> pref?.marketingPush ?: true
                NotificationCategory.SECURITY -> true
            }
            if (enabled) {
                sendPush(req, subject, entity)
            } else {
                log.infof("PUSH suppressed by preference: party=%s category=%s", req.partyId, category)
                markStatus(entity, "SUPPRESSED")
            }
        }
    }

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
    private fun sendPush(req: NotificationRequest, subject: String, entity: NotificationEntity): Uni<Void> {
        // ADR-0135 §3 + issue #1182: push payloads must carry NO amount / account number / PII.
        // The rendered body (e.g. "Transaction of 1 234,00 EUR completed") used to be flattened
        // with htmlToPlain(body) and shipped as the PushMessage body, landing verbatim in the
        // lock-screen-visible aps.alert.body (ApnsPushSender) / FCM notification — a direct §3
        // violation. We now send only the already-PII-free subject as the title plus a fixed
        // generic wake body. The customer's device fetches the full detail on tap via the
        // authenticated, party-scoped GET /api/v1/notifications/{id}/self.
        val pushText = GENERIC_PUSH_BODY
        // Capture the Vert.x (duplicated) context now, while we are demonstrably on it — the
        // opening findActiveByParty transaction below only works because we are. The push adapter's
        // send completes on the JDK HttpClient's own thread pool, off the event loop
        // (ApnsPushSender), so without hopping back, the trailing status/invalidate transaction
        // runs with "No current Vertx context found" and is silently swallowed (issue #1548).
        val vertxContext = Vertx.currentContext()
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
                    // The sends completed off the Vert.x event loop; hop back onto the captured
                    // context so the Panache.withTransaction below has a context (issue #1548).
                    .emitOn(Executor { command -> vertxContext?.runOnContext { command.run() } ?: command.run() })
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

    /**
     * Renders [template] into a (subject, body) pair.
     *
     * Exhaustive by design — there is deliberately **no `else` branch**. The `else` this replaces
     * dumped every caller-supplied variable into the body (`"$key: $value"`), which is how a
     * secret could ride an ordinary template into storage (issue #1325), and it silently absorbed
     * the seven constants nobody had written a render for: they reached real customers as raw
     * variable dumps. Without the `else`, adding a constant to [NotificationTemplate] is a
     * COMPILE error here until someone writes its copy — a guard that cannot be forgotten, unlike
     * the review the classification allow-list depends on (ADR-0176 D1).
     *
     * Every `vars[...]` key read here must be declared in that constant's [NotificationTemplate.variables];
     * anything else is rejected upstream and can never arrive.
     */
    @Suppress("CyclomaticComplexMethod") // one branch per template — grows with the template catalogue
    private fun renderTemplate(template: NotificationTemplate, vars: Map<String, String>): Pair<String, String> =
        when (template) {
            NotificationTemplate.ACCOUNT_OPENED ->
                "Your OpenBank account is ready" to
                    "<h2>Welcome to OpenBank!</h2><p>Your account <b>${vars.v(
                        "accountNumber",
                    )}</b> has been opened.</p>"
            NotificationTemplate.ACCOUNT_CLOSED ->
                "Your OpenBank account has been closed" to
                    "<h2>Account Closed</h2><p>Your account <b>${vars.v("accountNumber")}</b> has been closed. " +
                    "Statements and transaction history remain available on request.</p>"
            NotificationTemplate.ACCOUNT_FROZEN ->
                "Your OpenBank account has been frozen" to
                    "<h2>Account Frozen</h2><p>Access to your account <b>${vars.v("accountNumber")}</b> has been " +
                    "temporarily suspended. Reason: ${vars.v("reason")}. Please contact support.</p>"
            NotificationTemplate.TRANSACTION_COMPLETED ->
                "Transaction completed" to
                    "<p>Transaction of <b>${vars.v("amount")} ${vars.v("currency")}</b> completed successfully.</p>"
            NotificationTemplate.TRANSACTION_FAILED ->
                "Transaction failed" to
                    "<h2>Transaction Failed</h2><p>Your transaction of <b>${vars.v("amount")} " +
                    "${vars.v("currency")}</b> could not be completed. Reason: ${vars.v("reason")}. " +
                    "No funds have left your account.</p>"
            NotificationTemplate.KYC_APPROVED ->
                "Identity verification approved" to
                    "<h2>KYC Approved</h2><p>Your identity has been verified. You can now use all OpenBank services.</p>"
            NotificationTemplate.KYC_REJECTED ->
                "Identity verification failed" to
                    "<h2>KYC Rejected</h2><p>We could not verify your identity. Reason: ${vars.v(
                        "reason",
                    )}. Please contact support.</p>"
            NotificationTemplate.KYC_DOCUMENT_REQUIRED ->
                "We need a document from you" to
                    "<h2>Document Required</h2><p>To finish verifying your identity we need your " +
                    "<b>${vars.v("documentType")}</b>. You can upload it in the OpenBank app.</p>"
            NotificationTemplate.CONSENT_GRANTED ->
                "Access to your account data was granted" to
                    "<h2>Consent Granted</h2><p>You granted access to your account data " +
                    "(<b>${vars.v("scope")}</b>). You can withdraw this at any time in the OpenBank app.</p>"
            NotificationTemplate.CONSENT_REVOKED ->
                "Access to your account data was withdrawn" to
                    "<h2>Consent Withdrawn</h2><p>Access to your account data " +
                    "(<b>${vars.v("scope")}</b>) has been withdrawn. No further data will be shared under it.</p>"
            NotificationTemplate.OTP_CODE ->
                "Your OpenBank verification code" to
                    "<h2>Verification Code</h2><p>Your code is: <b>${vars.v("code")}</b>. Valid for 5 minutes.</p>"
            NotificationTemplate.PASSWORD_RESET ->
                "Reset your OpenBank password" to
                    "<h2>Password Reset</h2><p>Use the link below to set a new password. It expires in 15 minutes " +
                    "and can be used once. If you did not ask for this, ignore this message and your password stays " +
                    "unchanged.</p><p><a href=\"${vars.v("resetLink")}\">Reset your password</a></p>"
            NotificationTemplate.WELCOME ->
                "Welcome to OpenBank" to
                    "<h2>Welcome!</h2><p>Thank you for joining OpenBank, ${vars.v("name")}.</p>"
            NotificationTemplate.SCA_APPROVAL ->
                "Approve your payment" to
                    "<p>${vars.v("detail").ifBlank { "You have a payment waiting for your approval." }}</p>"
        }
}

/**
 * Reads a declared template variable, HTML-escaped, or "" when the caller omitted it.
 *
 * The schema is closed against **undeclared** keys, not against missing ones (see
 * [NotificationTemplate.variables]): rejecting a partial request would silently drop a real
 * message, since poison payloads are acked. An omitted variable renders empty, as it always has.
 *
 * Escaping happens HERE, not per call site (issue #1382): every one of [renderTemplate]'s ~16
 * reads interpolates straight into an HTML body (or, for `PASSWORD_RESET`'s `resetLink`, an
 * `href="..."` attribute) with zero escaping between a domain-event-supplied variable and the
 * mail actually sent — a `reason`, `documentType`, or `scope` containing markup rendered verbatim
 * in the customer's mail client. Escaping the shared accessor closes every call site in one place
 * instead of relying on each of the 16 to remember it.
 *
 * Top-level rather than a member so `renderTemplate` reads as copy instead of null-handling — the
 * 16 inline `?: ""` reads it replaces were most of that function's cyclomatic complexity.
 */
private fun Map<String, String>.v(key: String): String = HtmlEscape.escape(this[key] ?: "")
