// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.JournalIdempotencyLookupUseCase
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.domain.model.JournalEntry
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class JournalIdempotencyLookupService(private val journals: JournalRepository) : JournalIdempotencyLookupUseCase {
    override suspend fun findJournalByIdempotencyKey(key: String): JournalEntry? = journals.findByIdempotencyKey(key)
}
