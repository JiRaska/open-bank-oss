// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.openbank.treasury.application.port.out.PositionClose
import com.openbank.treasury.application.port.out.requestFingerprint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** #14310: the fingerprint a day-close Idempotency-Key is bound to. */
class PositionCloseFingerprintTest {
    private fun close(
        date: LocalDate = LocalDate.of(2031, 3, 3),
        book: String? = "B1",
        scenario: String? = null,
        special: Boolean = false,
        by: String = "olga",
    ) = PositionClose(UUID.randomUUID(), date, book, scenario, special, by, Instant.now(), "{}")

    @Test
    fun `identical requests share a fingerprint regardless of snapshot id, time and payload`() {
        assertThat(close().requestFingerprint()).isEqualTo(close().copy(positionsJson = "[1]").requestFingerprint())
    }

    @Test
    fun `date, book, scenario, endpoint and actor each change the fingerprint`() {
        val base = close().requestFingerprint()
        listOf(
            close(date = LocalDate.of(2031, 3, 4)),
            close(book = "B2"),
            close(book = null),
            close(scenario = "STRESS"),
            close(special = true),
            close(by = "other"),
        ).forEach { assertThat(it.requestFingerprint()).isNotEqualTo(base) }
    }

    @Test
    fun `field boundaries cannot be shifted to forge a collision`() {
        assertThat(close(book = "ab", scenario = "c").requestFingerprint())
            .isNotEqualTo(close(book = "a", scenario = "bc").requestFingerprint())
    }
}
