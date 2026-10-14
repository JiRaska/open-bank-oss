// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit

import com.openbank.pension.application.exit.IncentiveClawbackPort
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.application.usecase.StateContributionReturnService
import com.openbank.pension.domain.exit.IncentiveBalance
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The exit slice's (S5) view of the incentive ledger the contribution slice (S3) keeps — in-process,
 * same service, no stub (ADR-0334 S8). A quote reads the clawback balance from the real ledger, and
 * an executed exit writes its RETURNED entries there, idempotently on the activity's key. Before
 * integration S5 read an in-memory stub, so a received state contribution was never returned.
 */
@ApplicationScoped
class IncentiveLedgerClawbackAdapter(
    private val incentives: IncentiveService,
    private val stateReturns: StateContributionReturnService,
    private val clock: Clock,
) : IncentiveClawbackPort {

    override suspend fun balance(contractId: UUID, asOf: LocalDate): IncentiveBalance {
        val b = incentives.clawbackBalance(contractId, asOf)
        return IncentiveBalance(
            stateIncentivesToReturn = b.stateIncentivesToReturn,
            stateIncentivesReceived = b.stateIncentivesReceived,
            deductedContributionsByYear = b.deductedContributionsByYear,
            employerExemptByYear = b.employerExemptByYear,
            ownContributionsNotDeducted = b.ownContributionsNotDeducted,
        )
    }

    /**
     * Writes the RETURNED ledger entries, then registers the return the agency is owed (ZDPS §18(3),
     * #12382). Both steps are idempotent on [idempotencyKey], so a replayed activity is harmless.
     */
    override suspend fun settleClawback(contractId: UUID, amount: BigDecimal, idempotencyKey: String) {
        incentives.settleClawback(contractId, amount, idempotencyKey)
        stateReturns.registerTermination(
            contractId,
            amount,
            LocalDate.now(clock.withZone(ZoneOffset.UTC)),
            idempotencyKey,
        )
    }
}
