// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.infrastructure.persistence

import com.openbank.anacredit.application.port.out.CreditExposureRepository
import com.openbank.anacredit.domain.model.CreditExposure
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * v1 in-memory exposure store (the openbank-product-catalog pattern). Keyed by instrumentId so a
 * re-submitted exposure for the same instrument replaces the prior reference-date snapshot.
 */
@ApplicationScoped
class InMemoryCreditExposureRepository : CreditExposureRepository {

    private val store = ConcurrentHashMap<String, CreditExposure>()

    override fun upsert(exposure: CreditExposure): CreditExposure {
        store[exposure.instrumentId] = exposure
        return exposure
    }

    override fun findById(instrumentId: String): CreditExposure? = store[instrumentId]

    override fun listAll(): List<CreditExposure> = store.values.sortedBy { it.instrumentId }
}
