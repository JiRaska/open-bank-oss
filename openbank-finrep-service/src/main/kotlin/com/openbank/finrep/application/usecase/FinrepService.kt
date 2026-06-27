// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.FinrepUseCase
import com.openbank.finrep.application.port.inbound.GetFinrepTemplateQuery
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.mapper.F0200Mapper
import com.openbank.finrep.domain.model.FinrepTemplate
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class FinrepService(private val ledgerPort: LedgerPort) : FinrepUseCase {

    override suspend fun getTemplate(query: GetFinrepTemplateQuery): FinrepTemplate {
        val lines = ledgerPort.getTrialBalance(query.asOf)
        return when (query.templateId) {
            "F01.01" -> F0101Mapper.map(lines, query.asOf)
            "F02.00" -> F0200Mapper.map(lines, query.asOf)
            else -> throw IllegalArgumentException("Unknown FINREP template: ${query.templateId}")
        }
    }
}
