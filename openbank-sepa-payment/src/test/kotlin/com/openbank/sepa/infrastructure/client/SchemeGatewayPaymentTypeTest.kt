// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.libs.iso20022.PaymentTypeInformation
import com.openbank.sepa.domain.model.SepaPaymentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #13663: the EPC payment type each [SepaPaymentType] puts on the pacs.008. The wire-level proof that
 * the adapter actually sends it is `SchemeGatewaySimulatorIT`; this pins the mapping without Docker.
 */
class SchemeGatewayPaymentTypeTest {
    @Test
    fun `SCT maps to service level SEPA with no local instrument`() {
        val pti = SchemeGatewayAdapter.paymentTypeInformation(SepaPaymentType.SCT)

        assertThat(pti).isEqualTo(PaymentTypeInformation("SEPA", null))
    }

    @Test
    fun `SCT_INST maps to service level SEPA and local instrument INST`() {
        val pti = SchemeGatewayAdapter.paymentTypeInformation(SepaPaymentType.SCT_INST)

        assertThat(pti).isEqualTo(PaymentTypeInformation("SEPA", "INST"))
    }

    @Test
    fun `every payment type declares a service level`() {
        SepaPaymentType.entries.forEach {
            assertThat(SchemeGatewayAdapter.paymentTypeInformation(it).serviceLevelCode).isEqualTo("SEPA")
        }
    }
}
