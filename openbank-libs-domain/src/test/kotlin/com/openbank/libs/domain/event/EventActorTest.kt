// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The canonical spelling of a system-originated actor (#3994).
 *
 * Every assertion here is on an EXACT string. That is the point: the audit trail groups and
 * prefix-matches on this value, so "it is non-null" or "it contains system" would pass against a
 * value no downstream query can use — the same failure shape as asserting `isNotNull()` against
 * `Instant.EPOCH`, or against the four-character string `"null"` #4307 removed from `actor_id`.
 */
class EventActorTest {

    @Test
    fun `a system actor id names the service and the mechanism, in that order`() {
        assertThat(EventActor.system("balance-service", "ledger-projection"))
            .isEqualTo("system:balance-service:ledger-projection")
    }

    @Test
    fun `a system actor id can never be mistaken for a person`() {
        val id = EventActor.system("statement-service", "period-close")

        assertThat(EventActor.isSystem(id)).isTrue()
        // Not a UUID, not a Keycloak subject, not an email — so the GDPR Art. 15 access log and the
        // ADR-0226 cross-channel person query can exclude it with one prefix match.
        assertThat(id).startsWith("system:")
        assertThat(id).doesNotContain("@")
    }

    @Test
    fun `absence is not a system actor - the two states this issue exists to separate`() {
        // "we failed to record who did this" must stay distinguishable from "nobody did this".
        assertThat(EventActor.isSystem(null)).isFalse()
        assertThat(EventActor.isSystem("")).isFalse()
        assertThat(EventActor.isSystem("7d13ecc0-443b-456e-a504-dd8999000000")).isFalse()
    }

    @Test
    fun `a blank mechanism is rejected rather than silently producing a useless id`() {
        // "some automation in balance-service" is not an answer anyone can act on, and an optional
        // mechanism is how every call site ends up omitting it.
        assertThatThrownBy { EventActor.system("balance-service", " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EventActor.system("", "period-close") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the wire keys are the ones AuditConsumer reads`() {
        // AuditConsumer resolves the actor from `requestedBy` / `actorId` / `initiatedByPartyId`
        // and the type from `actorType`. A different spelling here stores NULL, silently.
        assertThat(EventActor.FIELD_ACTOR_ID).isEqualTo("actorId")
        assertThat(EventActor.FIELD_ACTOR_TYPE).isEqualTo("actorType")
        assertThat(EventActor.TYPE_SYSTEM).isEqualTo("SYSTEM")
    }
}
