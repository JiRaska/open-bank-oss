// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.domain.reference.BookPayload
import com.openbank.treasury.domain.reference.Ifrs9Category
import com.openbank.treasury.domain.reference.ReferenceKind
import java.time.Clock

/**
 * ADR-0344 D3/D7: a deal's IFRS 9 measurement category is its book's (`book` reference record,
 * ACTIVE today). A deal without a book, or whose book has no effective record, is measured at
 * amortised cost — the category that never revalues — so a missing record cannot make a deal post
 * unrealised P&L.
 */
class BookCategories(private val references: ReferenceRepository, private val clock: Clock) {
    suspend fun of(bookId: String?): Ifrs9Category {
        if (bookId == null) return Ifrs9Category.AMORTISED_COST
        val today = AccountingClock.bank(clock).today()
        val record = references.versions(ReferenceKind.BOOK, bookId).lastOrNull { it.isEffectiveOn(today) }
        return (record?.payload as? BookPayload)?.ifrs9Category ?: Ifrs9Category.AMORTISED_COST
    }
}
