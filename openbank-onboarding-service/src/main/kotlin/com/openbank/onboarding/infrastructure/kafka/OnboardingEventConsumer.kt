// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.onboarding.application.usecase.OnboardingProjectionService
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingEvent
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.domain.model.ProjectionResult
import com.openbank.onboarding.infrastructure.observability.ProjectionOutcomeMetrics
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Consumes events from party-, kyc- and sca-service topics and feeds the onboarding read-model.
 *
 * Uses `suspend @Incoming` — same pattern as balance-service's LedgerProjectionConsumer.
 * Quarkus dispatches suspend @Incoming handlers on the Vert.x event loop with a proper
 * duplicated context, so awaitSuspending() inside the projection service works correctly.
 *
 * **Poison pills are acked; failing dependencies are not (#5745, #5698).** A parse or mapping
 * failure is still caught, logged and acked — replaying that record fails identically forever, so
 * it is the one case where dropping is right. A *projection* failure is a different thing: the
 * database or the read-model service is down, the event is fine, and returning normally tells Kafka
 * the work is done. Those are retried a bounded number of times by [EventRetry] and then rethrown,
 * which nacks the record.
 *
 * The old KDoc justified acking projection failures with "the canonical source of truth
 * (party/kyc/sca) can be replayed". Nothing replays it: no re-seed job exists in this service, and
 * the two things being lost are not cosmetic — a dropped `PARTY_ERASED` leaves personal data in the
 * onboarding read model after an Art. 17 erasure was executed everywhere else (a compliance breach,
 * not a stale tile), and a dropped KYC/SCA transition means the funnel never sees the party move.
 *
 * **What becomes of a nacked record is the CONNECTOR's decision, not this class's.** The handler's
 * contract ends at "the projection did not happen, and the platform was told". Each channel's
 * configured `failure-strategy` decides the rest: `dead-letter-queue` parks the record on that
 * channel's DLQ topic for replay, SmallRye's default `fail` stops the channel instead. Both beat an
 * ack that loses an Art. 17 erasure silently, but they are different incidents — read
 * `application.yaml` per channel rather than assuming either. (#5751 wires all three to
 * `dead-letter-queue`, with explicit `openbank.dlq.onboarding.<channel>` topics.)
 *
 * **A recognised event type carrying an unparseable status is NOT a poison pill (#9038).** The old
 * `runCatching { X.valueOf(raw) }.getOrNull() ?: return null` mapped a typo'd or renamed status
 * onto the same path as an event type we legitimately ignore: the record was acked, the
 * UNRECOGNISED counter moved, and the funnel silently stopped seeing the party. That conflates two
 * different situations — "we don't care about this event type" (ack, correct) and "we care, the
 * producer changed the vocabulary, and every replay will fail the same way until the code or the
 * data is fixed" (nack; the DLQ keeps it for exactly that investigation). So the parsers throw
 * [UnparseableStatusException] for the second case, and the consume methods rethrow it after
 * recording the FAILED metric.
 */
@ApplicationScoped
class OnboardingEventConsumer(private val clock: Clock) {

    @Inject
    lateinit var projection: OnboardingProjectionService

    @Inject
    lateinit var objectMapper: ObjectMapper

    // Field injection: the constructor already carries Clock, and detekt's LongParameterList
    // fires AT the threshold rather than above it.
    @Inject
    lateinit var metrics: ProjectionOutcomeMetrics

    private val log = Logger.getLogger(OnboardingEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consumePartyEvent(payload: String) {
        // Fast-path: intercept PARTY_ERASED before routing through parsePartyEvent, which
        // returns null for unknown event types and would silently drop the erasure request.
        // This mirrors the pattern used in kyc-service's PartyEventConsumer (GDPR Art. 17).
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON payload: %.200s", payload)
            return
        }

        if (node.path("eventType").asText() == "PARTY_ERASED") {
            val partyId = node.path("partyId").asUuid()
            if (partyId == null) {
                log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
                return
            }
            // Rethrows after the bounded attempts. An erasure that is acked without happening is a
            // GDPR Art. 17 breach that no later process notices, and `PARTY_ERASED` is emitted once.
            EventRetry.withRetry(log, "[party-events-in] GDPR Art. 17 erasure", partyId) {
                projection.eraseParty(partyId)
            }
            log.infof("[party-events-in] GDPR Art. 17: erased onboarding read-model for party %s", partyId)
            return
        }

        val event = try {
            parsePartyEvent(node)
        } catch (e: UnparseableStatusException) {
            // Recognised event type, unrecognised status vocabulary: nack, do not ack (#9038).
            metrics.record("party-events-in", ProjectionOutcomeMetrics.Outcome.FAILED)
            throw e
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to map event: %.200s", payload)
            metrics.record("party-events-in", ProjectionOutcomeMetrics.Outcome.FAILED)
            return
        } ?: run {
            metrics.record("party-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)
            return
        }
        project(event, "party-events-in")
    }

    @Incoming("kyc-events-in")
    suspend fun consumeKycEvent(payload: String) {
        val event = parseEvent(payload, "kyc-events-in") { parseKycEvent(it) } ?: return
        project(event, "kyc-events-in")
    }

    @Incoming("sca-events-in")
    suspend fun consumeScaEvent(payload: String) {
        val event = parseEvent(payload, "sca-events-in") { parseScaEvent(it) } ?: return
        project(event, "sca-events-in")
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parsePartyEvent(node: JsonNode): OnboardingEvent? {
        val type = node.path("eventType").asText()
        val partyId = node.path("partyId").asUuid() ?: return null
        val occurredAt = node.path("occurredAt").asInstant() ?: clock.instant()
        return when (type) {
            "PARTY_CREATED" -> OnboardingEvent.PartyCreated(
                partyId = partyId,
                legalName = node.path("legalName").asText(""),
                email = node.path("email").asText(""),
                occurredAt = occurredAt,
            )
            // KafkaPartyEventPublisher actually publishes "KYC_STATUS_CHANGED" (see
            // publishKycStatusChanged) — "KYC_STATUS_UPDATED" was never a real event type, so
            // every KYC/AML status transition from party-service was silently dropped here.
            "PARTY_STATUS_CHANGED", "KYC_STATUS_UPDATED", "KYC_STATUS_CHANGED" -> {
                val rawStatus = node.path("newStatus").asText().takeIf { it.isNotBlank() }
                    ?: node.path("status").asText()
                val stage = runCatching { PartyStage.valueOf(rawStatus) }.getOrElse {
                    throw UnparseableStatusException(type, rawStatus, PartyStage::class.simpleName ?: "PartyStage")
                }
                OnboardingEvent.PartyStatusChanged(partyId, stage, occurredAt)
            }
            else -> null
        }
    }

    private fun parseKycEvent(node: JsonNode): OnboardingEvent? {
        val type = node.path("eventType").asText()
        val partyId = node.path("partyId").asUuid() ?: return null
        val occurredAt = node.path("occurredAt").asInstant() ?: clock.instant()
        return when (type) {
            "KYC_CASE_OPENED" -> {
                // kyc-service emits the case id as "kycCaseId"; accept "caseId" too for resilience.
                val caseId = (node.path("kycCaseId").asUuid() ?: node.path("caseId").asUuid()) ?: return null
                OnboardingEvent.KycCaseOpened(partyId, caseId, occurredAt)
            }
            "KYC_CASE_STATUS_CHANGED", "KYC_CASE_APPROVED", "KYC_CASE_REJECTED" -> {
                // kyc-service emits the case id as "kycCaseId"; accept "caseId" too for resilience.
                val caseId = (node.path("kycCaseId").asUuid() ?: node.path("caseId").asUuid()) ?: return null
                // KycEventPublisher.publish always serializes the field as "status", never
                // "newStatus" — KYC_CASE_STATUS_CHANGED has no other fallback, so every
                // non-terminal case-status transition (e.g. UNDER_REVIEW, PEP escalation) was
                // silently dropped here (only the terminal APPROVED/REJECTED hardcoded defaults
                // ever worked, and only by coincidence — they never actually read the payload).
                val rawStatus = node.path("newStatus").asText().takeIf { it.isNotBlank() }
                    ?: node.path("status").asText().takeIf { it.isNotBlank() }
                    ?: when (type) {
                        "KYC_CASE_APPROVED" -> "APPROVED"
                        "KYC_CASE_REJECTED" -> "REJECTED"
                        else -> return null
                    }
                val stage = runCatching { KycStage.valueOf(rawStatus) }.getOrElse {
                    throw UnparseableStatusException(type, rawStatus, KycStage::class.simpleName ?: "KycStage")
                }
                OnboardingEvent.KycStatusChanged(partyId, caseId, stage, occurredAt)
            }
            else -> null
        }
    }

    private fun parseScaEvent(node: JsonNode): OnboardingEvent? {
        val type = node.path("eventType").asText()
        val partyId = node.path("partyId").asUuid() ?: return null
        val occurredAt = node.path("occurredAt").asInstant() ?: clock.instant()
        return when (type) {
            "DEVICE_ENROLLED" -> OnboardingEvent.DeviceEnrolled(
                partyId = partyId,
                // sca-service sends both; `credentialId` is the stable identity of the key
                // material and `deviceId` the row id. Fall back rather than default to "",
                // because an empty credential id collapses every one of a party's devices onto
                // the same ledger key and silently caps device_count at 1.
                credentialId = node.path("credentialId").asText("").takeIf { it.isNotBlank() }
                    ?: node.path("deviceId").asText(""),
                occurredAt = occurredAt,
            )
            else -> null
        }
    }

    // ── Dispatch helpers ──────────────────────────────────────────────────────

    private fun parseEvent(payload: String, topic: String, parse: (JsonNode) -> OnboardingEvent?): OnboardingEvent? {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[%s] Failed to parse JSON payload: %.200s", topic, payload)
            metrics.record(topic, ProjectionOutcomeMetrics.Outcome.FAILED)
            return null
        }
        val event = try {
            parse(node)
        } catch (e: UnparseableStatusException) {
            // Recognised event type, unrecognised status vocabulary: nack, do not ack (#9038).
            metrics.record(topic, ProjectionOutcomeMetrics.Outcome.FAILED)
            throw e
        } catch (e: Exception) {
            log.errorf(e, "[%s] Failed to map event: %.200s", topic, payload)
            metrics.record(topic, ProjectionOutcomeMetrics.Outcome.FAILED)
            return null
        }
        // A null here is the QUIET drop this counter exists for: valid JSON the parser
        // recognised nothing in. No log — it is the normal outcome for the many event types
        // on a shared topic that onboarding legitimately ignores — so the only way to tell
        // "ignoring what we should ignore" from "ignoring everything" is the ratio.
        if (event == null) metrics.record(topic, ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)
        return event
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun project(event: OnboardingEvent, topic: String) {
        val result = try {
            EventRetry.withRetry(log, "[$topic] Projection of ${event::class.simpleName}", partyIdOf(event)) {
                projection.applyEvent(event)
            }
        } catch (e: Exception) {
            // The FAILED counter is still recorded — it is the series the funnel dashboards read —
            // and then the failure is rethrown so the record is nacked rather than acked as done.
            metrics.record(topic, ProjectionOutcomeMetrics.Outcome.FAILED)
            throw e
        }
        // A seed is NOT an ordinary success (#6248). This used to be a *drop* counted as
        // PROJECTED, which is how 15 DEVICE_ENROLLED events were consumed with zero lag, reached
        // no row, and left every alert reading healthy.
        when (result) {
            ProjectionResult.APPLIED ->
                metrics.record(topic, ProjectionOutcomeMetrics.Outcome.PROJECTED)

            ProjectionResult.APPLIED_TO_SEEDED_RECORD -> {
                // INFO, not WARN: nothing is lost any more. It is logged at all because the
                // ordering is otherwise invisible per-party — the metric says how many, only
                // this line says which, and a row with no legal name is a question an operator
                // will eventually ask about.
                log.infof(
                    "[%s] Seeded onboarding record for party %s from %s: PARTY_CREATED has not arrived yet",
                    topic,
                    partyIdOf(event),
                    event::class.simpleName,
                )
                metrics.record(topic, ProjectionOutcomeMetrics.Outcome.SEEDED_UNKNOWN_PARTY)
            }
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private fun JsonNode.asUuid(): UUID? = runCatching { UUID.fromString(asText()) }.getOrNull()
    private fun JsonNode.asInstant(): Instant? = runCatching { Instant.parse(asText()) }.getOrNull()
}

/**
 * The sealed base declares only `occurredAt`; every subtype carries its own party id.
 *
 * Top-level on purpose: as a member it would put the class AT detekt's `TooManyFunctions`
 * threshold of 11, which fires at the limit rather than above it. It carries no annotation, so the
 * next-declaration binding trap does not apply here.
 */
private fun partyIdOf(event: OnboardingEvent): UUID = when (event) {
    is OnboardingEvent.PartyCreated -> event.partyId
    is OnboardingEvent.PartyStatusChanged -> event.partyId
    is OnboardingEvent.KycCaseOpened -> event.partyId
    is OnboardingEvent.KycStatusChanged -> event.partyId
    is OnboardingEvent.DeviceEnrolled -> event.partyId
}

/**
 * A recognised event type carried a status value the enum no longer knows (#9038). Distinct from
 * "event type we ignore" (parsed to null, acked) and from "malformed JSON" (logged, acked): this
 * one means the producer's vocabulary drifted from ours, and every replay fails identically until
 * code or data changes — exactly what the DLQ exists to hold. Thrown by the parsers, recorded as
 * FAILED, then rethrown so the record is nacked. Extends [IllegalStateException] so any catch site
 * that already special-cases mapping failures still sees it as one.
 */
class UnparseableStatusException(eventType: String, rawStatus: String, enumName: String) :
    IllegalStateException("Event $eventType carried status '$rawStatus' not present in $enumName")
