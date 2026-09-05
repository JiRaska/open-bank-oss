// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactDenyReason
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.MarketingCallSite
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.notification.application.port.out.EmailMetricsPort
import com.openbank.notification.application.port.out.NotificationOutboxRepository
import com.openbank.notification.application.port.out.OversightWebhookPublisher
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.application.port.out.PushMetricsPort
import com.openbank.notification.application.port.out.PushSender
import com.openbank.notification.domain.HtmlEscape
import com.openbank.notification.domain.RecipientAddress
import com.openbank.notification.domain.model.EmailSendOutcome
import com.openbank.notification.domain.model.MobileDeepLink
import com.openbank.notification.domain.model.NotificationCategory
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationOutcome
import com.openbank.notification.domain.model.NotificationOutcomeEvent
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushResult
import com.openbank.notification.domain.model.PushSendOutcome
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.client.PartyContactClient
import com.openbank.notification.infrastructure.client.PartyMergeResolver
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationPreferenceRepository
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.Mail
import io.quarkus.mailer.reactive.ReactiveMailer
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import io.vertx.core.Vertx
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.function.Function
import java.util.function.Supplier

@ApplicationScoped
@Suppress("TooManyFunctions") // one delivery path per channel + shared helpers; grows with channels
class NotificationConsumer @Inject constructor(
    /** See the KDoc on the `mailerMocked` declaration site below (issue #4737). */
    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false")
    val mailerMocked: Boolean,
    /**
     * Default-off guard for the reviewed no-device fallback policy (#4363). Enabling it is a
     * separate reviewed GitOps decision: sandbox mail is deliberately mocked, so a false default
     * must never be mistaken for delivery readiness.
     */
    @ConfigProperty(name = "openbank.notification.push-fallback.enabled", defaultValue = "false")
    val pushFallbackEnabled: Boolean,
) {

    companion object {
        /**
         * Generic, PII-free push body (ADR-0135 §3, issue #1182). Lock-screen-visible push
         * payloads must never carry the transaction amount, account number, or any PII — the
         * subject alone (already PII-free, e.g. "Transaction completed") is the title and this
         * fixed string is the body. Full detail is fetched on tap via the authenticated,
         * party-scoped GET /api/v1/notifications/{id}/self.
         */
        const val GENERIC_PUSH_BODY = "Open the OpenBank app to view details."

        /** PII-free fallback e-mail body. Full detail remains behind the authenticated app. */
        const val GENERIC_FALLBACK_EMAIL_BODY =
            "A notification is waiting in the OpenBank app. Open the app to view details."

        /**
         * The consent scope a MARKETING send is checked against, per target channel (ADR-0198 D4).
         * Visible for tests — the gate's correctness is this mapping never drifting per channel.
         */
        fun marketingScopeFor(channel: NotificationChannel): String = when (channel) {
            NotificationChannel.EMAIL -> "MARKETING_COMMS_EMAIL"
            NotificationChannel.PUSH -> "MARKETING_COMMS_PUSH"
        }

        /**
         * Terminal status of a PUSH fan-out from its accepted/skipped tally (ADR-0252 phase 0).
         *
         * Visible for tests, and deliberately a pure function of two numbers: this mapping is the
         * defect. The previous form asked `success > 0`, which is true for a SKIPPED send, so an
         * environment with every push adapter disabled recorded SENT and looked healthy.
         *
         * A fan-out that was only skipped is SUPPRESSED, not FAILED — nothing was rejected and
         * nothing is retryable; the channel is switched off. Merging it into FAILED would put a
         * configuration state into the delivery-failure series and make that series unusable for
         * alerting, which is the mirror image of the bug being fixed.
         */
        fun pushOutcomeOf(accepted: Int, skipped: Int): NotificationOutcome = when {
            accepted > 0 -> NotificationOutcome.SENT
            skipped > 0 -> NotificationOutcome.SUPPRESSED
            else -> NotificationOutcome.FAILED
        }

        /**
         * Terminal status of one EMAIL send from its three-state [EmailSendOutcome] (issue #4737).
         *
         * Visible for tests, and deliberately a pure function of one value: like [pushOutcomeOf],
         * this mapping *is* the defect. The previous form asked only "did the `Uni` fail?", and a
         * mocked `ReactiveMailer.send` does not fail — it completes exactly like a real accept —
         * so an environment with no SMTP recorded `SENT` with `sent_at` for mail that never left
         * the process, and looked healthy from the status column, the outcome stream and the logs
         * alike.
         *
         * `MOCKED` maps to SUPPRESSED, not FAILED, for the reason [pushOutcomeOf] gives: nothing
         * was rejected and nothing is retryable, the channel is switched off. It must also never
         * map to SENT — that is the whole bug, and the sandbox's mock is deliberate (its gitops
         * manifest says so), so the record's honesty has to hold independently of the config.
         */
        fun emailOutcomeOf(outcome: EmailSendOutcome): NotificationOutcome = when (outcome) {
            EmailSendOutcome.ACCEPTED -> NotificationOutcome.SENT
            EmailSendOutcome.MOCKED -> NotificationOutcome.SUPPRESSED
            EmailSendOutcome.FAILED -> NotificationOutcome.FAILED
        }

        /** Reason code accompanying [emailOutcomeOf]; null exactly when the mailer accepted. */
        fun emailReasonOf(outcome: EmailSendOutcome): String? = when (outcome) {
            EmailSendOutcome.ACCEPTED -> null
            EmailSendOutcome.MOCKED -> NotificationOutcomeEvent.REASON_MAILER_MOCKED
            EmailSendOutcome.FAILED -> NotificationOutcomeEvent.REASON_MAILER_REFUSED
        }

        /** Reason code accompanying [pushOutcomeOf]; null exactly when something was accepted. */
        fun pushReasonOf(accepted: Int, skipped: Int): String? = when {
            accepted > 0 -> null
            skipped > 0 -> NotificationOutcomeEvent.REASON_PUSH_ADAPTER_DISABLED
            else -> NotificationOutcomeEvent.REASON_PUSH_REJECTED
        }
    }

    @Inject lateinit var mailer: ReactiveMailer

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var notificationRepo: NotificationRepository

    /**
     * ADR-0239 D2. Field injection, not a constructor parameter: detekt's `LongParameterList`
     * fires AT `constructorThreshold: 9`, and this bean is already at the ceiling — the fleet
     * convention for one more collaborator is a field.
     */
    @Inject lateinit var outboxRepo: NotificationOutboxRepository

    @Inject lateinit var deviceTokenRepo: DeviceTokenRepository

    @Inject lateinit var preferenceRepo: NotificationPreferenceRepository

    @Inject lateinit var pushSender: PushSender

    /**
     * ADR-0252 phase 0. Field injection for the same reason as [outboxRepo] above — detekt's
     * `LongParameterList` fires AT `constructorThreshold: 9` and this bean is at the ceiling.
     */
    @Inject lateinit var pushMetrics: PushMetricsPort

    @Inject lateinit var emailMetrics: EmailMetricsPort

    /**
     * Whether `ReactiveMailer` is the Quarkus mock (issue #4737).
     *
     * Read as configuration rather than inferred from the send result, because there is nothing to
     * infer from: a mocked send completes exactly like a real accept — same `Uni`, no item, no
     * failure, no distinguishing signal anywhere in the reactive chain. The configuration is the
     * only place the difference exists.
     *
     * Deliberately **not** the `@ConfigProperty` + `var x: Boolean = false` field shape its
     * neighbours use (`ApnsPushSender.enabled` and 109 other fleet occurrences, all frozen in
     * `configproperty-kotlin-defaults-baseline.txt`): a Kotlin default generates a synthetic
     * constructor, ArC builds the bean through it, and the annotation is never applied — the field
     * would sit at `false` forever, whatever the environment says. That would have made this
     * entire fix inert in the one deployment that needs it, and silently so, which is the same
     * family of defect as the bug being fixed.
     *
     * `defaultValue = "false"` matches Quarkus's own production default, so a deployment that says
     * nothing about the mailer is treated as a real one; the sandbox sets `QUARKUS_MAILER_MOCK`
     * explicitly.
     */
    // Declared on the primary constructor (see KDoc above) — the one shape that actually applies.

    @Inject lateinit var clock: Clock

    @Inject lateinit var audit: AuditEventPublisher

    @Inject lateinit var contactGate: ContactPolicyGate

    /** Resolves the EMAIL envelope address from `partyId` (issue #3581) — see [resolveEmailRecipient]. */
    @Inject
    @RestClient
    lateinit var partyContactClient: PartyContactClient

    /** Follows the ADR-0179 `merged_into` pointer at dispatch entry (issue #1984) — see [dispatch]. */
    @Inject
    lateinit var partyMergeResolver: PartyMergeResolver

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
        pushMetrics.recordFallbackEnabled(pushFallbackEnabled)
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
        val validDeepLink = req.deepLink == null ||
            (req.channel == NotificationChannel.PUSH && MobileDeepLink.isAllowed(req.deepLink))
        if (!validDeepLink) {
            // The deep link controls device navigation, so it gets the same allow-list posture as
            // template identifiers. Do not log the rejected value; it may be attacker-controlled.
            log.errorf("Rejected notification with non-bank mobile deep-link for template=%s", req.template.name)
            return Uni.createFrom().voidItem()
        }
        return dispatch(req)
            .onFailure().invoke { e ->
                // #5745 (sweep of #5698): a processing failure (e.g. transient DB error) used to be
                // logged and ACKED here — "redelivery is safe" was never true, because acking is
                // exactly what tells Kafka NOT to redeliver. An acked message and a successfully
                // handled one are indistinguishable from outside, so the notification itself (a
                // transactional or SECURITY-category send, not only marketing) was silently lost.
                //
                // Deliberately NOT wrapped in EventRetry's bounded in-process retry, unlike
                // PartyErasureConsumer's idempotent deletes: dispatchResolved() mints a fresh
                // notificationId and persists a NEW row on every call, so retrying this Uni from
                // the top after a failure that occurred AFTER that persist (e.g. in sendEmail)
                // would insert a second row and could re-send — trading a lost notification for a
                // duplicated one. A single attempt, then rethrow, hands the decision to the
                // connector's own failure-strategy (dead-letter-queue, application.yaml) instead.
                log.errorf(e, "Failed to process notification — rethrowing so it is not acked: %s", payload)
            }
    }

    /**
     * Resolves `req.partyId` through [PartyMergeResolver] before anything else runs (issue #1984
     * fleet sweep — ADR-0179 consumer adoption). This is the identity chokepoint: persistence,
     * the preference check, the device-token fan-out and the EMAIL address lookup all read
     * `partyId` off the request that reaches [dispatchResolved], so resolving once here means none
     * of them need their own adoption. A request for a since-merged party is redirected to the
     * survivor; an unaffected request pays one resolver call that is almost always a cache hit
     * (see [PartyMergeResolver]).
     */
    private fun dispatch(req: NotificationRequest): Uni<Void> =
        partyMergeResolver.resolve(req.partyId).chain { resolved ->
            dispatchResolved(if (resolved == req.partyId) req else req.copy(partyId = resolved))
        }

    private fun dispatchResolved(req: NotificationRequest): Uni<Void> {
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
            it.correlationId = req.correlationId
            it.status = "PENDING"
            it.createdAt = Instant.now(clock)
        }
        return Panache.withTransaction { notificationRepo.persist(entity) }
            .chain { _ ->
                // Consent gate BEFORE the channel dispatch (ADR-0198 D4, issue #2369). Deliberately
                // NOT a per-channel check: the defect the issue names is precisely that gating lived
                // inside the channel branches, so PUSH got a (default-true) check and EMAIL got none,
                // and any channel added later would inherit whichever the author remembered. One gate
                // ahead of the `when` cannot be forgotten by a new branch.
                if (req.template.category == NotificationCategory.MARKETING) {
                    gateMarketingOnConsent(req, subject, body, entity)
                } else {
                    when (req.channel) {
                        NotificationChannel.EMAIL -> sendEmail(req, subject, body, entity)
                        NotificationChannel.PUSH -> maybeSendPush(req, subject, entity)
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

    // Two `.onFailure()` handlers, deliberately at two different points in the chain — not one
    // after both stages (issue #1392, mirroring the identical fix applied to
    // OperatorMessageService.compose() in PR #1368's own code review, which this method
    // predates and was the uncorrected model for). A single trailing handler cannot tell "the
    // mail never went out" from "the mail went out, but recording SENT failed" — Mutiny's
    // Uni#chain composes onto ONE failure channel, so it would catch both: a transient Postgres
    /**
     * The ADR-0219 D4 contact gate (#2749) — this service's own `consentServiceClient` call was
     * the choke point ADR-0219 D4 names outright ("its consent call becomes this gate call"): one
     * `ContactPolicyGate.check(...)` now wraps suppression list -> send cap -> quiet hours ->
     * live consent pull, in the gate's ordering (same composition campaign-service and
     * engagement-service already reuse), instead of a bespoke consent-only check.
     *
     * Bridged into this `Uni` chain via `CoroutineScope(Dispatchers.Unconfined).async {
     * }.asUni()` — the same suspend-into-Uni idiom `CampaignJourneyActivitiesImpl` uses (the
     * gate itself is a `suspend fun`; this dispatch pipeline is Mutiny `Uni`, not coroutines).
     *
     * Fail-closed in every deny reason, deliberately distinguishable in the audit (#2660 §3,
     * carried forward): `NO_CONSENT` -> `no_active_consent` (a genuine refusal),
     * `GATE_UNAVAILABLE` -> `consent_check_unavailable` (a port outage — consent, counter or
     * suppression) — a gate outage must never read as a grant, and the two numbers must never
     * merge into one. `SEND_CAP_REACHED`/`QUIET_HOURS`/`SUPPRESSED_LIST` are new reasons this
     * service could not previously produce; each gets its own outcome code rather than folding
     * into one of the two above.
     *
     * The check is per send, never cached (ADR-0198). `notification_preference`'s columns
     * (`payments_push` / `product_push` / `marketing_push`) remain a per-channel mute *within* a
     * granted consent — a different and legitimate control, not the Art. 6(1)(a) basis.
     */
    @MarketingCallSite
    private fun gateMarketingOnConsent(
        req: NotificationRequest,
        subject: String,
        body: String,
        entity: NotificationEntity,
    ): Uni<Void> {
        val scope = marketingScopeFor(req.channel)
        val decisionUni = CoroutineScope(Dispatchers.Unconfined).async {
            contactGate.check(req.partyId, ContactClass.OUTBOUND_SEND, scope)
        }.asUni()
        return decisionUni.chain { decision ->
            if (decision.allowed) {
                when (req.channel) {
                    NotificationChannel.EMAIL -> sendEmail(req, subject, body, entity)
                    NotificationChannel.PUSH -> maybeSendPush(req, subject, entity)
                }
            } else {
                val reason = when (decision.denyReason) {
                    ContactDenyReason.NO_CONSENT -> NotificationOutcomeEvent.REASON_NO_CONSENT
                    ContactDenyReason.GATE_UNAVAILABLE -> NotificationOutcomeEvent.REASON_CONSENT_UNAVAILABLE
                    ContactDenyReason.SEND_CAP_REACHED -> NotificationOutcomeEvent.REASON_SEND_CAP_REACHED
                    ContactDenyReason.QUIET_HOURS -> NotificationOutcomeEvent.REASON_QUIET_HOURS
                    ContactDenyReason.SUPPRESSED_LIST -> NotificationOutcomeEvent.REASON_SUPPRESSED_LIST
                    // IMPRESSION_BUDGET_REACHED, null: never produced for OUTBOUND_SEND — the
                    // gate's own `when` is exhaustive over ContactClass, so this branch exists
                    // only for a future ContactDenyReason value this file has not been taught yet.
                    else -> NotificationOutcomeEvent.REASON_CONSENT_UNAVAILABLE
                }
                log.infof(
                    "MARKETING %s suppressed: %s (template=%s party=%s, ADR-0219 D4)",
                    req.channel,
                    reason,
                    req.template,
                    req.partyId,
                )
                markStatus(req, entity, NotificationOutcome.SUPPRESSED, reason)
                    .invoke { _ -> auditMarketingSuppressed(req, reason) }
            }
        }
    }

    /**
     * Audit trail for a suppressed marketing send. Same `runBlocking` trade-off, and the same
     * constraint, as [auditWebhookSent] — safe only while the wired publisher is the synchronous
     * [com.openbank.libs.audit.LoggingAuditEventPublisher].
     */
    private fun auditMarketingSuppressed(req: NotificationRequest, reason: String) {
        try {
            runBlocking {
                audit.publish(
                    AuditEvent(
                        actorId = "system",
                        actorType = "SYSTEM",
                        operation = "notification.marketing.suppressed",
                        resourceType = "notification",
                        resourceId = req.template.name,
                        result = AuditResult.DENIED,
                        payload = mapOf(
                            "template" to req.template.name,
                            "channel" to req.channel.name,
                            "partyId" to req.partyId.toString(),
                            "reason" to reason,
                        ),
                    ),
                )
            }
        } catch (e: Exception) {
            // An audit failure must not swallow the suppression itself — the notification stays
            // SUPPRESSED either way, which is the safe outcome. Logged so the gap is visible.
            log.warnf(e, "Failed to audit marketing suppression for template=%s", req.template)
        }
    }

    /**
     * Resolves the EMAIL envelope address, then delivers (issue #3581).
     *
     * Every producer on this topic puts the **party id** in `recipient` — campaign-service,
     * account-service and sca-service all do — and nothing ever resolved it, so the UUID went to
     * `Mail.withHtml(...)` verbatim and the mail could not be delivered. PUSH never had the bug
     * because it resolves its destination from `partyId` in the device-token registry; EMAIL now
     * does the same, here, rather than in each caller.
     *
     * Fail CLOSED: an unresolvable address records FAILED and sends nothing. The alternative —
     * handing the mailer whatever arrived — is what produced a permanently green send funnel over
     * mail that never left.
     */
    private fun sendEmail(
        req: NotificationRequest,
        subject: String,
        body: String,
        entity: NotificationEntity,
    ): Uni<Void> = resolveEmailRecipient(req).chain { address ->
        if (address.isEmpty()) {
            // The party id is logged, never a candidate address — an unresolved recipient is
            // exactly where a malformed value would be, and it is still customer data.
            log.errorf(
                "EMAIL not sent: no deliverable address for party=%s template=%s — recorded FAILED (#3581)",
                req.partyId,
                req.template,
            )
            markStatus(req, entity, NotificationOutcome.FAILED, NotificationOutcomeEvent.REASON_NO_RECIPIENT)
        } else {
            deliverEmail(req, address, subject, body, entity)
        }
    }

    /**
     * The supplied `recipient` when it already is an address, otherwise party-service's.
     *
     * Emits `""` for "not resolvable", never a null item: a `Uni` carrying null is the shape that
     * bites callers of `Uni.createFrom().item(...)` in this codebase, and the caller's only
     * question is deliverable-or-not. The lookup result is never cached — an address the customer
     * changed is precisely what a cache would get wrong — and a party-service outage resolves to
     * "not deliverable", so an outage suppresses rather than sends to a UUID.
     */
    private fun resolveEmailRecipient(req: NotificationRequest): Uni<String> =
        if (RecipientAddress.isEmailAddress(req.recipient)) {
            Uni.createFrom().item(req.recipient.trim())
        } else {
            partyContactClient.getParty(req.partyId)
                .map { party -> party.email?.trim()?.takeIf { RecipientAddress.isEmailAddress(it) } ?: "" }
                .onFailure().recoverWithItem { e ->
                    log.warnf(e, "Party lookup failed for party=%s — EMAIL not deliverable (#3581)", req.partyId)
                    ""
                }
        }

    /**
     * Hand one rendered mail to the mailer and record what actually happened (issue #4737).
     *
     * Terminal status comes from the three-state [EmailSendOutcome], not from "did the `Uni`
     * fail?":
     * - **SENT** — the mailer accepted the message. Accepted, not delivered: an SMTP accept is a
     *   handoff to a relay and a later bounce can still refine it (ADR-0239 D4 `BOUNCED`).
     * - **SUPPRESSED** / `mailer_mocked` — `quarkus.mailer.mock=true`, so nothing left the
     *   process. This used to be SENT *with `sent_at` populated*: the mock completes successfully,
     *   the code asked only whether the call threw, and so a deployment with no SMTP produced
     *   byte-identical status and telemetry to a working one. Exactly the `PushResult.skipped()`
     *   defect (ADR-0252 phase 0) on the channel #4363 is considering re-routing *to* — and that
     *   one was found by a customer, not by any signal.
     * - **FAILED** / `mailer_refused` — the mailer rejected the message or the call failed.
     *
     * The deployed sandbox mocks the mailer deliberately, and that stays true; what changes is
     * that the record now says so. A configuration choice must not be able to make the database
     * assert a delivery that never occurred.
     */
    private fun deliverEmail(
        req: NotificationRequest,
        recipient: String,
        subject: String,
        body: String,
        entity: NotificationEntity,
    ): Uni<Void> = mailer.send(Mail.withHtml(recipient, subject, body))
        // One branch point, deliberately, rather than two `.onFailure()` handlers at different
        // depths (the older shape, issue #1392). The distinction that shape existed to preserve —
        // "the mail never went out" vs "the mail went out but recording it failed" — is kept, and
        // is now visible as two branches instead of two positions in a chain.
        .onItemOrFailure().transformToUni { _, failure ->
            // Three states, not two (issue #4737). A mocked mailer completes with no failure, so
            // `failure == null` on its own means "the call did not throw", never "the mail left".
            val sendOutcome = when {
                failure != null -> EmailSendOutcome.FAILED
                mailerMocked -> EmailSendOutcome.MOCKED
                else -> EmailSendOutcome.ACCEPTED
            }
            emailMetrics.recordSend(req.template, sendOutcome)
            if (sendOutcome != EmailSendOutcome.ACCEPTED) {
                if (failure != null) {
                    log.warnf(failure, "Email send failed: party=%s template=%s", req.partyId, req.template)
                } else {
                    // Not a warning: the sandbox mocks the mailer on purpose. Logged at INFO so the
                    // no-op is greppable, and counted as MOCKED so it is alertable — a log line is
                    // not a signal anyone watches, which is how the push channel's identical
                    // no-op went unnoticed until a customer reported it.
                    log.infof(
                        "Email NOT sent: mailer is mocked (quarkus.mailer.mock=true) — party=%s " +
                            "template=%s recorded SUPPRESSED/%s, never SENT (#4737)",
                        req.partyId,
                        req.template,
                        NotificationOutcomeEvent.REASON_MAILER_MOCKED,
                    )
                }
                // sent = false, so `sent_at` stays NULL: the column means "when did this leave the
                // process", and nothing left it.
                markStatus(req, entity, emailOutcomeOf(sendOutcome), emailReasonOf(sendOutcome))
            } else {
                markStatus(req, entity, NotificationOutcome.SENT, reason = null, sent = true)
                    .onItem().invoke { _ ->
                        log.infof("Email sent: party=%s template=%s", req.partyId, req.template)
                    }
                    // Reachable only when the mail genuinely went out. Never marks the row FAILED —
                    // that would be a lie about a message the customer already has — logs loudly
                    // instead and swallows, so dispatch still completes. The cost is now explicit:
                    // the outcome event is lost with the status write (they share one transaction,
                    // ADR-0239 D2), so the producer's row stays PENDING rather than going wrong.
                    .onFailure().invoke { e ->
                        log.warnf(
                            e,
                            "Email sent but recording SENT status failed: party=%s template=%s — row " +
                                "left as-is, NOT marked FAILED (the email was actually delivered); no " +
                                "outcome event was emitted either",
                            req.partyId,
                            req.template,
                        )
                    }
                    .onFailure().recoverWithUni { _ -> Uni.createFrom().voidItem() }
            }
        }

    /**
     * Gate a PUSH by the party's preferences (#2). SECURITY-category notifications (OTP, SCA, KYC,
     * account freeze) always send. For a togglable category, a missing preference row means "on";
     * a muted category records the notification as SUPPRESSED and skips egress.
     *
     * MARKETING never reaches here — [suppressUnconsentedMarketing] catches it ahead of the channel
     * dispatch. The branch below stays for `when` exhaustiveness and is deliberately fail-CLOSED
     * (`?: false`) as defence in depth: `marketing_push` is a per-channel mute *within* a granted
     * consent, not the GDPR Art. 6(1)(a) record (consent-service owns that,
     * `party-service:marketing-comms`, ADR-0205 D3), so it must never be the thing that decides a
     * marketing send. It previously defaulted to `true`, which would have sent marketing to a party
     * with no preference row at all.
     */
    private fun maybeSendPush(req: NotificationRequest, subject: String, entity: NotificationEntity): Uni<Void> {
        val category = req.template.category
        if (category == NotificationCategory.SECURITY) return sendPush(req, subject, entity)
        return Panache.withTransaction { preferenceRepo.findByParty(req.partyId) }.chain { pref ->
            val enabled = when (category) {
                NotificationCategory.PAYMENTS -> pref?.paymentsPush ?: true
                NotificationCategory.PRODUCT -> pref?.productPush ?: true
                // Fail-closed: absent preference row => do NOT send (see KDoc above).
                NotificationCategory.MARKETING -> pref?.marketingPush ?: false
                NotificationCategory.SECURITY -> true
            }
            if (enabled) {
                sendPush(req, subject, entity)
            } else {
                log.infof("PUSH suppressed by preference: party=%s category=%s", req.partyId, category)
                markStatus(
                    req,
                    entity,
                    NotificationOutcome.SUPPRESSED,
                    NotificationOutcomeEvent.REASON_PREFERENCE_MUTED,
                )
            }
        }
    }

    /**
     * Deliver a PUSH notification by fanning out to every ACTIVE device token registered for
     * the party. The inbound request carries no token (the producer does not know it) — the
     * registry is the source of truth. Tokens the provider rejects (UNREGISTERED /
     * BadDeviceToken) are retired so they drop out of future fan-out.
     *
     * Terminal status, from the three-state [PushSendOutcome] rather than from `success`:
     * - **SENT** — at least one provider ACCEPTED the push. Accepted, not delivered: APNs returns
     *   HTTP 200 to mean accepted for delivery and issues no receipt, so this process cannot
     *   observe whether a device received anything. Device-side acknowledgement is ADR-0252
     *   phase 3 (#4348).
     * - **SUPPRESSED** / `push_adapter_disabled` — nothing was accepted and at least one send was
     *   skipped because the adapter is off. This used to be SENT: a skipped result is
     *   `success = true`, the fan-out counted `success`, and so an environment with no APNs
     *   credentials produced byte-identical telemetry and status to a working one. That is half
     *   the reason a dead push channel went unnoticed until a customer reported it.
     * - **FAILED** — no devices, or every send was rejected.
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
                    pushMetrics.recordFanOut(req.template, NotificationOutcome.FAILED, 0)
                    return@chain rerouteNoDevice(req, subject, entity)
                }
                // Snapshot detached values before crossing the async send boundary — the
                // managed entities belong to the (now closed) read transaction.
                val targets = tokens.map { Triple(it.deviceId, PushPlatform.valueOf(it.platform), it.token) }
                val sends = targets.map { (deviceId, platform, token) ->
                    pushSender.send(
                        PushMessage(platform, token, subject, pushText, pushData(req, entity)),
                    ).map { result ->
                        // Recorded here, where the platform is in scope. Counter increments are
                        // thread-safe, so running off the event loop (the adapters complete on the
                        // JDK HttpClient pool) is fine — unlike the Panache work further down.
                        pushMetrics.recordSend(platform, result.outcome, result.errorCode)
                        deviceId to result
                    }
                }
                Uni.join().all(sends).andCollectFailures()
                    // The sends completed off the Vert.x event loop; hop back onto the captured
                    // context so the Panache.withTransaction below has a context (issue #1548).
                    .emitOn(Executor { command -> vertxContext?.runOnContext { command.run() } ?: command.run() })
                    .chain { results -> persistPushFanOut(req, entity, results) }
                    .replaceWithVoid()
            }
    }

    /**
     * Keep the original row truthful (`FAILED` / no active device) and publish the more precise
     * REROUTED outcome and fresh EMAIL request commit in one transaction. The fallback has a new
     * row and its own terminal outcome, so neither row claims that a different channel delivered
     * it. In particular, a crash cannot publish REROUTED without a durable fallback request.
     */
    private fun rerouteNoDevice(req: NotificationRequest, subject: String, entity: NotificationEntity): Uni<Void> {
        val fallback = req.template.noDeviceFallbackChannel
        if (!pushFallbackEnabled || fallback == null) {
            return markStatus(req, entity, NotificationOutcome.FAILED, NotificationOutcomeEvent.REASON_NO_DEVICE)
        }
        val fallbackRequest = req.copy(
            channel = fallback,
            deepLink = null,
            interactionRef = null,
        )
        val fallbackEntity = fallbackNotificationEntity(fallbackRequest, subject)
        return persistReroute(req, entity, fallbackEntity)
            .invoke(
                Runnable {
                    pushMetrics.recordFallbackRouted(
                        req.template,
                        NotificationChannel.PUSH,
                        NotificationChannel.EMAIL,
                        NotificationOutcome.REROUTED,
                    )
                },
            )
            .chain(Supplier { sendEmail(fallbackRequest, subject, GENERIC_FALLBACK_EMAIL_BODY, fallbackEntity) })
    }

    /** Builds the separate generic EMAIL notification persisted with the original reroute evidence. */
    private fun fallbackNotificationEntity(req: NotificationRequest, subject: String): NotificationEntity =
        NotificationEntity().also { entity ->
            entity.notificationId = Ids.newId()
            entity.partyId = req.partyId
            entity.channel = NotificationChannel.EMAIL.name
            entity.template = req.template.name
            entity.recipient = req.recipient
            entity.subject = subject
            entity.body = GENERIC_FALLBACK_EMAIL_BODY
            entity.correlationId = req.correlationId
            entity.status = "PENDING"
            entity.createdAt = Instant.now(clock)
        }

    /** Atomically records the original failed PUSH outcome, its REROUTED evidence, and the fallback request. */
    private fun persistReroute(
        req: NotificationRequest,
        original: NotificationEntity,
        fallback: NotificationEntity,
    ): Uni<Void> = Panache.withTransaction {
        notificationRepo.find("notificationId", original.notificationId).firstResult()
            .chain(
                Function { persisted: NotificationEntity? ->
                    if (persisted == null) {
                        reportMissingRow(req, original, NotificationOutcome.REROUTED)
                        Uni.createFrom().failure<Void>(IllegalStateException("notification row missing during reroute"))
                    } else {
                        persisted.status = NotificationStatus.FAILED.name
                        persisted.failureReason = NotificationOutcomeEvent.REASON_REROUTED_NO_DEVICE
                        outboxRepo.persistInTransaction(
                            outcomeMessage(
                                req,
                                original,
                                NotificationOutcome.REROUTED,
                                NotificationOutcomeEvent.REASON_REROUTED_NO_DEVICE,
                            ),
                        ).chain(Supplier { notificationRepo.persist(fallback) })
                    }
                },
            )
    }.replaceWithVoid()

    /**
     * FCM/APNs data is a routing envelope, not customer content. `notificationId` lets an app
     * fetch the authenticated full detail after a generic wake-up; `deepLink` is only admitted by
     * [MobileDeepLink] before this method runs. Neither carries balances, names, offers or other
     * lock-screen-visible material.
     */
    private fun pushData(req: NotificationRequest, entity: NotificationEntity): Map<String, String> = buildMap {
        put("template", req.template.name)
        put("notificationId", entity.notificationId.toString())
        req.deepLink?.let { put("deepLink", it) }
        req.interactionRef?.let { put("interactionRef", it.toString()) }
    }

    /**
     * Tally one PUSH fan-out, then commit its status and outcome event in a single transaction.
     *
     * Split out of [sendPush] to keep that method under detekt's `LongMethod` ceiling; it runs on
     * the Vert.x context [sendPush] hopped back onto, which is what lets the Panache work below
     * function at all (issue #1548).
     */
    private fun persistPushFanOut(
        req: NotificationRequest,
        entity: NotificationEntity,
        results: List<Pair<UUID, PushResult>>,
    ): Uni<*> {
        // `accepted`, not `delivered`, and it excludes SKIPPED — counting `success` here merged
        // "the provider took it" with "the adapter is off" (ADR-0252 phase 0).
        val accepted = results.count { it.second.outcome == PushSendOutcome.ACCEPTED }
        val skipped = results.count { it.second.outcome == PushSendOutcome.SKIPPED }
        val invalidIds = results.filter { it.second.invalidToken }.map { it.first }
        log.infof(
            "PUSH fan-out party=%s template=%s devices=%d accepted=%d skipped=%d invalidated=%d",
            req.partyId,
            req.template,
            results.size,
            accepted,
            skipped,
            invalidIds.size,
        )
        val outcome = pushOutcomeOf(accepted, skipped)
        val reason = pushReasonOf(accepted, skipped)
        pushMetrics.recordFanOut(req.template, outcome, results.size)
        return Panache.withTransaction {
            deviceTokenRepo.invalidate(invalidIds).chain { _ ->
                notificationRepo.find("notificationId", entity.notificationId).firstResult()
                    .map { e ->
                        if (e == null) reportMissingRow(req, entity, outcome)
                        e?.also {
                            it.status = outcome.name
                            // ADR-0252's counters answer "how is the channel doing"; this answers
                            // "why did THIS message fail", durably. The outcome event carries the
                            // same value but its outbox row is pruned after dispatch.
                            it.failureReason = reason
                            if (accepted > 0) it.sentAt = Instant.now(clock)
                        }
                    }
                    .chain { _ ->
                        outboxRepo.persistInTransaction(outcomeMessage(req, entity, outcome, reason))
                    }
            }
        }
    }

    /**
     * Write the terminal status AND its outcome event in ONE transaction (ADR-0003, ADR-0239 D2).
     *
     * The two must commit together or not at all. A status written without its event leaves the
     * producer's funnel permanently wrong about a message — which is issue #3663 in miniature — and
     * an event without the status would announce a transition that never happened.
     */
    private fun markStatus(
        req: NotificationRequest,
        entity: NotificationEntity,
        status: NotificationOutcome,
        reason: String?,
        sent: Boolean = false,
        persistedStatus: String = status.name,
    ): Uni<Void> = Panache.withTransaction {
        notificationRepo.find("notificationId", entity.notificationId).firstResult()
            .map { e ->
                if (e == null) reportMissingRow(req, entity, status)
                e?.also {
                    it.status = persistedStatus
                    // Persist the reason alongside the status (V13): the outcome event below
                    // carries the same value, but its outbox row is pruned after dispatch, so
                    // without this the table can only ever say FAILED.
                    it.failureReason = reason
                    if (sent) it.sentAt = Instant.now(clock)
                }
            }
            .chain { _ -> outboxRepo.persistInTransaction(outcomeMessage(req, entity, status, reason)) }
    }.replaceWithVoid()

    /**
     * The row a terminal transition was supposed to land on is not there (issue #4512).
     *
     * Both status writers locate the row by `notificationId` and apply the transition through a
     * null-safe `?.also { ... }`, then commit the outcome event in the same transaction whether or
     * not a row was found. That was silent by construction: the outbox row commits, the event is
     * published, the message is acked, and no branch anywhere logs. On 2026-08-09 four
     * `SCA_APPROVAL` PUSH fan-outs ran that way — four `NotificationOutcome` rows in
     * `notification_outbox`, zero `notifications` rows, and not one error line in the pod.
     *
     * This does not repair the row; by the time it runs the insert is already lost, and inventing
     * one here would fabricate a `created_at` and a body the service no longer holds. What it does
     * is stop the state from being unobservable — an ERROR with the identifiers needed to
     * reconstruct the message, and a counter an alert can read. Deliberately loud: an outcome
     * event announcing a notification that has no durable record is an evidence gap under DORA
     * Art. 17-19, not a housekeeping detail.
     */
    private fun reportMissingRow(req: NotificationRequest, entity: NotificationEntity, outcome: NotificationOutcome) {
        pushMetrics.recordMissingRow(req.channel, req.template)
        log.errorf(
            "notification.status.row_missing notificationId=%s party=%s channel=%s template=%s outcome=%s " +
                "— the outcome event will be published for a notification that has no row",
            entity.notificationId,
            req.partyId,
            req.channel,
            req.template,
            outcome,
        )
    }

    /**
     * The outbox row for one terminal transition.
     *
     * `aggregateId` is the **notificationId**, not the correlation id: it is what partitions the
     * topic (`OutboxKafkaHeaders.partitionKey`), and a correlation id is optional, so keying on it
     * would put every uncorrelated event on one partition.
     */
    private fun outcomeMessage(
        req: NotificationRequest,
        entity: NotificationEntity,
        outcome: NotificationOutcome,
        reason: String?,
    ): OutboxMessage {
        val now = Instant.now(clock)
        val event = NotificationOutcomeEvent(
            notificationId = entity.notificationId,
            correlationId = req.correlationId,
            partyId = req.partyId,
            channel = req.channel,
            template = req.template,
            outcome = outcome,
            reason = reason,
            occurredAt = now,
        )
        return OutboxMessage(
            eventId = Ids.newId(),
            aggregateId = entity.notificationId,
            eventType = NotificationOutcomeEvent.EVENT_TYPE,
            payload = objectMapper.writeValueAsString(event),
            createdAt = now,
        )
    }

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
    // one branch per template — grows with the catalogue; each branch stays a two-line render
    @Suppress("CyclomaticComplexMethod", "LongMethod")
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
            NotificationTemplate.MARKETING_PRODUCT_OFFER ->
                vars.v("offerTitle") to
                    "<h2>${vars.v("offerTitle")}</h2><p>${vars.v("offerText")}</p>" +
                    "<p><b>${vars.v("ctaText")}</b></p>" +
                    "<p style=\"font-size:small;color:#666\">You are receiving this because you opted in to " +
                    "marketing emails. Manage your preferences in the app.</p>"
            NotificationTemplate.DELEGATION_OFFERED ->
                "You have a delegated access offer to review" to
                    "<h2>Delegated Access Offer</h2><p>Someone has offered you delegated access to their " +
                    "<b>${vars.v("resourceType")}</b>. Open the OpenBank app to accept or decline.</p>"
            NotificationTemplate.DELEGATION_ACCEPTED ->
                "Your delegated access offer was accepted" to
                    "<h2>Offer Accepted</h2><p>Your delegated access offer for your " +
                    "<b>${vars.v("resourceType")}</b> was accepted and is now active.</p>"
            NotificationTemplate.DELEGATION_DECLINED ->
                "Your delegated access offer was declined" to
                    "<h2>Offer Declined</h2><p>Your delegated access offer for your " +
                    "<b>${vars.v("resourceType")}</b> was declined. No access was granted.</p>"
            NotificationTemplate.DELEGATION_REVOKED ->
                "Your delegated access was revoked" to
                    "<h2>Access Revoked</h2><p>Delegated access to a <b>${vars.v("resourceType")}</b> " +
                    "granted to you has been revoked. You can no longer act on it.</p>"
            NotificationTemplate.DELEGATION_SUSPENDED ->
                "Delegated access was suspended" to
                    "<h2>Access Suspended</h2><p>Delegated access for a <b>${vars.v("resourceType")}</b> " +
                    "was temporarily suspended by the bank. It cannot be used while suspended.</p>"
            NotificationTemplate.DELEGATION_REINSTATED ->
                "Delegated access was restored" to
                    "<h2>Access Restored</h2><p>Delegated access for a <b>${vars.v("resourceType")}</b> " +
                    "was restored by the bank and may be used again within its existing scope and conditions.</p>"
            NotificationTemplate.DELEGATION_RENOUNCED ->
                "Delegated access was renounced" to
                    "<h2>Access Renounced</h2><p>The person who held delegated access to your " +
                    "<b>${vars.v("resourceType")}</b> ended that access. It is no longer active.</p>"
            NotificationTemplate.DELEGATION_EXPIRED ->
                "A delegated access grant has expired" to
                    "<h2>Grant Expired</h2><p>A delegated access grant for a <b>${vars.v("resourceType")}</b> " +
                    "has reached the end of its validity period and is no longer active.</p>"
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
