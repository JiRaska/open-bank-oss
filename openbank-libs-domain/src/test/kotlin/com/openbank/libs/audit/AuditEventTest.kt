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
}
