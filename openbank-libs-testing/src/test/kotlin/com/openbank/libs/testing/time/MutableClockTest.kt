// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClockTest {

    @Test
    fun `instant reflects the initial value by default`() {
        val clock = MutableClock(Instant.parse("2026-03-01T12:00:00Z"))
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"))
    }

    @Test
    fun `set jumps to an absolute instant`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        clock.set(Instant.parse("2027-06-15T09:30:00Z"))
        assertThat(clock.instant()).isEqualTo(Instant.parse("2027-06-15T09:30:00Z"))
    }

    @Test
    fun `advance moves forward by a duration, crossing a day boundary`() {
        val clock = MutableClock(Instant.parse("2026-01-01T23:00:00Z"))
        clock.advance(Duration.ofHours(2))
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-02T01:00:00Z"))
    }

    @Test
    fun `zone defaults to UTC and withZone preserves the current instant`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        assertThat(clock.zone).isEqualTo(ZoneId.of("UTC"))

        val zoned = clock.withZone(ZoneOffset.ofHours(2))
        assertThat(zoned.instant()).isEqualTo(clock.instant())
        assertThat(zoned.zone).isEqualTo(ZoneOffset.ofHours(2))
    }

    @Test
    fun `mutating the original after withZone does not affect the derived clock`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val derived = clock.withZone(ZoneOffset.UTC)
        clock.set(Instant.parse("2030-01-01T00:00:00Z"))
        assertThat(derived.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
    }
}
