// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.mailer.Mail
import io.quarkus.mailer.reactive.ReactiveMailer
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
 * worse, not better. IN_APP is refused because it is a stub that never leaves `PENDING` (no real
 * delivery exists yet). SMS was never implemented for any template. Real multi-channel delivery is
 * the remaining ADR-0176 D2/D3 work.
 */
@ApplicationScoped
class OperatorMessageService {

    @Inject
    lateinit var notificationRepo: NotificationRepository

    @Inject
    lateinit var mailer: ReactiveMailer

    @Inject
    lateinit var clock: Clock

    suspend fun compose(request: OperatorMessageRequest): UUID {
        val unknown = request.template.unknownVariables(request.variables)
        if (unknown.isNotEmpty()) {
            throw OperatorMessageRejected(
                "template ${request.template.name} declares ${request.template.variables.sorted()} " +
                    "but request carried undeclared ${unknown.sorted()}",
            )
        }

        val (subject, body) = render(request.template, request.variables)
        // notificationId is the entity's durable, indexed identifier (ADR-0106) — Ids.newId()
        // (UUIDv7), not a bare UUID.randomUUID(). Matches NotificationConsumer's existing rows,
        // which predate this guard.
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

        mailer.send(Mail.withHtml(request.recipient, subject, body))
            .chain { _ ->
                Panache.withTransaction {
                    notificationRepo.find("notificationId", notificationId).firstResult()
                        .map { e ->
                            e?.also {
                                it.status = "SENT"
                                it.sentAt = Instant.now(clock)
                            }
                        }
                }
            }
            .onFailure().recoverWithUni { _ ->
                Panache.withTransaction {
                    notificationRepo.find("notificationId", notificationId).firstResult()
                        .map { e -> e?.also { it.status = "FAILED" } }
                }
            }
            .awaitSuspending()

        return notificationId
    }

    /** Exhaustive, no `else` — a new [OperatorMessageTemplate] constant fails to compile here. */
    private fun render(template: OperatorMessageTemplate, vars: Map<String, String>): Pair<String, String> =
        when (template) {
            OperatorMessageTemplate.GENERIC_NOTICE ->
                (vars.v("subject").ifBlank { "A message from OpenBank" }) to
                    "<p>${vars.v("note")}</p>"
            OperatorMessageTemplate.SUPPORT_FOLLOWUP ->
                "Following up on your support request" to
                    "<p>We are following up on your support request " +
                    "(reference <b>${vars.v("ticketReference")}</b>). " +
                    "Please reply to this message if you have further questions.</p>"
        }
}

private fun Map<String, String>.v(key: String): String = this[key] ?: ""

data class OperatorMessageRequest(
    val partyId: UUID,
    val template: OperatorMessageTemplate,
    val recipient: String,
    val variables: Map<String, String>,
)
