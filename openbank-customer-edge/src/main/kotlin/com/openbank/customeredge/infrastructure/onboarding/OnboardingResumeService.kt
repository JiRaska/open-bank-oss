// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.KeycloakAdminClient
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming

/**
 * Resumes a paused onboarding when pid decides its four-eyes identity-verification case (ADR-0072).
 *
 * The onboarding gate stores a [PendingOnboarding] (keyed by caseId) when /resolve returns
 * NEEDS_MANUAL_VERIFICATION. This consumer listens to pid's `party.events` and, on an
 * `IdentityVerificationCaseDecided` event, replays the onboarding per the operators' verdict:
 *   - LINK_TO_EXISTING → link the applicant's Keycloak sub to the decided party (one golden record).
 *   - DISTINCT_NEW     → create the party + register the (no-RČ) identity into pid.
 *   - REJECT           → audit only; the applicant is not onboarded.
 *
 * Flag-gated off by default. The plaintext RČ is never available here (never stored) — resume uses
 * name + birthdate.
 *
 * ## Failure handling — the pending record must outlive a failed attempt (#5698)
 *
 * This used to be `try { resume(...) } catch (e: Exception) { Log.error(...) } finally { delete }`,
 * which is the catch-and-ack defect of #5698 with an extra turn of the screw. [resume] fans out to
 * `upstream.post` (party-service, pid-service) and `keycloakAdmin.setPartyIdAttribute`; a connection
 * refused from any of them was logged and the handler returned normally, acking the Kafka message —
 * and the `finally` then deleted the [PendingOnboarding] anyway. So even a manual replay of the
 * decision event found nothing to resume: the applicant was stranded with an operator verdict
 * recorded in pid and no party ever created, and the only trace was one ERROR line.
 *
 * Now the attempt is bounded-retried and, if it still fails, RETHROWN — the connector dead-letters,
 * which is a signal someone can see — and the pending record is deleted **only on success**, so a
 * redelivery or a replay has something to work with. `resume` is safe to repeat: the party create
 * carries the idempotency key `onboarding-resume-<caseId>`, the Keycloak sub link is idempotent by
 * construction, and the pid identity registration is keyed on the party id.
 *
 * A malformed or foreign event still returns early without deleting anything (nothing was written),
 * and an unknown verdict is treated as handled — replaying it produces the same unknown verdict
 * forever, so it is the poison-pill case and the record is cleared.
 */
