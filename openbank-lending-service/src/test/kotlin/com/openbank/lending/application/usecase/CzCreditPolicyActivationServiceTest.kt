// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.CzCreditPolicyStore
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.libs.decision.PolicyAttribute
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.decision.PolicyOperator
import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.decision.PolicyTableKind
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CzCreditPolicyActivationServiceTest {
    @Test
    fun `an unsupported input or incompatible operand cannot be proposed`() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)
        val effectiveDay = LocalDate.of(2026, 10, 26)
        val starter = StarterCreditPolicy().activeBundle(effectiveDay).await().indefinitely()
        val store = mockk<CzCreditPolicyStore>()
        val service = CzCreditPolicyActivationService(store, ObjectMapper(), clock)
        val invalidRules = listOf(
            PolicyRule("numeric-membership", PolicyAttribute.AGE_YEARS, PolicyOperator.IN, values = setOf("18")),
            PolicyRule("text-threshold", PolicyAttribute.RESIDENCY, PolicyOperator.GTE, threshold = BigDecimal.ONE),
            PolicyRule("no-source", PolicyAttribute.CHANNEL, PolicyOperator.EQ, values = setOf("MOBILE")),
        )

        for (rule in invalidRules) {
            val tables = starter.tables.map { table ->
                val rules = if (table.kind == PolicyTableKind.ELIGIBILITY) table.rules + rule else table.rules
                table.copy(version = 3, effectiveFrom = effectiveDay, rules = rules)
            }
            assertThatThrownBy { service.propose(PolicyBundle(tables), "synthetic-credit-policy-maker") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("unsupported input, incompatible operands")
        }

        verify(exactly = 0) { store.propose(any()) }
    }
}
