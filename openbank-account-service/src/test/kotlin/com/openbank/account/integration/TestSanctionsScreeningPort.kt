// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.SanctionsEntityType
import com.openbank.account.application.port.out.SanctionsScreenResult
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process stub for the sanctions-service REST client. IT tests run without the
 * sanctions-service container, so the real [SanctionsScreeningAdapter] would throw
 * UnknownHostException. The default CLEAR response keeps unrelated account tests independent.
 *
 * ADR-0032 §C is tested at the unit level (AccountServiceTest.kt) — the IT suite
 * only verifies the HTTP contract (201 / 409 idempotency / pagination).
 */
@Mock
@ApplicationScoped
class TestSanctionsScreeningPort : AccountSanctionsScreeningPort {
    private val responses = ConcurrentHashMap<String, (String, String, SanctionsEntityType) -> SanctionsScreenResult>()

    fun respondFor(subjectId: String, response: (String, String, SanctionsEntityType) -> SanctionsScreenResult) {
        responses[subjectId] = response
    }

    fun clearOverrides() = responses.clear()

    override suspend fun screen(
        name: String,
        idempotencyKey: String,
        subjectId: String,
        entityType: SanctionsEntityType,
    ): SanctionsScreenResult = SanctionsScreenResult(status = "CLEAR", matchScore = 0.0, matchedName = null)
}
