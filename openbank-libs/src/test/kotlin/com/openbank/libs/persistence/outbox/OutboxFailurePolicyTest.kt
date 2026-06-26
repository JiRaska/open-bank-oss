// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxFailurePolicyTest {

    @Test
    fun `stays FAILED below the attempt cap`() {
        assertThat(OutboxFailurePolicy.statusAfterFailure(1)).isEqualTo(OutboxStatus.FAILED)
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS - 1))
            .isEqualTo(OutboxStatus.FAILED)
    }

    @Test
    fun `transitions to DEAD at and beyond the attempt cap`() {
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS))
            .isEqualTo(OutboxStatus.DEAD)
        assertThat(OutboxFailurePolicy.statusAfterFailure(OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS + 5))
            .isEqualTo(OutboxStatus.DEAD)
    }

    @Test
    fun `respects a custom cap`() {
        assertThat(OutboxFailurePolicy.statusAfterFailure(attemptCount = 2, maxAttempts = 3))
            .isEqualTo(OutboxStatus.FAILED)
        assertThat(OutboxFailurePolicy.statusAfterFailure(attemptCount = 3, maxAttempts = 3))
            .isEqualTo(OutboxStatus.DEAD)
    }
}
