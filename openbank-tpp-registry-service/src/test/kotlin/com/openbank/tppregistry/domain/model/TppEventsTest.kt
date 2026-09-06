// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The envelope IS the wire contract for `openbank.tpp.registry.event`, so these assert the exact
 * key set, the exact values and their ordering — a renamed or dropped key must fail here.
 */
class TppEventsTest {

    private val registeredAt = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC)

    private fun entry(
        id: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        roles: Set<TppRole> = setOf(TppRole.PISP, TppRole.AISP),
        status: TppStatus = TppStatus.ACTIVE,
        blacklistedAt: OffsetDateTime? = null,
        blacklistReason: String? = null,
    ) = TppEntry(
        id = id,
        tppId = "CZ-CNB-123456",
        name = "Acme Payments",
        countryCode = "CZ",
        nca = "CNB",
        roles = roles,
        status = status,
        qwacSubjectDn = null,
        qsealSubjectDn = null,
        qwacExpiresAt = null,
        qsealExpiresAt = null,
        registeredAt = registeredAt,
        updatedAt = registeredAt,
        blacklistedAt = blacklistedAt,
        blacklistReason = blacklistReason,
    )

    @Test
    fun `registered envelope carries exactly the documented keys in order`() {
        val event = TppEvents.registered(entry())

        assertThat(event.envelope.keys).containsExactly(
            "eventType", "entryId", "tppId", "name", "countryCode", "nca", "roles", "status", "occurredAt",
        )
    }

    @Test
    fun `registered sorts roles so the wire order is stable regardless of set iteration order`() {
        val forward = TppEvents.registered(entry(roles = setOf(TppRole.PISP, TppRole.AISP, TppRole.PIISP)))
        val reversed = TppEvents.registered(entry(roles = setOf(TppRole.PIISP, TppRole.AISP, TppRole.PISP)))

        assertThat(forward.envelope["roles"]).isEqualTo(listOf("AISP", "PIISP", "PISP"))
        assertThat(forward.envelope["roles"]).isEqualTo(reversed.envelope["roles"])
    }

    @Test
    fun `registered takes eventType, aggregateId and occurredAt from the entry`() {
        val id = UUID.randomUUID()
        val event = TppEvents.registered(entry(id = id))

        assertThat(event.eventType).isEqualTo("TPP_REGISTERED")
        assertThat(event.aggregateId).isEqualTo(id)
        assertThat(event.occurredAt).isEqualTo(registeredAt.toInstant())
        assertThat(event.envelope["eventType"]).isEqualTo("TPP_REGISTERED")
        assertThat(event.envelope["entryId"]).isEqualTo(id)
        assertThat(event.envelope["occurredAt"]).isEqualTo(registeredAt.toInstant())
        assertThat(event.envelope["tppId"]).isEqualTo("CZ-CNB-123456")
        assertThat(event.envelope["countryCode"]).isEqualTo("CZ")
        assertThat(event.envelope["nca"]).isEqualTo("CNB")
        assertThat(event.envelope["status"]).isEqualTo("ACTIVE")
    }

    @Test
    fun `blacklisted envelope carries the reason and the blacklisting instant`() {
        val at = OffsetDateTime.of(2026, 5, 6, 7, 8, 9, 0, ZoneOffset.UTC)
        val blacklisted = entry(
            status = TppStatus.BLACKLISTED,
            blacklistedAt = at,
            blacklistReason = "licence revoked by CNB",
        )

        val event = TppEvents.blacklisted(blacklisted, at)

        assertThat(event.eventType).isEqualTo("TPP_BLACKLISTED")
        assertThat(event.occurredAt).isEqualTo(at.toInstant())
        assertThat(event.envelope.keys).containsExactly(
            "eventType", "entryId", "tppId", "status", "blacklistReason", "blacklistedAt", "occurredAt",
        )
        assertThat(event.envelope["status"]).isEqualTo("BLACKLISTED")
        assertThat(event.envelope["blacklistReason"]).isEqualTo("licence revoked by CNB")
        assertThat(event.envelope["blacklistedAt"]).isEqualTo(at.toInstant())
    }

    @Test
    fun `blacklisted uses the supplied instant, not the entry's own blacklistedAt`() {
        val stamped = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val at = OffsetDateTime.of(2026, 2, 2, 0, 0, 0, 0, ZoneOffset.UTC)

        val event = TppEvents.blacklisted(entry(blacklistedAt = stamped), at)

        assertThat(event.envelope["occurredAt"]).isEqualTo(at.toInstant())
        assertThat(event.envelope["blacklistedAt"]).isEqualTo(stamped.toInstant())
    }

    @Test
    fun `blacklisted tolerates a null blacklistedAt rather than throwing`() {
        val at = OffsetDateTime.of(2026, 2, 2, 0, 0, 0, 0, ZoneOffset.UTC)

        val event = TppEvents.blacklisted(entry(blacklistedAt = null), at)

        assertThat(event.envelope).containsKey("blacklistedAt")
        assertThat(event.envelope["blacklistedAt"]).isNull()
    }
}
