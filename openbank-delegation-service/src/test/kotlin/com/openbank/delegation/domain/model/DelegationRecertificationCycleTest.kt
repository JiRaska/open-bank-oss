// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationRecertificationCycleTest {
    private val grantor = UUID.randomUUID()
    private val now = OffsetDateTime.of(2026, 9, 8, 9, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `confirmation records review evidence and never alters the lifecycle revision`() {
        val pending = cycle()

        val confirmed = pending.confirm(grantor, now)

        assertThat(confirmed.status).isEqualTo(DelegationRecertificationStatus.CONFIRMED)
        assertThat(confirmed.confirmedAt).isEqualTo(now)
        assertThat(confirmed.confirmedBy).isEqualTo(grantor)
        assertThat(confirmed.expectedLifecycleRevision).isEqualTo(pending.expectedLifecycleRevision)
    }

    @Test
    fun `confirmation is one-time and grantor-only`() {
        val pending = cycle()

        assertThatThrownBy { pending.confirm(UUID.randomUUID(), now) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { pending.confirm(grantor, now).confirm(grantor, now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun cycle() = DelegationRecertificationCycle(
        delegationId = UUID.randomUUID(),
        grantorPartyId = grantor,
        expectedLifecycleRevision = 7,
        audience = DelegationRecertificationAudience.SME,
        sequence = 1,
        dueAt = now.minusDays(1),
        createdAt = now.minusDays(1),
    )
}
