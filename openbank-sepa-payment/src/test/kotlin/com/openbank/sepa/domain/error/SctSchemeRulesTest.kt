// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.error

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SctSchemeRulesTest {

    @Test
    fun `EUR amount passes the scheme check unchanged`() {
        val eur = Money.of(BigDecimal("100.00"), "EUR")

        val checked = SctSchemeRules.requireSchemeCurrency(eur)

        assertThat(checked).isEqualTo(eur)
    }

    @Test
    fun `non-EUR amount is refused as a currency violation`() {
        // #11931 / ADR-0326: a non-EUR SCT submission is a 400 naming the `currency` field,
        // and the rejected value is deliberately not echoed in the client message.
        val czk = Money.of(BigDecimal("100.00"), "CZK")

        assertThatThrownBy { SctSchemeRules.requireSchemeCurrency(czk) }
            .isInstanceOf(ValidationFailure::class.java)
            .satisfies({ thrown ->
                val failure = thrown as ValidationFailure
                // ADR-0326: the client-safe text never echoes the rejected value; the
                // exception's technical message is internal-only and may name it.
                assertThat(failure.clientMessage).contains("currency")
                assertThat(failure.clientMessage).doesNotContain("CZK")
                assertThat(failure.errorCode).isEqualTo(SctErrorCode.CURRENCY_NOT_ALLOWED)
                assertThat(failure.violations).hasSize(1)
                assertThat(failure.violations[0].field).isEqualTo("currency")
                assertThat(failure.violations[0].code).isEqualTo("CURRENCY_NOT_ALLOWED")
            })
    }

    @Test
    fun `currency-not-allowed error code contract is stable`() {
        // ADR-0326: append-only, never renamed — sepa-instant carries the same wire contract,
        // so a renamed or re-categorised code is a cross-rail breaking change.
        val code = SctErrorCode.CURRENCY_NOT_ALLOWED

        assertThat(code.code).isEqualTo("CURRENCY_NOT_ALLOWED")
        assertThat(code.name).isEqualTo("CURRENCY_NOT_ALLOWED")
        assertThat(code.category).isEqualTo(ErrorCategory.VALIDATION)
        assertThat(code.title).contains("currency")
        // VALIDATION is not retryable-by-default: an identical request must fail identically.
        assertThat(code.retryable).isFalse()
    }
}
