// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import com.openbank.tax.domain.corporate.CorporateFact
import com.openbank.tax.domain.corporate.CorporateRegisterEntry
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Append-only store of the corporate register (#12425). */
interface CorporateRegisterRepository {
    suspend fun insert(entry: CorporateRegisterEntry): CorporateRegisterEntry

    suspend fun find(id: UUID): CorporateRegisterEntry?

    /** Every entry of [entityId], any status, oldest first. */
    suspend fun entries(entityId: String): List<CorporateRegisterEntry>

    /** Highest version for (entity, fact, effective date), or 0 when none. */
    suspend fun latestVersion(entry: CorporateRegisterEntry): Int

    /** Moves a PROPOSED entry to its decision; refuses (409) if it is no longer PROPOSED. */
    suspend fun decide(entry: CorporateRegisterEntry): CorporateRegisterEntry
}

/** Read side the return assembly uses: the effective approved figures, never a proposal. */
interface CorporateFactsPort {
    /**
     * The value of each of [facts] effective for the period, keyed by fact; a fact with no
     * approved entry is ABSENT from the map — never zero.
     */
    suspend fun effective(
        entityId: String,
        facts: Collection<CorporateFact>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Map<CorporateFact, BigDecimal>
}
