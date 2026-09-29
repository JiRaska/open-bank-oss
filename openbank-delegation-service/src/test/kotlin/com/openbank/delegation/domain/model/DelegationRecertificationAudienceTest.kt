// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class DelegationRecertificationAudienceTest {

    private val from = OffsetDateTime.parse("2026-09-08T10:00:00Z")

    @Test
    fun `confirmed cadence follows the explicit audience policy`() {
        assertThat(DelegationRecertificationAudience.FOP.nextDueAt(from)).isEqualTo(from.plusMonths(12))
        assertThat(DelegationRecertificationAudience.SME.nextDueAt(from)).isEqualTo(from.plusMonths(6))
        assertThat(DelegationRecertificationAudience.CORPORATE.nextDueAt(from)).isEqualTo(from.plusMonths(3))
    }
}
