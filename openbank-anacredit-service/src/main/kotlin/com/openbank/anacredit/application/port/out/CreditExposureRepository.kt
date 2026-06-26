// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.application.port.out

import com.openbank.anacredit.domain.model.CreditExposure

/**
 * System of record for the credit exposures the AnaCredit feed derives its return from.
 *
 * v1 is fed by REST upsert and backed in-memory (the openbank-product-catalog pattern); a future
 * revision consumes balance.overdraft.* events and persists. The port keeps that swap mechanical.
 */
interface CreditExposureRepository {
    /** Insert or replace the exposure keyed by [CreditExposure.instrumentId]; returns the stored value. */
    fun upsert(exposure: CreditExposure): CreditExposure

    fun findById(instrumentId: String): CreditExposure?

    fun listAll(): List<CreditExposure>
}
