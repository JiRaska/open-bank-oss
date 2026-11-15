// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.swift.application.port.out.AmlCasePort
import com.openbank.swift.application.port.out.OpenAmlCaseCommand
import com.openbank.swift.application.port.out.SanctionsScreeningPort
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Every `@QuarkusTest` boots without sanctions-service. The MT103 sanctions gate fails closed
 * (ADR-0032), so without this stub every flow that is meant to reach the scheme would stay VALIDATED.
 * These ITs test the outbox and settlement, not screening, which `SwiftServiceTest` covers.
 * The alternatives are enabled globally by `@Priority`.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class ClearSanctionsScreeningPort : SanctionsScreeningPort {
    override suspend fun screen(name: String, role: ScreeningRole, idempotencyKey: String) =
        ScreeningResult(name, role, ScreeningMatchStatus.CLEAR, 0.0, null)
}

@Alternative
@Priority(1)
@ApplicationScoped
class NoOpAmlCasePort : AmlCasePort {
    override suspend fun openCase(command: OpenAmlCaseCommand) = Unit
}
