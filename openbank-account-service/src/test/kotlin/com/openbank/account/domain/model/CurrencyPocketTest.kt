// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.money.CurrencyCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CurrencyPocketTest {

    private fun pocket(primary: Boolean, status: PocketStatus = PocketStatus.ACTIVE) = CurrencyPocket(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        currency = CurrencyCode.EUR,
        isPrimary = primary,
        status = status,
        openedAt = Instant.parse("2024-01-15T12:00:00Z"),
        closedAt = null,
        version = 0L,
    )

    @Test
    fun `closing a secondary pocket marks it closed`() {
        val closed = pocket(primary = false).close(Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC))

        assertThat(closed.status).isEqualTo(PocketStatus.CLOSED)
        assertThat(closed.closedAt).isNotNull()
    }

    @Test
    fun `the primary pocket cannot be closed directly`() {
        val fixedClock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
        assertThatThrownBy { pocket(primary = true).close(fixedClock) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("primary pocket")
    }

    @Test
    fun `freeze then unfreeze round-trips an active pocket`() {
        val frozen = pocket(primary = false).freeze()
        assertThat(frozen.status).isEqualTo(PocketStatus.FROZEN)
        assertThat(frozen.isOperable()).isFalse()

        val active = frozen.unfreeze()
        assertThat(active.status).isEqualTo(PocketStatus.ACTIVE)
        assertThat(active.isOperable()).isTrue()
    }
}
