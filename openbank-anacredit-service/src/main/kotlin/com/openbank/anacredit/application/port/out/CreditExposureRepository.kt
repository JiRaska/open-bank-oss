// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.application.port.out

import com.openbank.anacredit.domain.model.CreditExposure

/**
 * System of record for the credit exposures the AnaCredit feed derives its return from.
 *
 * Exposures are fed by REST upsert (v1); a future revision may also consume balance.overdraft.*
 * events. Backed by Postgres via reactive Panache (ADR-0037 v2, the openbank-product-catalog
 * pattern) — durable across restarts. Methods are `suspend`: the adapter bridges its Mutiny `Uni`
 * results onto coroutines, the fleet/libs standard.
 */
interface CreditExposureRepository {
    /** Insert or replace the exposure keyed by [CreditExposure.instrumentId]; returns the stored value. */
    suspend fun upsert(exposure: CreditExposure): CreditExposure

    suspend fun findById(instrumentId: String): CreditExposure?

    suspend fun listAll(): List<CreditExposure>
}
