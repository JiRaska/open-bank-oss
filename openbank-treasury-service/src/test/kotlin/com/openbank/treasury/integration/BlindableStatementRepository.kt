// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.application.port.out.StoredStatement
import com.openbank.treasury.infrastructure.nostro.NostroStatementEntryPanacheRepository
import com.openbank.treasury.infrastructure.nostro.NostroStatementRepositoryImpl
import jakarta.annotation.Priority
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real repository, able to hide stored rows from the NEXT upload's two pre-check reads — the
 * view a concurrent upload has before the winner commits. Only the INSERT's unique constraint is
 * then left to stop the duplicate, which is the path under test (#11052 review).
 */
@Alternative
@Priority(1)
@Singleton // no client proxy: the superclass has only an injecting constructor
class BlindableStatementRepository(entryRepo: NostroStatementEntryPanacheRepository) :
    NostroStatementRepositoryImpl(entryRepo) {

    private val blindReads = AtomicInteger(0)

    /** The pre-check reads the idempotency key, then the account + statement id: two reads. */
    fun blindNextPreCheck() = blindReads.set(PRE_CHECK_READS)

    fun reset() = blindReads.set(0)

    override suspend fun findByIdempotencyKey(key: String): StoredStatement? =
        if (blindReads.getAndUpdate { maxOf(0, it - 1) } > 0) null else super.findByIdempotencyKey(key)

    override suspend fun findByAccountAndStatementId(iban: String, statementId: String): StoredStatement? =
        if (blindReads.getAndUpdate { maxOf(0, it - 1) } >
            0
        ) {
            null
        } else {
            super.findByAccountAndStatementId(iban, statementId)
        }

    private companion object {
        const val PRE_CHECK_READS = 2
    }
}