@ApplicationScoped
@Suppress("LongParameterList") // CDI-injected collaborators + two service URLs + the feature flag
class OnboardingResumeService(
    private val upstream: UpstreamClient,
    private val keycloakAdmin: KeycloakAdminClient,
    private val audit: EdgeAuditPublisher,
    private val pendingStore: PendingOnboardingStore,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.edge.party-service-url")
    private val partyServiceUrl: String,
    @ConfigProperty(name = "openbank.edge.pid-service-url")
    private val pidServiceUrl: String,
    @ConfigProperty(name = "openbank.edge.identity-resume-enabled", defaultValue = "false")
    private val resumeEnabled: Boolean,
) {

    @Incoming("party-events-in")
    @Blocking
    fun onPartyEvent(payload: String) {
        if (!resumeEnabled) return
        // Everything down to `linkPartyId` is parsing/routing: a malformed or foreign event returns
        // early and is acked, because replaying it can only fail the same way. Nothing was written,
        // so nothing is deleted either.
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        if (node["eventType"]?.asText() != DECIDED_EVENT) return
        val caseId = node["aggregateId"]?.asText()?.takeIf { it.isNotBlank() } ?: return
        val pending = pendingStore.find(caseId) ?: return // not ours, or expired
        val p = node["payload"]
        val verdict = p?.get("verdict")?.asText() ?: return
        val linkPartyId = p?.get("linkPartyId")?.takeIf { !it.isNull }?.asText()

        // The downstream half. On success the pending record is cleared; on a persistent failure the
        // exception escapes so the connector dead-letters AND the record survives for the replay.
        withBoundedRetry(caseId, verdict) { resume(pending, verdict, linkPartyId) }
        pendingStore.delete(caseId)
    }

    /**
     * Retry [block] a bounded number of times, then RETHROW so the connector dead-letters.
     *
     * The rethrow is the point. A caught-and-logged failure acks the message, and an acked message
     * that did no work is indistinguishable from one that succeeded — from Kafka, from the consumer
     * lag metric, and from every dashboard built on either.
     *
     * The sleep is a plain [Thread.sleep] because this handler is `@Blocking`: Quarkus dispatches it
     * on a worker thread, never on the event loop, so blocking it delays only this partition.
     */
    @Suppress("TooGenericExceptionCaught") // the retry is type-agnostic on purpose: any failure of
    // the fan-out is a failure to resume, and the bounded rethrow (not a swallow) keeps it visible.
    private fun withBoundedRetry(caseId: String, verdict: String, block: () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    Log.error(
                        "onboarding resume failed for case=$caseId verdict=$verdict after $attempt " +
                            "attempts (${e.javaClass.simpleName}: ${e.message}) — dead-lettering, " +
                            "the pending record is kept so a replay can resume it",
                        e,
                    )
                    throw e
                }
                Log.warn(
                    "onboarding resume failed for case=$caseId verdict=$verdict " +
                        "(attempt $attempt/$MAX_ATTEMPTS, ${e.javaClass.simpleName}: ${e.message}) — retrying",
                )
                Thread.sleep(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    private fun resume(pending: PendingOnboarding, verdict: String, linkPartyId: String?) {
        when (verdict) {
            "LINK_TO_EXISTING" -> resumeLink(pending, linkPartyId)
            "DISTINCT_NEW" -> resumeCreate(pending)
            "REJECT" -> audit.emit(
                eventType = "CUSTOMER_ONBOARDING_REJECTED",
                partyId = pending.callerPartyId,
                operation = "onboarding.resume",
                result = "REJECT",
                details = mapOf("caseId" to pending.caseId),
            )
            else -> Log.warn("onboarding resume: unknown verdict '$verdict' for case ${pending.caseId}")
        }
    }

    private fun resumeLink(pending: PendingOnboarding, linkPartyId: String?) {
        if (linkPartyId == null) {
            Log.warn("onboarding resume: LINK_TO_EXISTING without linkPartyId for case ${pending.caseId}")
            return
        }
        val subLinked = linkKeycloakSub(linkPartyId, pending.callerPartyId)
        val attributeSet = keycloakAdmin.setPartyIdAttribute(pending.callerPartyId, linkPartyId)
        audit.emit(
            eventType = "CUSTOMER_ONBOARDING_RESUMED_LINKED",
            partyId = linkPartyId,
            operation = "onboarding.resume",
            result = "LINK_TO_EXISTING",
            details = mapOf(
                "caseId" to pending.caseId,
                "newSub" to pending.callerPartyId,
                "subLinked" to subLinked.toString(),
                "partyIdAttributeSet" to attributeSet.toString(),
            ),
        )
    }

    private fun resumeCreate(pending: PendingOnboarding) {
        val out = objectMapper.createObjectNode()
        out.put("partyType", "INDIVIDUAL")
        out.put("legalName", pending.legalName)
        out.put("email", pending.email)
        out.put("id", pending.callerPartyId)
        pending.phone?.let { out.put("phone", it) }
        pending.dateOfBirth?.let { out.put("dateOfBirth", it) }
        pending.nationality?.let { out.put("nationality", it) }

        val resp = upstream.post(
            "$partyServiceUrl/api/v1/parties",
            pending.callerPartyId,
            objectMapper.writeValueAsString(out),
            "onboarding-resume-${pending.caseId}",
        )
        val created = resp.statusInfo.family == Response.Status.Family.SUCCESSFUL
        if (created) registerIdentityInPid(pending)
        audit.emit(
            eventType = "CUSTOMER_ONBOARDING_RESUMED_CREATED",
            partyId = pending.callerPartyId,
            operation = "onboarding.resume",
            result = if (created) "DISTINCT_NEW" else "ERROR",
            details = mapOf("caseId" to pending.caseId, "created" to created.toString()),
        )
    }

    /** Idempotent KEYCLOAK_ID link of [newSub] to [existingPartyId] (mirrors the REST gate's helper). */
    private fun linkKeycloakSub(existingPartyId: String, newSub: String): Boolean = runCatching {
        val resp = upstream.post(
            "$pidServiceUrl/api/v1/parties/$existingPartyId/external-ids",
            existingPartyId,
            """{"type":"KEYCLOAK_ID","value":"$newSub"}""",
            "relink-$newSub",
        )
        resp.statusInfo.family == Response.Status.Family.SUCCESSFUL
    }.getOrDefault(false)

    /** Register the resumed identity into the pid index — name + birthdate only; the RČ is never stored. */
    private fun registerIdentityInPid(pending: PendingOnboarding) {
        val birthdate = pending.dateOfBirth ?: return
        runCatching {
            val (givenName, familyName) = splitLegalName(pending.legalName)
            val req = objectMapper.createObjectNode()
            req.put("partyId", pending.callerPartyId)
            req.put("givenName", givenName)
            req.put("familyName", familyName)
            req.put("birthdate", birthdate)
            req.put("keycloakSub", pending.callerPartyId)
            upstream.post(
                "$pidServiceUrl/api/v1/parties/register-identity",
                pending.callerPartyId,
                objectMapper.writeValueAsString(req),
                "register-${pending.callerPartyId}",
            )
        }.onFailure {
            Log.warn("onboarding resume: register-identity failed for case ${pending.caseId}: ${it.message}")
        }
    }

    private fun splitLegalName(legalName: String): Pair<String, String> {
        val parts = legalName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts.dropLast(1).joinToString(" ") to parts.last()
            parts.size == 1 -> parts[0] to parts[0]
            else -> legalName to legalName
        }
    }

    companion object {
        private const val DECIDED_EVENT = "IdentityVerificationCaseDecided"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 500L
    }
}
