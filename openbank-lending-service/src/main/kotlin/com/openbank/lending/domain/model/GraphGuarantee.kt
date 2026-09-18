// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Proposed source fact. No caller-controlled bank, maker, checker or approval state exists here. */
data class GraphGuaranteeProposal(
    val contractId: UUID,
    val revision: Long,
    val supersedesGuaranteeId: UUID?,
    val loanId: UUID,
    val guarantorPartyId: UUID,
    val capAmount: BigDecimal,
    val currency: String,
    val coverageFraction: BigDecimal,
    val seniority: Int,
    val validFrom: Instant,
    val validTo: Instant?,
    val sourceDocumentId: UUID,
    val sourceSha256: String,
) {
    fun validate() {
        require(revision > 0 && ((revision == 1L) == (supersedesGuaranteeId == null))) {
            "guarantee revision and supersession must agree"
        }
        require(capAmount > BigDecimal.ZERO && capAmount.scale() <= 2) { "invalid guarantee cap" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "invalid guarantee currency" }
        require(
            coverageFraction > BigDecimal.ZERO &&
                coverageFraction <= BigDecimal.ONE &&
                coverageFraction.scale() <= MAX_FRACTION_SCALE,
        ) {
            "invalid guarantee coverage fraction"
        }
        require(seniority > 0) { "invalid guarantee seniority" }
        require(validTo == null || validTo > validFrom) { "invalid guarantee validity interval" }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) { "invalid sealed document hash" }
    }

    private companion object {
        const val MAX_FRACTION_SCALE = 6
    }
}

enum class GraphGuaranteeStatus { PENDING, APPROVED, REJECTED }

data class GraphGuaranteeFact(
    val guaranteeId: UUID,
    val proposal: GraphGuaranteeProposal,
    val status: GraphGuaranteeStatus,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
)
