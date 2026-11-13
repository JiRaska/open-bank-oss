// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DepositLoanTerms
import com.openbank.treasury.domain.model.EarlyWithdrawalTerms
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Typed deposit / loan terms on the deal header: bounded, and round-tripped by the stored header JSON. */
class DepositLoanTermsTest {
    private fun header(t: DepositLoanTerms) = DealHeader(depositLoanTerms = t)

    @Test
    fun `valid terms pass and survive the stored JSON`() {
        val t = DepositLoanTerms("ACC-P", "ACC-I", EarlyWithdrawalTerms(true, 31, 50, "with notice"))
        header(t).validate()
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        assertThat(mapper.readValue<DealHeader>(mapper.writeValueAsString(header(t))).depositLoanTerms).isEqualTo(t)
    }

    @Test
    fun `bounds and consistency are enforced`() {
        listOf(
            DepositLoanTerms(principalAccountId = " "),
            DepositLoanTerms(interestAccountId = "x".repeat(65)),
            DepositLoanTerms(principalAccountId = "A\u0000B"),
            DepositLoanTerms(earlyWithdrawal = EarlyWithdrawalTerms(true, noticeDays = -1)),
            DepositLoanTerms(earlyWithdrawal = EarlyWithdrawalTerms(true, penaltyBp = 10_001)),
            DepositLoanTerms(earlyWithdrawal = EarlyWithdrawalTerms(false, noticeDays = 5)),
        ).forEach { t ->
            assertThatThrownBy {
                header(t).validate()
            }.describedAs(t.toString()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
