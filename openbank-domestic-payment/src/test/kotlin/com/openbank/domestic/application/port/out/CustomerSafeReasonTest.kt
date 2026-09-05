// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticRejectReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The reason a customer is told a payment failed is the one string in this slice that can do harm.
 *
 * [DomesticRejectReason] mixes reasons a customer should simply be told with three that name a
 * financial-crime control applied to them. The enum constant name would go straight onto a lock
 * screen, so these tests are written against disclosure, not against wording.
 */
class CustomerSafeReasonTest {

    /** The three that must never be disclosed, named once here so the test reads as the rule. */
    private val sensitive = setOf(
        DomesticRejectReason.SANCTIONS_HIT,
        DomesticRejectReason.AML_HOLD,
        DomesticRejectReason.FRAUD_SUSPECTED,
    )

    /**
     * Belt and braces behind the call site: even if a sensitive reason ever reached this mapper,
     * nothing it renders may hint at the control. Checks every reason, not only the three, so a
     * newly added one cannot leak its own name either.
     */
    @Test
    fun `no reason ever discloses a financial-crime control`() {
        val forbidden = listOf("sanction", "aml", "money launder", "fraud", "screen", "block", "hit", "hold")
        (DomesticRejectReason.entries + null).forEach { reason ->
            val text = customerSafeReason(reason).lowercase()
            forbidden.forEach { word ->
                assertThat(text)
                    .describedAs("reason %s must not disclose a control (matched '%s')", reason, word)
                    .doesNotContain(word)
            }
        }
    }

    /** All three collapse to one sentence: a difference between them is itself a disclosure. */
    @Test
    fun `the sensitive reasons are indistinguishable from each other`() {
        assertThat(sensitive.map { customerSafeReason(it) }.toSet()).hasSize(1)
    }

    /**
     * The safe ones must NOT collapse — that is the whole value of telling someone at all. Someone
     * whose payment bounced for insufficient funds acts differently from someone whose recipient
     * closed their account.
     */
    @Test
    fun `each safe reason reads differently and names something actionable`() {
        val safe = DomesticRejectReason.entries.filterNot { it in sensitive }
        val texts = safe.map { customerSafeReason(it) }

        assertThat(texts.toSet()).hasSameSizeAs(safe)
        texts.forEach { assertThat(it).isNotBlank() }
    }

    /** A null reason is a rejection with no recorded cause; it must still render a sentence. */
    @Test
    fun `a missing reason still renders`() {
        assertThat(customerSafeReason(null)).isNotBlank()
    }
}
