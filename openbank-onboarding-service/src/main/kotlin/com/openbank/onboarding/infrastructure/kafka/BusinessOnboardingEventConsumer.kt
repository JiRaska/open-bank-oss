// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.onboarding.application.usecase.BusinessOnboardingProjectionService
import com.openbank.onboarding.domain.model.BusinessCaseStage
import com.openbank.onboarding.domain.model.BusinessOnboardingEvent
import com.openbank.onboarding.infrastructure.observability.ProjectionOutcomeMetrics
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Projects `openbank.kyb.events` into the business half of the onboarding read model (ADR-0284 D6).
 *
 * Same contract as [OnboardingEventConsumer], for the same reasons: a poison pill is logged and
 * acked because replaying it fails identically forever, while a projection failure is rethrown
 * after [EventRetry]'s bounded attempts so the record is nacked rather than silently marked done.
 * What happens to a nacked record is the connector's decision — `kyb-events-in` is configured
 * `failure-strategy: dead-letter-queue` with its own explicit topic in `application.yaml`.
 *
 * Consuming this topic is also what retires the `openbank.kyb.events` entry from
 * `rules.yaml: change_requirements.event_consumer_liveness.allowlist`: a produced-but-unconsumed
 * topic was allowed only until its first consumer landed, and that gate fails on a stale entry.
 */
@ApplicationScoped
class BusinessOnboardingEventConsumer(private val clock: Clock) {

    @Inject
    lateinit var projection: BusinessOnboardingProjectionService

    @Inject
    lateinit var objectMapper: ObjectMapper

    // Field injection: detekt's LongParameterList fires AT the constructor threshold, not above it.
    @Inject
    lateinit var metrics: ProjectionOutcomeMetrics

    private val log = Logger.getLogger(BusinessOnboardingEventConsumer::class.java)

    @Incoming("kyb-events-in")
    // Jackson raises unchecked subclasses of both IOException and RuntimeException for malformed
    // input; the specific type does not change the handling, which is "ack, this record can never
    // parse". Same shape as OnboardingEventConsumer's parse guards.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[%s] Failed to parse JSON payload: %.200s", CHANNEL, payload)
            metrics.record(CHANNEL, ProjectionOutcomeMetrics.Outcome.FAILED)
            return
        }
        val event = parse(node)
        if (event == null) {
            // The quiet drop this counter exists for: valid JSON carrying an event type or a
            // status this build does not model. Not logged per record — it is the normal outcome
            // for a shared topic — so the ratio is the only thing that tells "ignoring what we
            // should" from "ignoring everything".
            metrics.record(CHANNEL, ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)
            return
        }
        project(event)
    }

    private fun parse(node: JsonNode): BusinessOnboardingEvent? {
        val caseId = node.path("caseId").asUuid() ?: return null
        val initiator = node.path("initiatorPartyId").asUuid() ?: return null
        // An unknown status is NOT mapped to a neighbouring one: a guessed board column is worse
        // than a missing row, because it looks like a measurement.
        val status = BusinessCaseStage.from(node.path("status").asText()) ?: return null
        val identifier = node.path("identifier").asText("").takeIf { it.isNotBlank() } ?: return null
        return BusinessOnboardingEvent(
            eventType = node.path("eventType").asText(""),
            caseId = caseId,
            status = status,
            identifierScheme = node.path("identifierScheme").asText("UNKNOWN"),
            identifier = identifier,
            country = node.path("country").asTextOrNull(),
            legalName = node.path("legalName").asTextOrNull(),
            legalFormClass = node.path("legalFormClass").asTextOrNull(),
            initiatorPartyId = initiator,
            entityPartyId = node.path("entityPartyId").asUuid(),
            requiredSignatures = node.path("requiredSignatures").takeIf { it.isInt }?.asInt(),
            signedCount = node.path("signedCount").asInt(0),
            reviewReason = node.path("reviewReason").asTextOrNull(),
            occurredAt = node.path("occurredAt").asInstant() ?: clock.instant(),
        )
    }

    @Suppress("TooGenericExceptionCaught") // the metric is recorded for every failure, then rethrown
    private suspend fun project(event: BusinessOnboardingEvent) {
        try {
            EventRetry.withRetry(log, "[$CHANNEL] Projection of ${event.eventType}", event.caseId) {
                projection.project(event)
            }
        } catch (e: Exception) {
            metrics.record(CHANNEL, ProjectionOutcomeMetrics.Outcome.FAILED)
            throw e
        }
        metrics.record(CHANNEL, ProjectionOutcomeMetrics.Outcome.PROJECTED)
    }

    private fun JsonNode.asUuid(): UUID? = runCatching { UUID.fromString(asText()) }.getOrNull()
    private fun JsonNode.asInstant(): Instant? = runCatching { Instant.parse(asText()) }.getOrNull()
    private fun JsonNode.asTextOrNull(): String? = asText("").takeIf { it.isNotBlank() }

    private companion object {
        const val CHANNEL = "kyb-events-in"
    }
}
