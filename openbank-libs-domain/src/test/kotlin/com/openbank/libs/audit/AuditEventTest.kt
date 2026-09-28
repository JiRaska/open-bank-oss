// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The audit envelope is the evidentiary record (GDPR Art. 30, DORA Art. 17), so `timestamp`
 * has to answer "when did this happen" for a caller that did not pass one — and 23 of the 25
 * fleet construction sites do not pass one.
 *
 * These assert **recency**, deliberately, not non-nullity: `timestamp` defaulted to
 * `Instant.EPOCH`, and every non-null / not-null-value assertion passes against 1970-01-01.
 * That is exactly what let the default survive review.
 */
class AuditEventTest {

    private fun event(timestamp: Instant? = null) = if (timestamp == null) {
        AuditEvent(
            actorId = "party-1",
            actorType = "CUSTOMER",
            operation = "account.party.created",
            resourceType = "account",
            resourceId = "acc-1",
        )
    } else {
        AuditEvent(
            actorId = "party-1",
            actorType = "CUSTOMER",
            operation = "account.party.created",
            resourceType = "account",
            resourceId = "acc-1",
            timestamp = timestamp,
        )
    }

    @Test
    fun `an event built without an explicit timestamp is stamped at construction`() {
        val before = Instant.now()

        val stamped = event().timestamp

        assertThat(stamped).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1))
        assertThat(Duration.between(stamped, Instant.now()).abs())
            .describedAs("audit timestamp must be recent, not the %s epoch default", Instant.EPOCH)
            .isLessThan(Duration.ofMinutes(1))
    }

    @Test
    fun `an explicit timestamp is preserved`() {
        val explicit = Instant.parse("2026-01-02T03:04:05Z")

        assertThat(event(explicit).timestamp).isEqualTo(explicit)
    }

    // Every getter below was NO_COVERAGE under pitest's EmptyObjectReturnValsMutator (replacing
    // a String getter's return with "", a List getter's with emptyList(), a Map getter's with
    // emptyMap()) — nothing in this file ever read the field back, only the four fields required
    // to construct an event at all. Non-blank/non-default literals + exact-equality assertions on
    // every field are what actually kills that mutant class; a `describedAs`-only or non-null
    // check would pass against the emptied value just as readily as against the real one.
    @Test
    fun `a fully populated event round-trips every field exactly`() {
        val event = AuditEvent(
            actorId = "party-42",
            actorType = "CUSTOMER",
            operation = "payment.sepa.created",
            resourceType = "payment",
            resourceId = "pay-99",
            ipAddress = "203.0.113.7",
            userAgent = "openbank-app/1.0",
            result = AuditResult.DENIED,
            traceId = "trace-abc",
            channel = AuditChannel.MCP,
            actChain = listOf("agent:copilot", "operator:jdoe"),
            sessionId = "session-xyz",
            payload = mapOf("field" to "value"),
        )

        assertThat(event.actorId).isEqualTo("party-42")
        assertThat(event.actorType).isEqualTo("CUSTOMER")
        assertThat(event.operation).isEqualTo("payment.sepa.created")
        assertThat(event.resourceType).isEqualTo("payment")
        assertThat(event.resourceId).isEqualTo("pay-99")
        assertThat(event.ipAddress).isEqualTo("203.0.113.7")
        assertThat(event.userAgent).isEqualTo("openbank-app/1.0")
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.traceId).isEqualTo("trace-abc")
        assertThat(event.channel).isEqualTo(AuditChannel.MCP)
        assertThat(event.actChain).containsExactly("agent:copilot", "operator:jdoe")
        assertThat(event.sessionId).isEqualTo("session-xyz")
        assertThat(event.payload).containsExactlyEntriesOf(mapOf("field" to "value"))
    }

    @Test
    fun `optional fields default to absent, not to a value that could be mistaken for real data`() {
        val minimal = event()

        assertThat(minimal.ipAddress).isNull()
        assertThat(minimal.userAgent).isNull()
        assertThat(minimal.traceId).isNull()
        assertThat(minimal.channel).isNull()
        assertThat(minimal.sessionId).isNull()
        assertThat(minimal.actChain).isEmpty()
        assertThat(minimal.payload).isEmpty()
        assertThat(minimal.result).isEqualTo(AuditResult.SUCCESS)
    }
}
