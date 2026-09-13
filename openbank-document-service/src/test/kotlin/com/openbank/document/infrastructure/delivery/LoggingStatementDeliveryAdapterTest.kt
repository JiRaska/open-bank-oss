// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.delivery

import com.openbank.document.application.port.out.DeliveryOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one thing this stub must never do is report DELIVERED. The caller keys a 400-day
 * idempotency record off a DELIVERED outcome, so a stub claiming success would suppress the
 * delivery a real channel would later have made (the `PushResult.skipped()` shape, ADR-0252
 * phase 0).
 */
class LoggingStatementDeliveryAdapterTest {

    private val adapter = LoggingStatementDeliveryAdapter()

    @Test
    fun `the stub reports SKIPPED, never DELIVERED`() {
        val outcome = adapter.deliver(
            partyRef = "party-1",
            documentBytes = ByteArray(64),
            contentType = "application/pdf",
            subject = "Annual fee summary 2025",
        )

        assertThat(outcome).isEqualTo(DeliveryOutcome.SKIPPED)
        assertThat(outcome).isNotEqualTo(DeliveryOutcome.DELIVERED)
    }

    @Test
    fun `an empty document is still SKIPPED and does not throw`() {
        assertThat(adapter.deliver("party-1", ByteArray(0), "application/pdf", "")).isEqualTo(DeliveryOutcome.SKIPPED)
    }
}
