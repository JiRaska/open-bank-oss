// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund

import com.openbank.pensionfund.application.port.FundTermsPort
import com.openbank.pensionfund.application.port.PinnedFundTerms
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.math.BigDecimal
import java.util.UUID

/**
 * Test double for the catalog read: a fixed table of "published revisions" so the suites run without a
 * catalog. An id outside the table is not a published revision (the real adapter's fail-closed answer).
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FixedFundTerms : FundTermsPort {
    override suspend fun resolve(revisionId: UUID): PinnedFundTerms {
        val fee = FEES[revisionId]
            ?: throw IllegalArgumentException("productRevisionId $revisionId is not a published revision")
        return PinnedFundTerms(revisionId, fee)
    }

    companion object {
        val ZERO_FEE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val FEE_0_80_PERCENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val FEE_1_00_PERCENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val FEE_0_40_PERCENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")

        private val FEES = mapOf(
            ZERO_FEE to BigDecimal.ZERO,
            FEE_0_80_PERCENT to BigDecimal("0.0080"),
            FEE_1_00_PERCENT to BigDecimal("0.0100"),
            FEE_0_40_PERCENT to BigDecimal("0.0040"),
        )
    }
}
