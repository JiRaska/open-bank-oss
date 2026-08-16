// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.domain.model

import java.time.Instant
import java.time.OffsetDateTime

/**
 * A TPP-registry lifecycle event, together with the exact flat JSON envelope that goes on the wire
 * (topic `openbank.tpp.registry.event`).
 *
 * [envelope] is deliberately the whole message body rather than a set of typed fields — the
 * envelope IS the contract a consumer parses. Serialization happens in the infrastructure layer,
 * so this stays framework-free (ADR-0002).
 */
data class TppEvent(
    val eventType: String,
    val aggregateId: java.util.UUID,
    val occurredAt: Instant,
    val envelope: Map<String, Any?>,
)

/**
 * Builds the TPP-registry lifecycle events (issue #4007).
 *
 * Before this existed, `tpp_outbox` had a dispatcher, a backlog gauge, an atomic-claim query,
 * `dispatch-enabled: true`, a `KafkaTppOutboxEventPublisher`, a `KafkaTopic` resource and a write
 * ACL — and **no writer at all**. Unlike `party` and `balance`, there was not even a second direct
 * emitter to make the events arrive anyway: `tpp-events-out` is wired only to the outbox publisher,
 * so nothing has ever been produced to `openbank.tpp.registry.event`.
 *
 * The events are built here rather than in the repository so both the use case and the persistence
 * adapter can see them, and so the *only* way to persist a TPP is to hand over the event that
 * describes the change — [TppRepository.save] and [TppRepository.update] take one as a required
 * parameter, which is why there is no eventless overload to bypass.
 */
object TppEvents {

    const val TPP_REGISTERED = "TPP_REGISTERED"
    const val TPP_BLACKLISTED = "TPP_BLACKLISTED"

    fun registered(entry: TppEntry): TppEvent = TppEvent(
        eventType = TPP_REGISTERED,
        aggregateId = entry.id,
        occurredAt = entry.registeredAt.toInstant(),
        envelope = linkedMapOf(
            "eventType" to TPP_REGISTERED,
            "entryId" to entry.id,
            "tppId" to entry.tppId,
            "name" to entry.name,
            "countryCode" to entry.countryCode,
            "nca" to entry.nca,
            "roles" to entry.roles.map { it.name }.sorted(),
            "status" to entry.status.name,
            "occurredAt" to entry.registeredAt.toInstant(),
        ),
    )

    /**
     * A blacklisting is the event this registry exists to broadcast: psd2-service authorises every
     * TPP call against `checkAuthorization`, so a revoked provider must become visible to anything
     * holding a cached decision. It carries `blacklistReason` because an operator reading a
     * downstream alert needs to know why, and the reason is short free text supplied by that same
     * operator — never customer data.
     */
    fun blacklisted(entry: TppEntry, at: OffsetDateTime): TppEvent = TppEvent(
        eventType = TPP_BLACKLISTED,
        aggregateId = entry.id,
        occurredAt = at.toInstant(),
        envelope = linkedMapOf(
            "eventType" to TPP_BLACKLISTED,
            "entryId" to entry.id,
            "tppId" to entry.tppId,
            "status" to entry.status.name,
            "blacklistReason" to entry.blacklistReason,
            "blacklistedAt" to entry.blacklistedAt?.toInstant(),
            "occurredAt" to at.toInstant(),
        ),
    )
}
