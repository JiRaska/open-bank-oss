// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * ADR-0179. The ledger has no entry-type or reason-code column, so this description string is the
 * ONLY thing that distinguishes a merge correction from an ordinary customer transfer — in the
 * trial balance, on a statement, and to an auditor. Pin its shape.
 */
class MergeSweepDescriptionTest {

    private val sourceParty = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val survivingParty = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun request(mergeReference: String = "MERGE-2026-0001") = MergeSweepRequest(
        idempotencyKey = "sweep-1",
        sourceAccountId = UUID.randomUUID(),
        targetAccountId = UUID.randomUUID(),
        sourcePartyId = sourceParty,
        survivingPartyId = survivingParty,
        amount = BigDecimal("1234.56"),
        currencyCode = "CZK",
        valueDate = "2026-07-19",
        mergeReference = mergeReference,
    )

    @Test
    fun `carries the merge reference and both party ids, in survivor-last order`() {
        val description = MergeSweepDescription.of(request())

        assertThat(description)
            .isEqualTo("MERGE-SWEEP MERGE-2026-0001: party $sourceParty -> $survivingParty")
    }

    @Test
    fun `is prefixed so a merge correction is greppable in the ledger`() {
        assertThat(MergeSweepDescription.of(request())).startsWith(MergeSweepDescription.PREFIX)
    }

    @Test
    fun `names no PII — ids only, never a name or email`() {
        // The description lands on customer statements. A UUID discloses nothing a statement
        // holder does not already own; the OTHER party's legal name or email would. With a
        // digits-only reference, the whole string's alphabetic content is the fixed template,
        // so any future interpolation of a name would break this.
        val description = MergeSweepDescription.of(request(mergeReference = "20260719-0001"))

        assertThat(description).doesNotContain("@")
        assertThat(description.filter { it.isLetter() }.lowercase())
            .`as`("alphabetic content must be the fixed template only: the prefix plus 'party'")
            .isEqualTo("mergesweepparty")
    }
}
