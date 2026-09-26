// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.domain.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SettlementScopeTest {

    @Test
    fun `an internal payee leg stays in the bank whatever the rail`() {
        assertThat(SettlementScope.staysInTheBank(PaymentRail.DOMESTIC, hasInternalPayee = true)).isTrue()
        assertThat(SettlementScope.staysInTheBank(null, hasInternalPayee = true)).isTrue()
    }

    @Test
    fun `INTERNAL, FEE and INTEREST rails stay in the bank even with no resolved payee`() {
        for (rail in listOf(PaymentRail.INTERNAL, PaymentRail.FEE, PaymentRail.INTEREST)) {
            assertThat(SettlementScope.staysInTheBank(rail, hasInternalPayee = false))
                .describedAs(rail.name)
                .isTrue()
        }
    }

    @Test
    fun `a real external rail with no resolved payee leaves the bank`() {
        for (rail in listOf(PaymentRail.SEPA_CT, PaymentRail.SEPA_INST, PaymentRail.SWIFT, PaymentRail.DOMESTIC)) {
            assertThat(SettlementScope.staysInTheBank(rail, hasInternalPayee = false))
                .describedAs(rail.name)
                .isFalse()
        }
    }

    @Test
    fun `no rail and no resolved payee leaves the bank`() {
        // The honest "we don't know" case must not be treated as internal by default.
        assertThat(SettlementScope.staysInTheBank(null, hasInternalPayee = false)).isFalse()
    }
}
