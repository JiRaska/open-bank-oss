// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.domain.BillableFee
import java.time.Instant

/** In-memory catalog: one published revision, [fees] as its price components, and a class. */
class FakeRevisions(
    private val fees: List<BillableFee>,
    private val accountingClass: String? = "CUSTOMER_DEPOSIT_DEMAND",
    private val source: AccountingClassSource = AccountingClassSource.REVISION,
    var published: String? = PUBLISHED_ID,
    private val failure: Exception? = null,
    /** Revision ids by the instant they became effective (oldest first); overrides [published] when set. */
    private val history: List<Pair<Instant, String>>? = null,
    private val allFees: List<BillableFee> = fees,
    private val feeBearing: Boolean = allFees.isNotEmpty(),
    private val dropped: Int = 0,
    private val noIdentity: Int = 0,
    /** Fails ONLY the terms read (the lookup still works), to tell the failure kinds apart. */
    private val termsFailure: Exception? = null,
) : ProductRevisionPort {
    val termsReads = mutableListOf<String>()
    val lookups = mutableListOf<Instant?>()

    override suspend fun publishedRevisionIdFor(productId: String, effectiveAt: Instant?): String? {
        failure?.let { throw it }
        lookups += effectiveAt
        if (history == null) return published
        val asOf = effectiveAt ?: Instant.MAX
        return history.lastOrNull { !it.first.isAfter(asOf) }?.second
    }

    override suspend fun terms(revisionId: String, currency: String): RevisionTerms {
        failure?.let { throw it }
        termsFailure?.let { throw it }
        termsReads += revisionId
        return RevisionTerms(
            revisionId,
            accountingClass,
            source,
            fees.filter { it.currency == currency },
            feeBearing = feeBearing,
            droppedFeeComponents = dropped,
            noFeeIdentityComponents = noIdentity,
        )
    }

    companion object {
        const val PUBLISHED_ID = "0191c0de-0000-7000-8000-0000000000a1"
        const val OTHER_ID = "0191c0de-0000-7000-8000-0000000000a2"
    }
}

/** When the fake accounts were opened (an arbitrary instant, long before any test republication). */
val OPENED: Instant = Instant.parse("2026-01-15T10:00:00Z")

class InMemoryPins(private val findFailure: Exception? = null, private val pinFailure: Exception? = null) :
    AccountRevisionPinPort {
    val pins = mutableMapOf<String, String>()

    override suspend fun find(accountId: String): String? {
        findFailure?.let { throw it }
        return pins[accountId]
    }

    override suspend fun pinIfAbsent(accountId: String, revisionId: String): String {
        pinFailure?.let { throw it }
        return pins.getOrPut(accountId) { revisionId }
    }
}
