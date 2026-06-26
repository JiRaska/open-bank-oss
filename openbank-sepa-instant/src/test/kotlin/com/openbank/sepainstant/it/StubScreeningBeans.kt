// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.it

import com.openbank.sepainstant.application.port.out.AmlCasePort
import com.openbank.sepainstant.application.port.out.OpenAmlCaseCommand
import com.openbank.sepainstant.application.port.out.SanctionsScreeningPort
import com.openbank.sepainstant.domain.screening.ScreeningMatchStatus
import com.openbank.sepainstant.domain.screening.ScreeningResult
import com.openbank.sepainstant.domain.screening.ScreeningRole
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

@ApplicationScoped
@Alternative
@Priority(1)
class StubSanctionsScreeningPort : SanctionsScreeningPort {
    override fun screen(name: String, role: ScreeningRole, idempotencyKey: String): Uni<ScreeningResult> =
        Uni.createFrom().item(
            ScreeningResult(
                subject = name,
                role = role,
                status = ScreeningMatchStatus.CLEAR,
                score = 0.0,
                matchedEntity = null
            )
        )
}

@ApplicationScoped
@Alternative
@Priority(1)
class StubAmlCasePort : AmlCasePort {
    override fun openCase(command: OpenAmlCaseCommand): Uni<Void> =
        Uni.createFrom().voidItem()
}
