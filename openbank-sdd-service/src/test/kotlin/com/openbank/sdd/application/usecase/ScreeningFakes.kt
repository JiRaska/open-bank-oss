// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.sdd.application.port.out.AmlCasePort
import com.openbank.sdd.application.port.out.OpenAmlCaseCommand
import com.openbank.sdd.application.port.out.SanctionsScreeningPort
import com.openbank.sdd.application.port.out.ScreeningUnavailableException
import io.smallrye.mutiny.Uni

/** Sanctions port fake: CLEAR unless a role is configured otherwise. */
class FakeSanctions : SanctionsScreeningPort {
    val statuses = mutableMapOf<ScreeningRole, ScreeningMatchStatus>()
    val scores = mutableMapOf<ScreeningRole, Double>()
    var unavailable = false
    val screened = mutableListOf<Pair<String, ScreeningRole>>()

    override fun screen(name: String, role: ScreeningRole, idempotencyKey: String): Uni<ScreeningResult> {
        screened += name to role
        if (unavailable) return Uni.createFrom().failure(ScreeningUnavailableException(IllegalStateException("down")))
        val status = statuses[role] ?: ScreeningMatchStatus.CLEAR
        return Uni.createFrom().item(
            ScreeningResult(
                name,
                role,
                status,
                scores[role] ?: 0.0,
                "match-$role".takeIf {
                    status !=
                        ScreeningMatchStatus.CLEAR
                },
            ),
        )
    }
}

/** AML case port fake: records commands; optionally fails to prove the verdict does not flip. */
class FakeAmlCases : AmlCasePort {
    val opened = mutableListOf<OpenAmlCaseCommand>()
    var fail = false

    override fun openCase(command: OpenAmlCaseCommand): Uni<Void> {
        opened += command
        return if (fail) Uni.createFrom().failure(IllegalStateException("aml down")) else Uni.createFrom().voidItem()
    }
}
