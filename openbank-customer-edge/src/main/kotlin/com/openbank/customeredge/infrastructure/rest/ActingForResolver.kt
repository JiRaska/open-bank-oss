// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Profile switching (ADR-0284 D4). A customer request may carry `X-Acting-For: <entityPartyId>`;
 * when it does, every downstream call is made AS that entity — but only after party-service has
 * confirmed an ACTIVE representation mandate from the token's human to that entity.
 *
 * ## Fail-CLOSED, deliberately — the opposite of [PartyMergeResolver]
 *
 * A merge that is not honoured shows a customer LESS (their own retired record); an acting-for
 * that is not verified would show them someone else's company. So an unreachable party-service,
 * a non-200, an unparseable body or a missing mandate all answer 403. The personal profile is
 * unaffected: a request without the header never touches this class.
 *
 * ## Caching
 *
 * In-process, per replica, short. A positive answer is cached for [positiveTtl] so the switcher
 * costs one upstream read per (human, entity) per minute, not one per request; a negative answer
 * is cached briefly too so a client hammering a forbidden entity does not turn into party-service
 * load. Revocation therefore takes effect within [positiveTtl] — acceptable for a profile switch
 * whose money-moving routes are SCA-bound on their own. Bounded; cleared on overflow.
 */
@ApplicationScoped
class ActingForResolver(
    private val upstream: UpstreamClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.edge.party-service-url")
    private val partyServiceUrl: String,
    @ConfigProperty(name = "openbank.edge.acting-for-enabled", defaultValue = "true")
    private val enabled: Boolean,
) {

    private data class Verdict(val allowed: Boolean, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<Pair<UUID, UUID>, Verdict>()

    /**
     * The party every downstream call should be scoped to: [claimed] itself when no header is
     * present, the entity when the header names one the human may act for, 403 otherwise.
     */
    fun resolve(claimed: UUID, actingForHeader: String?): UUID {
        val raw = actingForHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return claimed
        val entity = runCatching { UUID.fromString(raw) }.getOrNull()
        val refusal = when {
            !enabled -> "profile switching is disabled"
            entity == null -> "X-Acting-For is not a party id"
            entity != claimed && !mayActFor(claimed, entity) -> "no active mandate to act for party $entity"
            else -> null
        }
        // One throw, one place: every refusal path is a 403, so no branch can become a fall-open
        // by accident — which is the whole security property of this class.
        if (refusal != null) throw ForbiddenException(refusal)
        return entity ?: claimed
    }

    /** Fresh, bounded ACTIVE mandate identities; never rely on the profile-switch cache for money. */
    fun activeMandateIds(agent: UUID, entity: UUID): List<UUID> {
        if (!enabled || agent == entity) throw ForbiddenException("An acting-for mandate is required")
        val response = try {
            upstream.get("$partyServiceUrl/api/v1/parties/$agent/acting-for", agent.toString())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.warn("Standing-order mandate validation unavailable", e)
            throw jakarta.ws.rs.ServiceUnavailableException("Mandate validation unavailable")
        }
        if (response.status != OK) throw jakarta.ws.rs.ServiceUnavailableException("Mandate validation unavailable")
        val profiles = runCatching { objectMapper.readTree(response.entity?.toString() ?: "") }.getOrNull()
            ?.takeIf { it.isArray }
            ?: throw jakarta.ws.rs.ServiceUnavailableException("Mandate validation unavailable")
        val matching = profiles.filter { it.path("partyId").asText() == entity.toString() }
        if (matching.isEmpty() || matching.size > MAX_ACTIVE_MANDATES) {
            throw ForbiddenException("Active acting-for mandate inventory is invalid")
        }
        val ids = matching.map { profile ->
            val mandate = profile.path("mandate")
            val id = runCatching { UUID.fromString(mandate.path("id").asText()) }.getOrNull()
            if (
                id == null ||
                mandate.path("principalPartyId").asText() != entity.toString() ||
                mandate.path("agentPartyId").asText() != agent.toString() ||
                mandate.path("status").asText() != "ACTIVE"
            ) {
                throw ForbiddenException("Acting-for mandate identity is invalid")
            }
            id
        }
        if (ids.toSet().size != ids.size) throw ForbiddenException("Duplicate acting-for mandate identity")
        return ids.sortedBy { it.toString() }
    }

    /** Stable selection for a NEW instruction; existing instructions use set membership. */
    fun activeMandateId(agent: UUID, entity: UUID): UUID = activeMandateIds(agent, entity).first()

    /** The entities [agent] may switch to, straight from party-service (no cache — this IS the list the cache is derived from). */
    fun profilesOf(agent: UUID): List<Map<String, Any?>> {
        val response = upstream.get("$partyServiceUrl/api/v1/parties/$agent/acting-for", agent.toString())
        if (response.status != OK) return emptyList()
        val body = response.entity as? String ?: return emptyList()
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        val now = Instant.now(clock)
        return node.mapNotNull { p ->
            val id = runCatching { UUID.fromString(p.path("partyId").asText()) }.getOrNull() ?: return@mapNotNull null
            cache[agent to id] = Verdict(true, now.plus(positiveTtl))
            mapOf(
                "partyId" to id,
                "partyType" to p.path("partyType").asText(null),
                "legalName" to p.path("legalName").asText(null),
                "tradingName" to p.path("tradingName").takeIf { !it.isNull }?.asText(),
                "status" to p.path("status").asText(null),
                "kycStatus" to p.path("kycStatus").asText(null),
                "registrationNumber" to p.path("registrationNumber").takeIf { !it.isNull }?.asText(),
                "registrationCountry" to p.path("registrationCountry").takeIf { !it.isNull }?.asText(),
                "legalForm" to p.path("legalForm").takeIf { !it.isNull }?.asText(),
                "role" to p.path("mandate").path("role").asText(null),
                "authority" to p.path("mandate").path("authority").asText(null),
            )
        }
    }

    /** Inbox aggregation must never turn a mandate lookup outage into a false empty company feed. */
    fun profilesOfStrict(agent: UUID): List<Map<String, Any?>> = profilesOfStrictWithin(agent, null)

    /** The inventory read and parsing share the caller's aggregate deadline. */
    fun profilesOfStrict(agent: UUID, deadlineNanos: Long): List<Map<String, Any?>> =
        profilesOfStrictWithin(agent, deadlineNanos)

    @Suppress("ThrowsCount") // Each malformed mandate response must fail the aggregate closed.
    private fun profilesOfStrictWithin(agent: UUID, deadlineNanos: Long?): List<Map<String, Any?>> {
        if (!enabled) return emptyList()
        val response = try {
            val url = "$partyServiceUrl/api/v1/parties/$agent/acting-for"
            if (deadlineNanos == null) {
                upstream.get(url, agent.toString())
            } else {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
                upstream.get(url, agent.toString(), TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.warn("Notification mandate inventory unavailable", e)
            throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
        }
        if (response.status != OK) throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
        val body = response.entity as? String
            ?: throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
        val node = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?.takeIf { it.isArray }
            ?: throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
        return node.map { profile ->
            if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
            }
            val id = runCatching { UUID.fromString(profile.path("partyId").asText()) }.getOrNull()
                ?: throw jakarta.ws.rs.ServiceUnavailableException("Notification profiles unavailable")
            mapOf("partyId" to id)
        }
    }

    private fun mayActFor(agent: UUID, entity: UUID): Boolean {
        val now = Instant.now(clock)
        cache[agent to entity]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.allowed }
        if (cache.size > MAX_ENTRIES) cache.clear()
        val allowed = try {
            profilesOf(agent).any { it["partyId"] == entity }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.warnf(e, "acting-for check for %s -> %s failed; refusing", agent, entity)
            false
        }
        cache[agent to entity] = Verdict(allowed, now.plus(if (allowed) positiveTtl else negativeTtl))
        return allowed
    }

    internal fun invalidate(agent: UUID) {
        cache.keys.removeIf { it.first == agent }
    }

    private companion object {
        const val OK = 200
        const val MAX_ENTRIES = 10_000
        const val MAX_ACTIVE_MANDATES = 16
        val positiveTtl: Duration = Duration.ofSeconds(60)
        val negativeTtl: Duration = Duration.ofSeconds(15)
    }
}
