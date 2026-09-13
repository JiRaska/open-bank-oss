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
        val positiveTtl: Duration = Duration.ofSeconds(60)
        val negativeTtl: Duration = Duration.ofSeconds(15)
    }
}
