// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.port.inbound

import com.openbank.finrep.domain.model.CorepTemplate
import java.time.LocalDate

data class GetCorepTemplateQuery(
    val templateId: String,
    val asOf: LocalDate,
    val evidence: TrialBalanceEvidence = TrialBalanceEvidence.FROZEN,
)

interface CorepUseCase {
    suspend fun getTemplate(query: GetCorepTemplateQuery): CorepTemplate
}
