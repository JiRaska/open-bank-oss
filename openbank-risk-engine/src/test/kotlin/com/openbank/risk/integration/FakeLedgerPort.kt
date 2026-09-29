// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.model.LedgerInputs
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate

/**
 * Replaces the ledger REST adapter in @QuarkusTest: the test sets [inputs] and the snapshot is
 * built from exactly that, with everything below the port (REST, tie-out, Postgres) real.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeLedgerPort : LedgerPort {
    @Volatile
    var inputs: LedgerInputs = Fixtures.tiedOut()

    override suspend fun read(asOf: LocalDate): LedgerInputs = inputs.copy(asOf = asOf)
}
