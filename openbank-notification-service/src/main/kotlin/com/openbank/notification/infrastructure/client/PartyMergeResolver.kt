// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.client

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Follows ADR-0179's `merged_into` pointer so a notification for a merged party's retired id is
 * delivered/keyed against the surviving party, not the retired one (issue #1984 fleet sweep).
 *
 * ## What this fixes
 *
 * A party merge retires the duplicate (`PartyStatus.MERGED` + `merged_into` -> the survivor) and
 * touches nothing else. notification-service stores device tokens, notification preferences and
 * every persisted `Notification` row keyed by `partyId` — and every producer on
 * `openbank.notification.requests` (campaign-service, account-service, sca-service, ...) supplies
 * whatever `partyId` it holds, which for a party merged after that producer last looked it up is
 * the retired id. Before this resolver, a notification for a merged customer was persisted under
 * the retired id, checked against preferences for the retired id, and (for PUSH) fanned out to
 * device tokens registered under the retired id — silently missing the survivor's actual devices
 * and preferences, or in the EMAIL case, looking up the retired party's (possibly stale) address.
 *
 * ## Where it sits
 *
 * At the single identity chokepoint: `NotificationConsumer.dispatch()`, before the request is
 * persisted or fanned out to any channel. Same shape as customer-edge's `PartyMergeResolver`
 * (#3901) — resolve once at the entry point rather than per downstream lookup, because every
 * downstream step (persistence, preference check, device-token lookup, EMAIL address lookup)
 * reads `partyId` off the same request.
 *
 * ## Why a synchronous (request/response) read and not the `PARTY_MERGED` event
 *
 * notification-service already consumes `openbank.party.events` for `PARTY_ERASED`
 * ([com.openbank.notification.infrastructure.kafka.PartyErasureConsumer]), so unlike
 * customer-edge it COULD consume `PARTY_MERGED` too and maintain a redirect table. That was
 * considered and rejected here: a `NotificationRequest` can arrive before the consumer's own
 * projection of a merge that happened seconds earlier catches up (no ordering guarantee across
 * two independent topics/partitions), and the failure mode of a stale redirect table is exactly
 * the bug this fixes — silently keying a live send to the wrong id. A direct read from
 * party-service (already the source of truth this service asks for the EMAIL address via
 * [PartyContactClient]) has no such staleness window. The read is also cheap: notification volume
 * is nowhere near customer-edge's per-request-of-every-authenticated-call rate, so the extra
 * upstream call this adds is proportionate.
 *
 * ## Failure behaviour: fail-OPEN, deliberately
 *
 * This sits ahead of every notification send, including SECURITY-category ones (OTP, SCA, account
 * freeze) that must not be silently dropped by an unrelated party-service hiccup. If party-service
 * is unreachable, slow, or answers something unexpected, [resolve] returns the claimed id
 * unchanged — i.e. exactly today's behaviour (a party is rarely merged, so most sends are
 * unaffected either way). Failing closed would suppress every notification whenever party-service
 * has a bad moment, to protect a state (a merged party) that is rare by construction.
 *
 * ## Security note
 *
 * Following the pointer targets the notification at the SURVIVING party's devices/address/
 * preferences. That is the intended meaning of a merge (the two rows are one human) and adds no
 * new authority: `POST /api/v1/parties/{id}/merge` is `@Authorize(action = "party.merge")` and
 * `party.merge` is listed in `rules.yaml: four_eyes.actions`, so a merge itself needs a maker and
 * a different checker. This resolver only reads a pointer that endpoint wrote.
 *
 * ## Caching
 *
 * In-process, per replica — same shape and TTLs as customer-edge's resolver. A resolved merge is
 * cached for a long time (a merge is irreversible), a "not merged" answer for a short time so a
 * fresh merge is picked up promptly. Bounded in size; on overflow the cache is cleared rather than
 * grown.
 */
@ApplicationScoped
class PartyMergeResolver @Inject constructor(
    @RestClient
    private val partyContactClient: PartyContactClient,
    private val clock: Clock,
    // Kill switch. On by default: a merged party's notifications are misdirected without it.
    // Flip to false to fall back to "the request's partyId is used verbatim" without a rollback.
    @ConfigProperty(name = "openbank.notification.party-merge-follow-enabled", defaultValue = "true")
    private val followEnabled: Boolean,
) {

    private data class Entry(val survivor: UUID, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<UUID, Entry>()

    /**
     * Returns a [Uni] of the party id [claimed] should be read as: the surviving party if
     * [claimed] was merged away (following a chain of merges to its end), otherwise [claimed]
     * itself.
     *
     * Never fails — see the fail-open note on the class.
     */
    fun resolve(claimed: UUID): Uni<UUID> {
        if (!followEnabled) return Uni.createFrom().item(claimed)
        val now = Instant.now(clock)
        cache[claimed]?.let { if (it.expiresAt.isAfter(now)) return Uni.createFrom().item(it.survivor) }
        return walk(claimed, claimed, mutableSetOf(claimed), 0)
            .onFailure().recoverWithItem { _: Throwable -> claimed }
            .invoke { survivor -> remember(claimed, survivor, now) }
    }

    /**
     * Walks the `mergedIntoPartyId` chain from [start] (tracked only for the log lines) starting
     * at [current], with [visited] the hop set so far and [hop] the hop count.
     *
     * Bounded two ways because a corrupted pointer must not hang a notification send: a hop
     * ceiling, and a visited set so a cycle (A -> B -> A) terminates at the last id that was not
     * already seen rather than looping. Any non-OK read, unparseable body, or a party that is not
     * MERGED (or MERGED with no pointer), ends the walk at the current id.
     */
    private fun walk(start: UUID, current: UUID, visited: MutableSet<UUID>, hop: Int): Uni<UUID> {
        if (hop >= MAX_HOPS) {
            Log.warnf("party-merge chain from %s exceeded %d hops — stopping at %s", start, MAX_HOPS, current)
            return Uni.createFrom().item(current)
        }
        return partyContactClient.getPartyIdentity(current)
            .chain { identity ->
                val next = identity.mergedIntoPartyId
                if (identity.status != MERGED_STATUS || next == null) {
                    Uni.createFrom().item(current)
                } else if (!visited.add(next)) {
                    Log.warnf("party-merge chain from %s cycles at %s — stopping at %s", start, next, current)
                    Uni.createFrom().item(current)
                } else {
                    walk(start, next, visited, hop + 1)
                }
            }
    }

    private fun remember(claimed: UUID, survivor: UUID, now: Instant) {
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        val ttl = if (survivor == claimed) UNMERGED_TTL_SECONDS else MERGED_TTL_SECONDS
        cache[claimed] = Entry(survivor, now.plusSeconds(ttl))
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
