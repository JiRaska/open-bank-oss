// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.PlatformErrorCode
import com.openbank.libs.domain.error.ValidationFailure
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvalidMoneyExceptionTest {

    private fun refusal(block: () -> Unit): InvalidMoneyException =
        catchThrowableOfType(InvalidMoneyException::class.java) { block() }
            ?: error("expected InvalidMoneyException")

    @Test
    fun `an amount that needs rounding is SCALE_EXCEEDED`() {
        val e = refusal { Money(BigDecimal("1.005"), CurrencyCode.EUR) }
        assertThat(e.reason).isEqualTo(InvalidMoneyReason.SCALE_EXCEEDED)
        assertThat(e.errorCode).isEqualTo(PlatformErrorCode.AMOUNT_SCALE_EXCEEDED)
        assertThat(e.message).isEqualTo("Amount scale 3 exceeds currency EUR fraction digits 2")
        assertThat(e.clientMessage).doesNotContain("1.005")
    }

    @Test
    fun `a fractional JPY amount is SCALE_EXCEEDED`() {
        assertThat(refusal { Money.of("1.5", "JPY") }.reason).isEqualTo(InvalidMoneyReason.SCALE_EXCEEDED)
    }

    @Test
    fun `an unknown, malformed or unit-less currency is CURRENCY_UNSUPPORTED`() {
        listOf("ZZZ", "EU", "E1R", "XAU", "XXX").forEach { code ->
            val e = refusal { CurrencyCode.of(code) }
            assertThat(e.reason).`as`(code).isEqualTo(InvalidMoneyReason.CURRENCY_UNSUPPORTED)
            assertThat(e.errorCode.code).isEqualTo("CURRENCY_UNSUPPORTED")
        }
    }

    @Test
    fun `an amount beyond the magnitude bound is AMOUNT_OUT_OF_RANGE`() {
        val tooWide = refusal { Money(BigDecimal("1E+19"), CurrencyCode.EUR) }
        assertThat(tooWide.reason).isEqualTo(InvalidMoneyReason.AMOUNT_OUT_OF_RANGE)
        assertThat(tooWide.errorCode).isEqualTo(PlatformErrorCode.VALIDATION_ERROR)
        val tooFine = refusal { Money(BigDecimal("1E-19"), CurrencyCode.EUR) }
        assertThat(tooFine.reason).isEqualTo(InvalidMoneyReason.AMOUNT_OUT_OF_RANGE)
    }

    @Test
    fun `it stays an IllegalArgumentException so existing catches behave as before`() {
        val caught = try {
            Money.of("1.005", "EUR")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertThat(caught).isInstanceOf(InvalidMoneyException::class.java)
        assertThat(IllegalArgumentException::class.java.isAssignableFrom(InvalidMoneyException::class.java)).isTrue()
    }

    @Test
    fun `the new codes are VALIDATION`() {
        assertThat(PlatformErrorCode.AMOUNT_SCALE_EXCEEDED.category).isEqualTo(ErrorCategory.VALIDATION)
        assertThat(PlatformErrorCode.CURRENCY_UNSUPPORTED.category).isEqualTo(ErrorCategory.VALIDATION)
    }

    @Test
    fun `parseInbound normalises the currency like the boundary did`() {
        assertThat(Money.parseInbound(BigDecimal("10.5"), " eur ")).isEqualTo(Money.of("10.50", "EUR"))
    }

    @Test
    fun `parseInbound attributes each refusal to its field`() {
        val scale = refusal { Money.parseInbound(BigDecimal("1.005"), "EUR", amountField = "instructedAmount") }
        assertThat(scale.reason).isEqualTo(InvalidMoneyReason.SCALE_EXCEEDED)
        assertThat(scale.field).isEqualTo("instructedAmount")
        assertThat(scale.message).isEqualTo("Amount scale 3 exceeds currency EUR fraction digits 2")

        val currency = refusal { Money.parseInbound(BigDecimal.ONE, "XAU", currencyField = "ccy") }
        assertThat(currency.reason).isEqualTo(InvalidMoneyReason.CURRENCY_UNSUPPORTED)
        assertThat(currency.field).isEqualTo("ccy")

        val range = refusal { Money.parseInbound(BigDecimal("1E+19"), "EUR") }
        assertThat(range.reason).isEqualTo(InvalidMoneyReason.AMOUNT_OUT_OF_RANGE)
        assertThat(range.field).isEqualTo("amount")
    }

    @Test
    fun `parseInbound answers a missing value as a ValidationFailure naming the field`() {
        assertThatThrownBy { Money.parseInbound(null, "EUR") }
            .isInstanceOf(ValidationFailure::class.java)
            .hasMessageContaining("amount")
        assertThatThrownBy { Money.parseInbound(BigDecimal.ONE, null, currencyField = "ccy") }
            .isInstanceOf(ValidationFailure::class.java)
            .hasMessageContaining("ccy")
    }

    @Test
    fun `requirePositive refuses zero as NOT_POSITIVE on the amount field`() {
        val e = refusal {
            Money.parseInbound(BigDecimal("0.00"), "EUR", amountField = "instructedAmount", requirePositive = true)
        }
        assertThat(e.reason).isEqualTo(InvalidMoneyReason.NOT_POSITIVE)
        assertThat(e.errorCode).isEqualTo(PlatformErrorCode.AMOUNT_NOT_POSITIVE)
        assertThat(e.field).isEqualTo("instructedAmount")
        assertThat(e.clientMessage).isEqualTo("Amount must be greater than zero")
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requirePositive refuses a negative amount and never echoes it`() {
        val e = refusal { Money.parseInbound(BigDecimal("-12.34"), "EUR", requirePositive = true) }
        assertThat(e.reason).isEqualTo(InvalidMoneyReason.NOT_POSITIVE)
        assertThat(e.field).isEqualTo("amount")
        assertThat(e.clientMessage).doesNotContain("12.34")
        assertThat(e.message).doesNotContain("12.34")
    }

    @Test
    fun `requirePositive accepts a positive amount, including the smallest unit`() {
        assertThat(Money.parseInbound(BigDecimal("0.01"), "EUR", requirePositive = true))
            .isEqualTo(Money.of("0.01", "EUR"))
        assertThat(Money.parseInbound(BigDecimal("1"), "JPY", requirePositive = true))
            .isEqualTo(Money.of("1", "JPY"))
    }

    @Test
    fun `requirePositive treats a zero-decimal currency's zero as zero`() {
        val e = refusal { Money.parseInbound(BigDecimal.ZERO, "JPY", requirePositive = true) }
        assertThat(e.reason).isEqualTo(InvalidMoneyReason.NOT_POSITIVE)
    }

    @Test
    fun `requirePositive keeps the earlier refusals ahead of it`() {
        // A fractional JPY or an unknown currency is still answered as before, not as NOT_POSITIVE.
        assertThat(refusal { Money.parseInbound(BigDecimal("-0.5"), "JPY", requirePositive = true) }.reason)
            .isEqualTo(InvalidMoneyReason.SCALE_EXCEEDED)
        assertThat(refusal { Money.parseInbound(BigDecimal.ZERO, "XAU", requirePositive = true) }.reason)
            .isEqualTo(InvalidMoneyReason.CURRENCY_UNSUPPORTED)
    }

    @Test
    fun `by default parseInbound still accepts zero and negative amounts`() {
        assertThat(Money.parseInbound(BigDecimal.ZERO, "EUR")).isEqualTo(Money.of("0.00", "EUR"))
        assertThat(Money.parseInbound(BigDecimal("-5"), "EUR")).isEqualTo(Money.of("-5.00", "EUR"))
        assertThat(Money.parseInbound(BigDecimal.ZERO, "JPY")).isEqualTo(Money.of("0", "JPY"))
    }

    @Test
    fun `AMOUNT_NOT_POSITIVE is VALIDATION and not retryable`() {
        assertThat(PlatformErrorCode.AMOUNT_NOT_POSITIVE.category).isEqualTo(ErrorCategory.VALIDATION)
        assertThat(PlatformErrorCode.AMOUNT_NOT_POSITIVE.retryable).isFalse()
    }
}
