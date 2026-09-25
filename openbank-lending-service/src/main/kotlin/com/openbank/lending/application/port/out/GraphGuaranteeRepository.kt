// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import java.time.Instant
import java.util.UUID

/** Decision and outbox persistence must share one transaction in the implementation. */
interface GraphGuaranteeRepository {
    suspend fun propose(proposal: GraphGuaranteeProposal, actor: String, at: Instant): GraphGuaranteeFact

    suspend fun find(guaranteeId: UUID): GraphGuaranteeFact?

    /**
     * Approved guarantee facts effective at [effectiveAt] and known by [knownAt].
     * Returns at most [limit] + 1 rows so callers can detect truncation without
     * loading an unbounded graph. Results retain every qualifying revision.
     */
    suspend fun findApprovedForLoan(
        loanId: UUID,
        effectiveAt: Instant,
        knownAt: Instant,
        limit: Int,
    ): List<GraphGuaranteeFact>

    /** Only approved, effective, known facts on this loan involving a root-loan guarantor. */
    suspend fun findApprovedForLoanAndGuarantors(
        loanId: UUID,
        guarantorPartyIds: Set<UUID>,
        effectiveAt: Instant,
        knownAt: Instant,
        limit: Int,
    ): List<GraphGuaranteeFact>

    /** Internal preselection from Context's already-assigned IDs; never returned before live rechecks. */
    suspend fun findSharedCandidateLoanIds(
        candidateLoanIds: List<UUID>,
        guarantorPartyIds: Set<UUID>,
        effectiveAt: Instant,
        knownAt: Instant,
        limit: Int,
    ): List<UUID>

    suspend fun decide(
        guaranteeId: UUID,
        decision: GraphGuaranteeStatus,
        actor: String,
        at: Instant,
    ): GraphGuaranteeFact
}
