// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.out.CnbPolicyRateFactRepository
import com.openbank.risk.application.port.out.CnbPolicyRateFactRow
import com.openbank.risk.domain.reserves.MinReserveParameters
import com.openbank.risk.domain.reserves.ReserveRateFact
import java.time.LocalDate

/** Resolves the ČNB reserve ratio and remuneration in effect on a day into a parameter set. */
internal object ReserveFacts {
    suspend fun resolve(
        parameters: MinReserveParameters,
        facts: CnbPolicyRateFactRepository,
        asOf: LocalDate,
    ): MinReserveParameters = parameters.withFacts(
        asOf,
        facts.effectiveAt(ReserveRateFact.RATIO, asOf)?.toFact(),
        facts.effectiveAt(ReserveRateFact.REMUNERATION, asOf)?.toFact(),
    )

    private fun CnbPolicyRateFactRow.toFact() =
        ReserveRateFact(instrument, effectiveFrom, rate, sourceUrl, contentSha256, note)
}
