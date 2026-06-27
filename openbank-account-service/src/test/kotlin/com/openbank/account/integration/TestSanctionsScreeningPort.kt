// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.SanctionsScreenResult
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped

/**
 * In-process stub for the sanctions-service REST client. IT tests run without the
 * sanctions-service container, so the real [SanctionsScreeningAdapter] would throw
 * UnknownHostException. This mock always returns CLEAR so account-open tests proceed.
 *
 * ADR-0032 §C is tested at the unit level (AccountServiceTest.kt) — the IT suite
 * only verifies the HTTP contract (201 / 409 idempotency / pagination).
 */
@Mock
@ApplicationScoped
class TestSanctionsScreeningPort : AccountSanctionsScreeningPort {
    override suspend fun screen(name: String, idempotencyKey: String): SanctionsScreenResult =
        SanctionsScreenResult(status = "CLEAR", matchScore = 0.0, matchedName = null)
}
