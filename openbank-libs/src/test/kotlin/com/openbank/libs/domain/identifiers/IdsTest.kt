// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.identifiers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdsTest {

    /** The 48-bit Unix-millisecond timestamp held in the high bits of a UUIDv7. */
    private fun unixTsMs(id: java.util.UUID): Long = id.mostSignificantBits ushr 16

    @Test
    fun `newId produces a version 7 UUID`() {
        assertThat(Ids.newId().version()).isEqualTo(7)
    }

    @Test
    fun `newId produces the RFC 4122 IETF variant`() {
        // UUID.variant() == 2 is the 10xx variant mandated by RFC 9562 / 4122.
        assertThat(Ids.newId().variant()).isEqualTo(2)
    }

    @Test
    fun `randomId produces a version 4 random UUID, distinct from the time-ordered newId`() {
        // The intent split (ADR-0106): randomId is deliberately NOT time-ordered.
        assertThat(Ids.randomId().version()).isEqualTo(4)
        assertThat(Ids.newId().version()).isEqualTo(7)
    }

    @Test
    fun `randomId is unique across many calls`() {
        val n = 10_000
        assertThat((1..n).map { Ids.randomId() }.toSet()).hasSize(n)
    }

    @Test
    fun `newId is unique across many calls`() {
        val n = 10_000
        val ids = (1..n).map { Ids.newId() }.toSet()
        assertThat(ids).hasSize(n)
    }

    @Test
    fun `newId embeds a timestamp close to now`() {
        val before = System.currentTimeMillis()
        val ts = unixTsMs(Ids.newId())
        val after = System.currentTimeMillis()
        // Allow a small skew window; the embedded ms must bracket wall-clock at generation.
        assertThat(ts).isBetween(before - 1_000, after + 1_000)
    }

    @Test
    fun `newId is time-ordered — timestamps are non-decreasing across sequential calls`() {
        val timestamps = (1..1_000).map { unixTsMs(Ids.newId()) }
        assertThat(timestamps).isSorted // monotonic non-decreasing → right-edge B-tree locality
    }

    @Test
    fun `EntityId random factories now mint UUIDv7`() {
        // The whole point of wiring Ids into the typesafe factories (ADR-0106).
        assertThat(AccountId.random().value.version()).isEqualTo(7)
        assertThat(TransactionId.random().value.version()).isEqualTo(7)
        assertThat(PaymentId.random().value.version()).isEqualTo(7)
        assertThat(LoanId.random().value.version()).isEqualTo(7)
    }
}
