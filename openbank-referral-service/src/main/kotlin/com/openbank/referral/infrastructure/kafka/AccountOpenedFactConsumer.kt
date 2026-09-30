// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.event.EventActor
import com.openbank.libs.messaging.EventRetry
import com.openbank.referral.application.ReferralQualificationService
import com.openbank.referral.domain.QualificationRule
import com.openbank.referral.domain.ReferralConflictException
import com.openbank.referral.domain.ReferralNotFoundException
import com.openbank.referral.domain.ReferralValidationException
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * ADR-0310 D1 — turns account-service's `AccountCreated` into a recorded `account.opened` fact and
 * qualifies any invite on which that party is already the attributed referee.
 *
 * The wire type is translated through [QualificationRule.qualifyingKeyFor]; anything it does not
 * know is ignored, so this channel sharing a topic with other account events costs nothing.
 *
 * Failures split by kind (#5698): a payload that cannot be parsed or lacks `eventId`/`partyId`/
 * `occurredAt` is logged and skipped — it fails identically on every delivery, and guessing a
 * missing event id would break the idempotency the whole design rests on. A domain rejection is
 * acked for the same reason. Anything else is retried by [EventRetry] and then rethrown, so the
 * connector's configured `failure-strategy` decides what follows.
 */
@ApplicationScoped
class AccountOpenedFactConsumer(
    private val qualification: ReferralQualificationService,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(AccountOpenedFactConsumer::class.java)

    // TooGenericExceptionCaught: an untrusted payload may fail Jackson in any number of ways, and
    // every one of them must be skipped rather than crash the channel (poison-pill safety).
    @Suppress("TooGenericExceptionCaught")
    @Incoming("account-created-in")
    suspend fun consume(payload: String) {
        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable account.created event, skipping: %s", payload.take(PAYLOAD_LOG_CHARS))
            return
        }
        val eventType = node["eventType"]?.asText() ?: return
        if (QualificationRule.qualifyingKeyFor(eventType) == null) return

        val eventId = node["eventId"]?.asText()?.takeIf { it.isNotBlank() }
        val partyId = node["partyId"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val occurredAt = node["occurredAt"]?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (eventId == null || partyId == null || occurredAt == null) {
            log.warnf(
                "AccountCreated missing eventId/partyId/occurredAt, skipping: %s",
                payload.take(PAYLOAD_LOG_CHARS),
            )
            return
        }
        val accountId = node["aggregateId"]?.asText()

        try {
            EventRetry.withRetry(
                log,
                "Referral qualification for account.opened $eventId",
                partyId,
                isRetryable = ::isTransient,
            ) {
                qualification.recordAccountOpened(partyId, eventId, accountId, occurredAt, ACTOR)
            }
        } catch (e: ReferralConflictException) {
            ackDeterministic(e, eventId)
        } catch (e: ReferralNotFoundException) {
            ackDeterministic(e, eventId)
        } catch (e: ReferralValidationException) {
            ackDeterministic(e, eventId)
        }
    }

    private fun isTransient(e: Exception): Boolean = EventRetry.RETRY_UNLESS_DETERMINISTIC(e) &&
        e !is ReferralConflictException &&
        e !is ReferralNotFoundException &&
        e !is ReferralValidationException

    private fun ackDeterministic(e: RuntimeException, eventId: String) {
        log.errorf(e, "Referral qualification rejected account.opened %s deterministically; acked.", eventId)
    }

    private companion object {
        const val PAYLOAD_LOG_CHARS = 200
        val ACTOR: String = EventActor.system("referral-service", "openbank.accounts.account.created")
    }
}
