// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finrep.application.port.inbound

import com.openbank.finrep.domain.model.FinrepTemplate
import java.time.LocalDate

data class GetFinrepTemplateQuery(val templateId: String, val asOf: LocalDate)

interface FinrepUseCase {
    suspend fun getTemplate(query: GetFinrepTemplateQuery): FinrepTemplate
}
