// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvalidMoneyExceptionMapperTest {

    private val mapper = InvalidMoneyExceptionMapper()

    private fun refusal(block: () -> Unit): InvalidMoneyException =
        catchThrowableOfType(InvalidMoneyException::class.java) { block() }
            ?: error("expected InvalidMoneyException")

    @Test
    fun `an over-scale amount renders AMOUNT_SCALE_EXCEEDED as a 400 problem`() {
        val e = refusal { Money.parseInbound(BigDecimal("1.005"), "eur", amountField = "instructedAmount") }
        val response = mapper.toResponse(e)
        val body = response.entity as ProblemDetail

        assertThat(response.status).isEqualTo(400)
        assertThat(response.mediaType.toString()).isEqualTo("application/json")
        assertThat(body.status).isEqualTo(400)
        assertThat(body.code).isEqualTo("AMOUNT_SCALE_EXCEEDED")
        assertThat(body.type).isEqualTo("urn:openbank:error:amount-scale-exceeded")
        assertThat(body.title).isEqualTo("The amount has more decimal places than its currency allows")
        assertThat(body.detail).isEqualTo("instructedAmount: Amount must have at most 2 decimal places for EUR")
        assertThat(body.message).isEqualTo(body.detail)
        assertThat(body.retryable).isFalse()
        assertThat(body.violations).containsExactly(
            ProblemViolation(
                "instructedAmount",
                "Amount must have at most 2 decimal places for EUR",
                "AMOUNT_SCALE_EXCEEDED",
            ),
        )
        assertThat(body.detail).doesNotContain("1.005")
    }

    @Test
    fun `an unsupported currency renders CURRENCY_UNSUPPORTED`() {
        val body = mapper.toResponse(refusal { Money.parseInbound(BigDecimal.ONE, "XAU") }).entity as ProblemDetail
        assertThat(body.status).isEqualTo(400)
        assertThat(body.code).isEqualTo("CURRENCY_UNSUPPORTED")
        assertThat(body.type).isEqualTo("urn:openbank:error:currency-unsupported")
        assertThat(body.violations).containsExactly(
            ProblemViolation("currency", "Currency must be an ISO 4217 code with a minor unit", "CURRENCY_UNSUPPORTED"),
        )
    }

    @Test
    fun `an out-of-range amount renders VALIDATION_ERROR`() {
        val body = mapper.toResponse(refusal { Money(BigDecimal("1E+19"), CurrencyCode.EUR) }).entity as ProblemDetail
        assertThat(body.status).isEqualTo(400)
        assertThat(body.code).isEqualTo("VALIDATION_ERROR")
        assertThat(body.detail).isEqualTo("Amount is out of the supported range")
        assertThat(body.violations).isNull()
    }

    @Test
    fun `a non-positive amount renders AMOUNT_NOT_POSITIVE as a 400 problem`() {
        val e = refusal {
            Money.parseInbound(BigDecimal("-7.50"), "EUR", amountField = "instructedAmount", requirePositive = true)
        }
        val response = mapper.toResponse(e)
        val body = response.entity as ProblemDetail

        assertThat(response.status).isEqualTo(400)
        assertThat(response.mediaType.toString()).isEqualTo("application/json")
        assertThat(body.status).isEqualTo(400)
        assertThat(body.code).isEqualTo("AMOUNT_NOT_POSITIVE")
        assertThat(body.type).isEqualTo("urn:openbank:error:amount-not-positive")
        assertThat(body.title).isEqualTo("The amount must be greater than zero")
        assertThat(body.detail).isEqualTo("instructedAmount: Amount must be greater than zero")
        assertThat(body.message).isEqualTo(body.detail)
        assertThat(body.retryable).isFalse()
        assertThat(body.violations).containsExactly(
            ProblemViolation("instructedAmount", "Amount must be greater than zero", "AMOUNT_NOT_POSITIVE"),
        )
        assertThat(body.detail).doesNotContain("7.5")
    }

    @Test
    fun `the generic IllegalArgumentException mapper still answers it with 400 as before`() {
        // Where only the generic mapper is registered (or a catch site widens to IAE), nothing changes.
        val e = refusal { Money.of("1.005", "EUR") }
        val response = IllegalArgumentExceptionMapper().toResponse(e)
        val body = response.entity as ApiError
        assertThat(response.status).isEqualTo(400)
        assertThat(body.code).isEqualTo("VALIDATION_ERROR")
        assertThat(body.message).isEqualTo("Amount scale 3 exceeds currency EUR fraction digits 2")
    }
}
