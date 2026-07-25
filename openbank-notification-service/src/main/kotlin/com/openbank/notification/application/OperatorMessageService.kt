// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.notification.domain.HtmlEscape
import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.Mail
import io.quarkus.mailer.reactive.ReactiveMailer
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Thrown for a request `opsmessage.compose` cannot fulfil; the resource maps this to 400. */
class OperatorMessageRejected(message: String) : RuntimeException(message)

/**
 * The `opsmessage.compose` write path (ADR-0176 D2/D5) — separate from [NotificationConsumer],
 * which owns system-triggered delivery from `openbank.notification.requests`. Kept apart
 * deliberately: [NotificationConsumer.dispatch] is typed to the system [com.openbank.notification
 * .domain.model.NotificationTemplate] enum and built for at-least-once Kafka redelivery, neither of
 * which fits a synchronous, four-eyes-gated operator action. Sharing storage (`NotificationEntity`
 * / `NotificationRepository`) is enough to give operator messages the same read path (the admin-ui
 * history tab, `GET /api/v1/notifications`) without sharing the dispatch machinery.
 *
 * EMAIL only, for now. PUSH is refused: `sendPush` puts rendered content in the APNs/FCM payload,
 * which ADR-0135 §3 forbids (issue #1182, unresolved, blocked on the customer app's fetch-on-tap
 * handling in another repository) — adding a second producer onto that broken path would make it
 * worse, not better. SMS and IN_APP are not options at all — issue #2372 removed them from
 * [com.openbank.notification.domain.model.NotificationChannel] because neither ever delivered
 * anything (both were logging stubs that reported success). Real multi-channel delivery is the
 * remaining ADR-0176 D2/D3 work.
 *
 * **`partyId` is trusted, unvalidated, caller-supplied input (issue #1384, explicit decision, not
 * an oversight).** Every other write into `notifications.party_id` originates from an
 * authenticated internal domain event; this endpoint is the first to accept it directly from an
 * operator. Validating it against `party-service` would need a new outbound REST client this
 * service does not otherwise have, for a namespace (`opsmessage.*`) that is already four-eyes
 * gated end to end — the checker reviewing a pending approval sees the same `partyId` the maker
 * submitted. Documented here so the audit-trail implication is explicit rather than silent: an
 * operator can send a real email while attaching it to an arbitrary or mistyped `partyId`, which
 * would misattribute the notification row without failing the request. Revisit if/when this
 * service gains a party-service client for another reason.
 */
@ApplicationScoped
class OperatorMessageService {

    private val log = Logger.getLogger(OperatorMessageService::class.java)

    @Inject
    lateinit var notificationRepo: NotificationRepository

    @Inject
    lateinit var mailer: ReactiveMailer

    @Inject
    lateinit var clock: Clock

    suspend fun compose(request: OperatorMessageRequest): UUID {
        validateRequest(request)

        val (subject, body) = render(request.template, request.variables)
        // notificationId is the entity's durable, indexed identifier (ADR-0106) — minted via
        // Ids, a UUIDv7 generator, not a plain random UUID. Matches NotificationConsumer's
        // durable notification identifiers.
        val notificationId = Ids.newId()
        val entity = NotificationEntity().also {
            it.notificationId = notificationId
            it.partyId = request.partyId
            it.channel = "EMAIL"
            it.template = request.template.name
            it.recipient = request.recipient
            it.subject = subject
            it.body = body
            it.status = "PENDING"
            it.createdAt = Instant.now(clock)
        }
        Panache.withTransaction { notificationRepo.persist(entity) }.awaitSuspending()

        // Two `.onFailure()` handlers, deliberately at two different points in the chain — not
        // one after both stages (code-review finding, PR #1368). A single trailing handler
        // cannot tell "the mail never went out" from "the mail went out, but recording SENT
        // failed" — Mutiny's Uni#chain composes onto ONE failure channel, so it would catch
        // both, and the original code did: a transient Postgres error AFTER a successful send
        // silently overwrote the row with status=FAILED, while the customer had actually
        // received the message. That is a worse outcome than leaving the row PENDING.
        mailer.send(Mail.withHtml(request.recipient, subject, body))
            .onFailure().invoke { e ->
                log.warnf(e, "opsmessage.compose: mail send failed notificationId=%s", notificationId)
            }
            .onFailure().recoverWithUni { _ -> notificationRepo.markTerminalStatus(notificationId, "FAILED") }
            // Scoped bulk UPDATE, not find-then-map-then-persist (issue #1393): the prior code
            // SELECTed the full row (pulling subject/body HTML back out) before UPDATEing it —
            // an extra DB round-trip this repository's own markRead/markAllRead idiom already
            // avoided elsewhere in this file.
            .chain { _ -> notificationRepo.markTerminalStatus(notificationId, "SENT", Instant.now(clock)) }
            // Only reachable if the mail genuinely went out (the FAILED path above already
            // recovered any send failure into a completed Uni). Never marks the row FAILED —
            // that would be a lie about a message that was actually delivered — logs loudly
            // instead, and swallows so the endpoint still returns 201: the customer already has
            // the message, this is a bookkeeping problem for an operator to notice via the log,
            // not a reason to fail the request.
            .onFailure().invoke { e ->
                log.warnf(
                    e,
                    "opsmessage.compose: mail sent but recording SENT status failed notificationId=%s " +
                        "— row left as-is, NOT marked FAILED (the email was actually delivered)",
                    notificationId,
                )
            }
            .onFailure().recoverWithUni { _ -> Uni.createFrom().voidItem() }
            .awaitSuspending()

        return notificationId
    }

