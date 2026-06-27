// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.domain.refund

import com.openbank.sdd.domain.model.SddScheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RefundPolicyTest {

    private val debit = LocalDate.parse("2026-01-10")

    @Test
    fun `authorised Core within 8 weeks is an unconditional refund`() {
        val d = RefundPolicy.assess(SddScheme.CORE, debit, debit.plusDays(56), authorised = true)
        assertThat(d).isInstanceOf(RefundDecision.Eligible::class.java)
        assertThat((d as RefundDecision.Eligible).kind).isEqualTo(RefundKind.UNCONDITIONAL)
    }

    @Test
    fun `authorised Core beyond 8 weeks is ineligible`() {
        val d = RefundPolicy.assess(SddScheme.CORE, debit, debit.plusDays(57), authorised = true)
        assertThat(d).isInstanceOf(RefundDecision.Ineligible::class.java)
    }

    @Test
    fun `authorised B2B never carries a post-settlement refund`() {
        val d = RefundPolicy.assess(SddScheme.B2B, debit, debit.plusDays(1), authorised = true)
        assertThat(d).isInstanceOf(RefundDecision.Ineligible::class.java)
    }

    @Test
    fun `an unauthorised collection within 13 months is refundable regardless of scheme`() {
        val d = RefundPolicy.assess(SddScheme.B2B, debit, debit.plusMonths(13), authorised = false)
        assertThat(d).isInstanceOf(RefundDecision.Eligible::class.java)
        assertThat((d as RefundDecision.Eligible).kind).isEqualTo(RefundKind.UNAUTHORISED)
    }

    @Test
    fun `an unauthorised collection beyond 13 months is ineligible`() {
        val d = RefundPolicy.assess(SddScheme.CORE, debit, debit.plusMonths(13).plusDays(1), authorised = false)
        assertThat(d).isInstanceOf(RefundDecision.Ineligible::class.java)
    }

    @Test
    fun `a debit dated in the future is ineligible`() {
        val d = RefundPolicy.assess(SddScheme.CORE, debit, debit.minusDays(1), authorised = true)
        assertThat(d).isInstanceOf(RefundDecision.Ineligible::class.java)
    }
}
