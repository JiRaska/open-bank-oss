// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Follows ADR-0179's `merged_into` pointer so a customer whose party was merged away keeps
 * seeing their own banking data.
 *
 * ## What this fixes
 *
 * A party merge retires the duplicate (`PartyStatus.MERGED` + `merged_into` -> the survivor) and
 * moves nothing else: the Keycloak user of the retired party still carries `party_id = <loser>`
 * in its token, and every customer-edge route keys off that claim. Before this resolver, a merged
 * customer signing in got the retired identity everywhere — an empty account list, no loans, no
 * KYC case, and a profile reading `status: MERGED` — while the survivor's data sat one pointer
 * away that nothing followed.
 *
 * ## Where it sits
 *
 * At the single identity chokepoint (`CustomerEdgeResource.customer()`), so every downstream
 * proxy call is made with the surviving id. Resolving here rather than per-route is the point:
 * there are ~86 routes and they all take the party id from the same place.
 *
 * ## Why a synchronous read and not the event
 *
 * party-service publishes `PARTY_MERGED` on `openbank.party.events`, but customer-edge's
 * `party-events-in` channel is bound to pid's `party.events` topic and has no KafkaUser/mTLS
 * identity in the cluster (see application.yaml). An event-driven redirect map here would be
 * code that never runs. party-service's own REST read is the source of truth and is reachable.
 *
 * ## Failure behaviour: fail-OPEN, deliberately
 *
 * This is on the hot path of every authenticated customer request. If party-service is
 * unreachable, slow, or answers something unexpected, [resolve] returns the claimed id unchanged
 * — i.e. exactly today's behaviour. A merge is not honoured for the duration of the outage; the
 * app does not break. Failing closed here would take the whole customer app down whenever
 * party-service is down, to protect a state (a merged party) that is rare by construction.
 *
 * ## Security note
 *
 * Following the pointer grants the caller access to the SURVIVING party's data. That is the
 * intended meaning of a merge — the two rows are one human — but it means a wrongly-approved
 * merge is an account-takeover primitive. The gate on that is the merge endpoint itself:
 * `POST /api/v1/parties/{id}/merge` is `@Authorize(action = "party.merge")` and `party.merge` is
 * listed in `rules.yaml: four_eyes.actions`, so a merge needs a maker and a different checker.
 * This resolver adds no new authority of its own: it only reads a pointer that endpoint wrote.
 *
 * ## Caching
 *
 * In-process, per replica. A resolved merge is cached for a long time (a merge is irreversible —
 * there is no un-merge endpoint), a "not merged" answer for a short time so a fresh merge is
 * picked up without a redeploy. Bounded in size; on overflow the cache is cleared rather than
 * grown, since a cold cache only costs one upstream read per active customer.
 */
@ApplicationScoped
class PartyMergeResolver(
    private val upstream: UpstreamClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.edge.party-service-url")
    private val partyServiceUrl: String,
    // Kill switch. On by default: a merged customer is broken without it. Flip to false to fall
    // back to "the token's party id is used verbatim" without a rollback.
    @ConfigProperty(name = "openbank.edge.party-merge-follow-enabled", defaultValue = "true")
    private val followEnabled: Boolean,
) {

    private data class Entry(val survivor: UUID, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<UUID, Entry>()

    /**
     * Returns the party id [claimed] should be read as: the surviving party if [claimed] was
     * merged away (following a chain of merges to its end), otherwise [claimed] itself.
     *
     * Never throws — see the fail-open note on the class.
     */
    @Suppress("TooGenericExceptionCaught") // hot path: no upstream failure may reach the caller
    fun resolve(claimed: UUID): UUID {
        if (!followEnabled) return claimed
        val now = Instant.now(clock)
        cache[claimed]?.let { if (it.expiresAt.isAfter(now)) return it.survivor }
        val survivor = try {
            walk(claimed)
        } catch (e: Exception) {
            Log.warnf(
                "party-merge resolve failed for %s (%s: %s) — using the claimed id unchanged",
                claimed,
                e.javaClass.simpleName,
                e.message,
            )
            return claimed
        }
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        val ttl = if (survivor == claimed) UNMERGED_TTL_SECONDS else MERGED_TTL_SECONDS
        cache[claimed] = Entry(survivor, now.plusSeconds(ttl))
        return survivor
    }

    /**
     * Walks the `mergedIntoPartyId` chain from [start] to its end.
     *
     * Bounded two ways because a corrupted pointer must not hang a customer request: a hop
     * ceiling, and a visited set so a cycle (A -> B -> A) terminates at the last id that was not
     * already seen rather than looping. Any non-200 read, or a party that is not MERGED, ends the
     * walk at the current id.
     */
    private fun walk(start: UUID): UUID {
        var current = start
        val visited = mutableSetOf(start)
        repeat(MAX_HOPS) {
            val next = survivorOf(current) ?: return current
            if (!visited.add(next)) {
                Log.warnf("party-merge chain from %s cycles at %s — stopping at %s", start, next, current)
                return current
            }
            current = next
        }
        Log.warnf("party-merge chain from %s exceeded %d hops — stopping at %s", start, MAX_HOPS, current)
        return current
    }

    /**
     * One hop: the party [id] was merged into, or null if it was not merged (or could not be read).
     *
     * Requires BOTH `status == MERGED` and a non-null `mergedIntoPartyId`. Reading only the
     * pointer would follow a field that party-service leaves populated on a row it no longer
     * considers retired; reading only the status would drop the caller's identity on the floor.
     */
    private fun survivorOf(id: UUID): UUID? {
        val response = upstream.get("$partyServiceUrl/api/v1/parties/$id", id.toString())
        if (response.status != Response.Status.OK.statusCode) return null
        val body = response.entity?.toString() ?: return null
        val node = objectMapper.readTree(body)
        if (node.path("status").asText() != MERGED_STATUS) return null
        val pointer = node.path("mergedIntoPartyId").takeIf { !it.isNull && !it.isMissingNode }?.asText()
            ?: return null
        return runCatching { UUID.fromString(pointer) }.getOrNull()
    }

    companion object {
        private const val MERGED_STATUS = "MERGED"

        // A merge is irreversible (there is no un-merge endpoint), so the positive answer can be
        // held for a long time; an hour still bounds the blast radius of a bad cache entry.
        private const val MERGED_TTL_SECONDS = 3600L

        // A "not merged" answer must go stale quickly — this is the case that turns into a merge.
        private const val UNMERGED_TTL_SECONDS = 300L

        // Depth of a merge chain (A merged into B, B later merged into C). Real chains are 0-1
        // hops; the ceiling exists so a corrupted pointer cannot spin.
        private const val MAX_HOPS = 5

        private const val MAX_CACHE_ENTRIES = 50_000
    }
}
