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
 * Best-effort and idempotent-ish (the pending record is deleted after handling). Flag-gated off by
 * default. The plaintext RČ is never available here (never stored) — resume uses name + birthdate.
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
    @Suppress("TooGenericExceptionCaught") // a consumer must never die on a single bad/foreign event
    fun onPartyEvent(payload: String) {
        if (!resumeEnabled) return
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        if (node["eventType"]?.asText() != DECIDED_EVENT) return
        val caseId = node["aggregateId"]?.asText()?.takeIf { it.isNotBlank() } ?: return
        val pending = pendingStore.find(caseId) ?: return // not ours, or expired
        val p = node["payload"]
        val verdict = p?.get("verdict")?.asText() ?: return
        val linkPartyId = p?.get("linkPartyId")?.takeIf { !it.isNull }?.asText()
        try {
            resume(pending, verdict, linkPartyId)
        } catch (e: Exception) {
            Log.error("onboarding resume failed for case=$caseId verdict=$verdict: ${e.message}", e)
        } finally {
            pendingStore.delete(caseId)
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
    }
}
