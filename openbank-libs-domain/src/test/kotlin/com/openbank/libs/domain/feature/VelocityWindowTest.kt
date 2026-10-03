// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Velocity buckets are tumbling on EVENT time (ADR-0140). A bucket that silently used a rolling
 * offset, or truncated to the wrong unit, would put two events of the same hour in different
 * buckets and halve every velocity count — invisible to any test that only checks one instant.
 */
class VelocityWindowTest {

    private fun at(iso: String) = Instant.parse(iso)

    @Test
    fun `H1 truncates to the top of the clock hour`() {
        assertThat(VelocityWindow.H1.bucketStart(at("2026-06-22T10:15:30.123456789Z")))
            .isEqualTo(at("2026-06-22T10:00:00Z"))
    }

    @Test
    fun `H24 truncates to the start of the UTC day`() {
        assertThat(VelocityWindow.H24.bucketStart(at("2026-06-22T10:15:30Z")))
            .isEqualTo(at("2026-06-22T00:00:00Z"))
    }

    @Test
    fun `an instant exactly on a boundary is the start of its own bucket, not the previous one`() {
        val topOfHour = at("2026-06-22T10:00:00Z")
        assertThat(VelocityWindow.H1.bucketStart(topOfHour)).isEqualTo(topOfHour)
        val midnight = at("2026-06-22T00:00:00Z")
        assertThat(VelocityWindow.H24.bucketStart(midnight)).isEqualTo(midnight)
    }

    @Test
    fun `two instants in the same hour share a bucket and the next second does not`() {
        val a = at("2026-06-22T10:00:00Z")
        val b = at("2026-06-22T10:59:59.999999999Z")
        val c = at("2026-06-22T11:00:00Z")
        assertThat(VelocityWindow.H1.bucketStart(a)).isEqualTo(VelocityWindow.H1.bucketStart(b))
        assertThat(VelocityWindow.H1.bucketStart(c)).isNotEqualTo(VelocityWindow.H1.bucketStart(b))
    }

    @Test
    fun `bucketing is idempotent - re-bucketing a bucket start yields itself`() {
        VelocityWindow.entries.forEach { w ->
            val start = w.bucketStart(at("2026-01-01T23:59:59Z"))
            assertThat(w.bucketStart(start)).isEqualTo(start)
        }
    }

    @Test
    fun `H24 buckets an instant before the epoch downwards, never upwards`() {
        val t = at("1969-12-31T23:30:00Z")
        val start = VelocityWindow.H24.bucketStart(t)
        assertThat(start).isEqualTo(at("1969-12-31T00:00:00Z"))
        assertThat(start).isBeforeOrEqualTo(t)
    }
}
