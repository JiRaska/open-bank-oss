// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.application.usecase

import com.openbank.psd2.domain.model.DomesticCzPayment
import java.util.Locale

/** The ČOBS domestic product is denominated in CZK on both PSD2 PIS surfaces. */
object DomesticCzCurrency {
    fun normalize(payment: DomesticCzPayment): DomesticCzPayment {
        val currency = payment.instructedAmount.currency.trim().uppercase(Locale.ROOT)
        if (currency != "CZK") throw Psd2RequestFormatException("domestic-cz requires CZK")
        return payment.copy(instructedAmount = payment.instructedAmount.copy(currency = currency))
    }
}
