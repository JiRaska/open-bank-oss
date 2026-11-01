// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnsettledOutcomeTest {
    @Test
    fun `wire labels are byte-identical to the values the alerts and dashboards select on`() {
        // Literal on purpose: deriving the expectation from the enum would move with it (#13669).
        assertThat(UnsettledOutcome.entries.map { it.label })
            .containsExactly("mismatch", "failed-replay", "conflict", "unexpected-status", "transport-error")
        assertThat(UnsettledOutcome.METRIC).isEqualTo("openbank.payment.unsettled")
    }
}
