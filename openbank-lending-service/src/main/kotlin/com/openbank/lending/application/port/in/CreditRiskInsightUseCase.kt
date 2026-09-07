// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.`in`

import com.openbank.lending.domain.model.CreditDecisionView
import com.openbank.lending.domain.model.CreditPolicyView
import com.openbank.lending.domain.model.DecisionOutcomeSummary
import com.openbank.lending.domain.model.LoanRiskView
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/**
 * Read-only credit-risk insight (ADR-0230 D1): what the ADR-0213 engine decided, against which
 * policy, and how the book is staged under IFRS 9. No mutation lives here — every credit
 * disposition stays in the four-eyes paths of [ApplyForLoanUseCase] and the approval inbox.
 */
interface CreditRiskInsightUseCase {
    /** Engine-evaluated applications, newest evaluation first. [limit] is clamped server-side. */
    fun decisions(limit: Int): Uni<List<CreditDecisionView>>

    /** Book-wide outcome × price-band totals — the figure a capped list cannot give. */
    fun summariseDecisions(): Uni<List<DecisionOutcomeSummary>>

    /** Every loan with its latest provisioning record (null where never assessed). [limit] clamped. */
    fun portfolio(limit: Int): Uni<List<LoanRiskView>>

    /** The bundle the engine evaluates as of [asOf], flagged when it is the code-seeded starter. */
    fun activePolicy(asOf: LocalDate): Uni<CreditPolicyView>
}