    // Format-validate before anything is persisted or sent (issue #1384): an empty string,
    // malformed address, or a string containing CR/LF previously reached Mail.withHtml's `to`
    // field unchecked — best case a late unhandled mailer exception, worst case a header-
    // injection vector. Regex.matches() requires the ENTIRE string to match (no partial-region
    // matching, unlike `find`), so a trailing "\r\n" cannot sneak past the `$` anchor.
    //
    // Symmetrical variable check (issue #1381): unknownVariables() only ever caught EXTRA keys,
    // so a request missing a required key sailed through and render()'s old fallback-to-""
    // quietly substituted a blank — a real customer got a message with an empty body/subject,
    // and the row was persisted and mailed as an ordinary SENT row with no error in the chain.
    private fun validateRequest(request: OperatorMessageRequest) {
        if (request.recipient.isBlank() || !EMAIL_PATTERN.matches(request.recipient)) {
            throw OperatorMessageRejected("recipient is not a well-formed email address")
        }

        val unknown = request.template.unknownVariables(request.variables)
        val missing = request.template.variables - request.variables.keys
        if (unknown.isNotEmpty() || missing.isNotEmpty()) {
            throw OperatorMessageRejected(
                "template ${request.template.name} declares ${request.template.variables.sorted()} " +
                    "but request carried ${request.variables.keys.sorted()}" +
                    (if (unknown.isNotEmpty()) " (undeclared: ${unknown.sorted()})" else "") +
                    (if (missing.isNotEmpty()) " (missing: ${missing.sorted()})" else ""),
            )
        }
    }

    /**
     * Exhaustive, no `else` — a new [OperatorMessageTemplate] constant fails to compile here.
     * `vars.getValue(key)` is safe: [compose] already rejected any request whose `variables`
     * don't exactly match `template.variables` (extra AND missing), so every declared key is
     * guaranteed present by the time render() runs — no fallback-to-"" indirection needed.
     *
     * Every value interpolated into the HTML *body* is [HtmlEscape.escape]d (issue #1382): this
     * is the operator-reachable half of that issue — `note` (`GENERIC_NOTICE`) and
     * `ticketReference` (`SUPPORT_FOLLOWUP`) previously reached `Mail.withHtml` completely
     * unescaped, so `ROLE_OPERATOR` free text like `<img src=x onerror=...>` rendered live in the
     * customer's mail client. `subject` is deliberately NOT escaped: it becomes the mail
     * `Subject:` header, not HTML, so HTML-entity-encoding it would corrupt the visible subject
     * line rather than protect anything (header injection there is already closed by
     * [validateRequest]'s recipient pattern; `subject` itself carries no address/header syntax).
     */
    private fun render(template: OperatorMessageTemplate, vars: Map<String, String>): Pair<String, String> =
        when (template) {
            OperatorMessageTemplate.GENERIC_NOTICE ->
                (vars.getValue("subject").ifBlank { "A message from OpenBank" }) to
                    "<p>${HtmlEscape.escape(vars.getValue("note"))}</p>"
            OperatorMessageTemplate.SUPPORT_FOLLOWUP ->
                "Following up on your support request" to
                    "<p>We are following up on your support request " +
                    "(reference <b>${HtmlEscape.escape(vars.getValue("ticketReference"))}</b>). " +
                    "Please reply to this message if you have further questions.</p>"
        }

    companion object {
        // Deliberately simple (not RFC 5322-complete): reject blanks/malformed input and, by
        // excluding whitespace from both local and domain parts, CR/LF header-injection payloads.
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

data class OperatorMessageRequest(
    val partyId: UUID,
    val template: OperatorMessageTemplate,
    val recipient: String,
    val variables: Map<String, String>,
)
