// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.port.out.AccountRevisionPinPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.domain.BillableFee

/** In-memory catalog: one published revision, [fees] as its price components, and a class. */
class FakeRevisions(
    private val fees: List<BillableFee>,
    private val accountingClass: String? = "CUSTOMER_DEPOSIT_DEMAND",
    private val source: AccountingClassSource = AccountingClassSource.REVISION,
    var published: String? = PUBLISHED_ID,
    private val failure: Exception? = null,
) : ProductRevisionPort {
    val termsReads = mutableListOf<String>()

    override suspend fun publishedRevisionIdFor(productId: String): String? {
        failure?.let { throw it }
        return published
    }

    override suspend fun terms(revisionId: String, currency: String): RevisionTerms {
        failure?.let { throw it }
        termsReads += revisionId
        return RevisionTerms(revisionId, accountingClass, source, fees.filter { it.currency == currency })
    }

    companion object {
        const val PUBLISHED_ID = "0191c0de-0000-7000-8000-0000000000a1"
        const val OTHER_ID = "0191c0de-0000-7000-8000-0000000000a2"
    }
}

class InMemoryPins : AccountRevisionPinPort {
    val pins = mutableMapOf<String, String>()

    override suspend fun find(accountId: String): String? = pins[accountId]

    override suspend fun pinIfAbsent(accountId: String, revisionId: String): String =
        pins.getOrPut(accountId) { revisionId }
}
