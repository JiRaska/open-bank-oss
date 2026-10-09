// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.application.exit.ParticipantNotificationPort
import com.openbank.pension.application.port.out.NotificationDispatch
import com.openbank.pension.application.port.out.ParticipantNotification
import com.openbank.pension.application.port.out.ParticipantNotificationKind
import com.openbank.pension.application.port.out.ParticipantNotifier
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.arc.profile.UnlessBuildProfile
import io.smallrye.reactive.messaging.kafka.Record
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The participant's notice language, as notification-service's closed `NotificationLanguage`
 * vocabulary (`CS` | `EN`; anything else would be rejected by its consumer). Resolved from the
 * party record per notice, so a participant who switches language is told in the new one.
 */
fun interface ParticipantLanguageResolver {
    suspend fun languageOf(partyId: UUID): String
}

/**
 * Maps a party's preferred language to notification-service's vocabulary. Unknown, blank or an
 * unsupported language falls back to [default] (`CS` for a CZ-only product line), never to a value
 * the consumer would reject.
 */
object NoticeLanguage {
    val SUPPORTED = setOf("CS", "EN")

    fun of(preferred: String?, default: String): String {
        val code = preferred?.trim()?.take(2)?.uppercase()
        return if (code in SUPPORTED) code!! else default.uppercase().takeIf { it in SUPPORTED } ?: "CS"
    }
}

/** The send, without Kafka types, so [ParticipantNotificationPublisher] is unit-tested. */
fun interface NotificationRequestSender {
    /** Completes when the broker acknowledged the record; throws when it did not. */
    suspend fun send(key: String, payload: String)
}

/**
 * Publishes participant notices onto `openbank.notification.requests`, the topic
 * notification-service's `NotificationConsumer` drains, in its `NotificationRequest` envelope
 * `{ partyId, channel, template, recipient, variables }` (the shape domestic-payment's
 * `KafkaCustomerNotificationPublisher` sends). Keyed by party, so one participant's notices keep
 * their order on one partition.
 *
 * Outcomes are counted for what this service can ESTABLISH (ADR-0252 phase 0, #4348):
 * `openbank_pension_notification_requests_total{kind, outcome}` with `outcome` one of
 * `enqueued` (the broker acknowledged), `skipped` (the switch is off — nothing was sent) and
 * `failed`. There is no `delivered`: delivery is notification-service's to observe, not ours.
 *
 * `openbank.pension.notifications.enabled` defaults to FALSE: notification-service's template
 * vocabulary is a closed enum and does not yet contain the `PENSION_*` templates (follow-up #12392 on
 * #12350), so it would reject every notice. Until it does, every notice is SKIPPED — visibly, in
 * the metric — and a step that REQUIRES a notice (a payout-account change) is refused.
 */
