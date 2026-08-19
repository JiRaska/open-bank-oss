// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.KycService
import com.openbank.kyc.application.PepScreeningService
import com.openbank.kyc.application.port.out.KycCaseRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * Consumes party-service events and auto-opens a KYC case when a new party is created, so the
 * onboarding funnel no longer depends on an operator opening the case by hand (ADR-0068).
 *
 * Uses `suspend @Incoming` — the same pattern as onboarding-service's OnboardingEventConsumer:
 * Quarkus dispatches suspend handlers on the Vert.x event loop with a duplicated context, so the
 * downstream reactive persistence inside [KycService] runs correctly.
 *
 * Failure handling distinguishes two things the original version conflated (#5698).
 *
 * A **malformed event** is unretryable — replaying it produces the same parse failure forever — so
 * it is logged and acked. That is the poison-pill case, and it is the only one.
 *
 * A **transient failure of a dependency** is the opposite: the event is fine, the infrastructure is
 * not, and the work must happen once it recovers. Acking there loses the event silently. That is
 * what happened on 2026-08-19: kyc-db was down for a few seconds, a PARTY_CREATED arrived, the
 * catch-all logged `Failed to auto-open/screen KYC case` and acked. No case was ever opened, so the
 * party stayed PENDING_KYC forever; its two accounts stayed PENDING_ACTIVATION; and the welcome
 * bonus, which fires only on activation, never ran. The pooled sequence proves no insert was ever
 * retried (`kyc_cases_seq.last_value` did not advance past the block in use). Ten of 73 parties in
 * sandbox were in that state — 13.7% of the onboarding funnel, silently.
 *
 * So domain and infrastructure failures now go through [withBoundedRetry] and, if they still fail,
 * are RETHROWN — the connector then retries and ultimately dead-letters, which is a signal someone
 * can see. Same shape as card-issuance's CardDelegationEventConsumer and account-service's
 * DelegationEventConsumer.
 *
 * A single such event can no longer be quietly dropped; it can still wedge nothing, because the
 * retry is bounded and the failure moves to the DLQ rather than blocking the partition forever.
 */
@ApplicationScoped
class PartyEventConsumer {

    @Inject
    lateinit var kycService: KycService

    @Inject
    lateinit var kycCaseRepository: KycCaseRepository

    @Inject
    lateinit var pepScreeningService: PepScreeningService

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    private val log = Logger.getLogger(PartyEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON payload: %.200s", payload)
            return
        }

        val eventType = node.path("eventType").asText()
        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull()

        when (eventType) {
            "PARTY_CREATED" -> handleCreated(partyId, node, payload)
            "PARTY_ERASED" -> handleErased(partyId, payload)
        }
    }

    private suspend fun handleCreated(partyId: UUID?, node: JsonNode, payload: String) {
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_CREATED without a valid partyId, skipping: %.200s", payload)
            return
        }
        withBoundedRetry("PARTY_CREATED", partyId) {
            val (case, created) = kycService.openCaseForParty(partyId)
            if (created) {
                log.infof("[party-events-in] Auto-opened KYC case %s for party %s", case.id, partyId)
            } else {
                log.infof(
                    "[party-events-in] KYC case %s already open for party %s (idempotent reuse)",
                    case.id,
                    partyId,
                )
            }
            // First-increment PEP screen (ADR-0116 delivery note): only for a case still in an
            // active, non-terminal state — the sandbox auto-approve path (openbank.kyc.auto-approve)
            // may have already settled the case to APPROVED, and re-screening a closed case here
            // would race the terminal state rather than extend it. legalName comes straight from
            // the PARTY_CREATED payload (party-service's KafkaPartyEventPublisher).
            val legalName = node.path("legalName").asText(null)
            if (!case.status.isTerminal && !legalName.isNullOrBlank()) {
                pepScreeningService.screenCase(case.id, legalName)
                log.infof("[party-events-in] PEP-screened KYC case %s for party %s", case.id, partyId)
            }
        }
    }

    private suspend fun handleErased(partyId: UUID?, payload: String) {
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
            return
        }
        // Same rule, and here the cost of swallowing is a compliance breach rather than a stalled
        // funnel: an acked-but-failed erasure leaves PII in place while the log claims otherwise.
        // anonymizeByPartyId is idempotent, so a retry or a redelivery is safe.
        withBoundedRetry("PARTY_ERASED", partyId) {
            kycCaseRepository.anonymizeByPartyId(partyId, clock.instant())
            log.infof("[party-events-in] GDPR Art. 17: anonymised KYC PII for erased party %s", partyId)
        }
    }

    /**
     * Retry [block] a bounded number of times, then RETHROW so the connector dead-letters.
     *
     * The rethrow is the point. A caught-and-logged failure acks the message, and an acked message
     * that did no work is indistinguishable from one that succeeded — from Kafka, from the consumer
     * lag metric, and from every dashboard built on either. The only trace is an ERROR line nobody
     * is alerting on, which is exactly how ten parties sat un-KYC'd for months.
     */
    private suspend fun withBoundedRetry(eventType: String, partyId: UUID, block: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.errorf(
                        e,
                        "[party-events-in] %s for party %s failed after %d attempts (%s: %s) — dead-lettering",
                        eventType,
                        partyId,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                log.warnf(
                    "[party-events-in] %s for party %s failed (attempt %d/%d, %s: %s) — retrying",
                    eventType,
                    partyId,
                    attempt,
                    MAX_ATTEMPTS,
                    e.javaClass.simpleName,
                    e.message,
                )
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 500L
    }
}
