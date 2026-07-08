// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.application.usecase

import com.openbank.finrep.application.port.inbound.CorepUseCase
import com.openbank.finrep.application.port.inbound.GetCorepTemplateQuery
import com.openbank.finrep.application.port.out.LedgerPort
import com.openbank.finrep.domain.mapper.C0100Mapper
import com.openbank.finrep.domain.model.CorepTemplate
import jakarta.enterprise.context.ApplicationScoped

/**
 * COREP report generation (ADR-0097 Phase 2, first increment). Only C 01.00 (Own Funds) is
 * implemented; every other COREP template (C 02.00 own funds requirements, C 05.01 transitional
 * provisions, etc.) is out of scope for this increment.
 */
@ApplicationScoped
class CorepService(private val ledgerPort: LedgerPort) : CorepUseCase {

    override suspend fun getTemplate(query: GetCorepTemplateQuery): CorepTemplate {
        val lines = ledgerPort.getTrialBalance(query.asOf)
        return when (query.templateId) {
            "C_01.00" -> C0100Mapper.map(lines, query.asOf)
            else -> throw IllegalArgumentException("Unknown or unimplemented COREP template: ${query.templateId}")
        }
    }
}