class ParticipantNotificationPublisher(
    private val enabled: Boolean,
    private val sender: NotificationRequestSender,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
    private val language: ParticipantLanguageResolver,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : ParticipantNotifier {
    private val log = Logger.getLogger(ParticipantNotificationPublisher::class.java)

    override suspend fun send(notification: ParticipantNotification): NotificationDispatch {
        val outcome = if (!enabled) {
            NotificationDispatch.SKIPPED
        } else {
            try {
                withTimeout(timeoutMillis) {
                    val lang = language.languageOf(notification.partyId)
                    sender.send(notification.partyId.toString(), envelope(notification, lang))
                }
                NotificationDispatch.ENQUEUED
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                log.warnf(
                    e,
                    "participant notice %s for party %s was not enqueued",
                    notification.kind,
                    notification.partyId,
                )
                NotificationDispatch.FAILED
            }
        }
        counter(notification.kind, outcome).increment()
        return outcome
    }

    fun envelope(notification: ParticipantNotification, language: String): String = objectMapper.writeValueAsString(
        mapOf(
            // notification-service renders the PENSION_* copy in this language (cs / en, #12409).
            "language" to language,
            "partyId" to notification.partyId.toString(),
            "channel" to "PUSH",
            "template" to notification.kind.template,
            // Informational for PUSH: delivery is by the party's registered devices.
            "recipient" to notification.partyId.toString(),
            "variables" to notification.variables,
        ),
    )

    private fun counter(kind: ParticipantNotificationKind, outcome: NotificationDispatch): Counter =
        Counter.builder(METRIC)
            .description("Participant notices handed to notification-service, by outcome (never 'delivered')")
            .tag("kind", kind.name)
            .tag("outcome", outcome.name.lowercase())
            .register(meters)

    companion object {
        const val METRIC = "openbank.pension.notification.requests"
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}

/** The REAL [ParticipantNotifier]: Kafka onto notification-service's request topic. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class KafkaParticipantNotifier(
    @param:Channel("participant-notifications-out") private val emitter: Emitter<Record<String, String>>,
    @param:ConfigProperty(name = "openbank.pension.notifications.enabled", defaultValue = "false")
    private val enabled: Boolean,
    objectMapper: ObjectMapper,
    meters: MeterRegistry,
    language: PartyLanguageResolver,
) : ParticipantNotifier {
    private val publisher = ParticipantNotificationPublisher(
        enabled,
        { key, payload -> emitter.send(Record.of(key, payload)).await() },
        objectMapper,
        meters,
        language,
    )

    override suspend fun send(notification: ParticipantNotification) = publisher.send(notification)
}

/** `dev`/`test` stand-in: records every notice and answers ENQUEUED, as an acknowledging broker would. */
@IfBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class RecordingParticipantNotifier : ParticipantNotifier {
    val sent = CopyOnWriteArrayList<ParticipantNotification>()

    override suspend fun send(notification: ParticipantNotification): NotificationDispatch {
        sent += notification
        return NotificationDispatch.ENQUEUED
    }
}

/** A notice the step depends on was not handed to notification-service; the step is refused (503). */
class ParticipantNotificationUnavailableException(message: String) : IllegalStateException(message)

/**
 * The REAL exit [ParticipantNotificationPort]: the payout-account-change security notice. The
 * change becomes effective only once it is marked NOTIFIED (`PayoutService`), so anything but
 * [NotificationDispatch.ENQUEUED] — including SKIPPED — refuses the change rather than letting a
 * silent channel count as a warning given.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class NotifierParticipantNotificationAdapter(private val notifier: ParticipantNotifier) : ParticipantNotificationPort {
    override suspend fun payoutAccountChanged(
        partyId: UUID,
        contractId: UUID,
        payoutId: UUID,
        accountLast4: String,
        effectiveFrom: LocalDate,
    ) = requireEnqueued(notifier, partyId, contractId, accountLast4, effectiveFrom)
}

internal suspend fun requireEnqueued(
    notifier: ParticipantNotifier,
    partyId: UUID,
    contractId: UUID,
    accountLast4: String,
    effectiveFrom: LocalDate,
) {
    val outcome = notifier.send(
        ParticipantNotification(
            partyId,
            ParticipantNotificationKind.PAYOUT_ACCOUNT_CHANGED,
            mapOf(
                "contractId" to contractId.toString(),
                "accountLast4" to accountLast4,
                "effectiveFrom" to effectiveFrom.toString(),
            ),
        ),
    )
    if (outcome != NotificationDispatch.ENQUEUED) {
        throw ParticipantNotificationUnavailableException(
            "the payout-account change notice was $outcome, not enqueued; the change is refused",
        )
    }
}

/**
 * The REAL resolver: the participant's `preferredLanguage` on the party record (party-service
 * `GET /api/v1/parties/{id}`, the read pension already makes for KYC). A lookup that cannot be made
 * falls back to the default rather than failing an informational notice; the security notice is
 * still sent, in the default language.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class PartyLanguageResolver(
    @param:RestClient private val parties: com.openbank.pension.infrastructure.identity.PartyRestClient,
    @param:ConfigProperty(name = "openbank.pension.notifications.default-language", defaultValue = "CS")
    private val default: String,
) : ParticipantLanguageResolver {
    private val log = Logger.getLogger(PartyLanguageResolver::class.java)

    override suspend fun languageOf(partyId: UUID): String {
        val preferred = try {
            parties.party(partyId).preferredLanguage
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.debugf(e, "party language for %s not resolved; using %s", partyId, default)
            null
        }
        return NoticeLanguage.of(preferred, default)
    }
}
