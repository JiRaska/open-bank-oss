// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.inbound

import com.openbank.finrep.domain.model.FinrepTemplate
import java.time.LocalDate

enum class TrialBalanceEvidence { FROZEN, LIVE_PREVIEW }

data class GetFinrepTemplateQuery(
    val templateId: String,
    val asOf: LocalDate,
    val evidence: TrialBalanceEvidence = TrialBalanceEvidence.FROZEN,
)

interface FinrepUseCase {
    suspend fun getTemplate(query: GetFinrepTemplateQuery): FinrepTemplate
}
