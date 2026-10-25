// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import com.openbank.kyc.application.port.out.PepScreeningPort
import com.openbank.kyc.application.port.out.PepScreeningResult
import com.openbank.kyc.application.port.out.PepScreeningStatus
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CDI alternative replacing the real sanctions-service REST adapter for the E2E suite.
 *
 * [KycKafkaTestResource] points the rest client at an unreachable address, which exercises the
 * UNAVAILABLE path only. The re-screen journey needs a deterministic screen outcome per test —
 * CLEAR, a PEP match, or an unreachable downstream — so the port is replaced wholesale (the
 * same `@Alternative @Priority(1)` stub pattern the party E2E suite uses for its guards). Tests
 * set [nextResult], and every call is recorded in [calls] so a test can assert the screening
 * actually ran with the expected name and idempotency key.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class E2EPepScreeningStub : PepScreeningPort {

    @Volatile
    var nextResult: PepScreeningResult = PepScreeningResult(
        status = PepScreeningStatus.CLEAR,
        matchScore = 0.05,
        matchedName = null,
    )

    val calls = CopyOnWriteArrayList<Pair<String, String>>()

    override suspend fun screenForPep(name: String, idempotencyKey: String): PepScreeningResult {
        calls += name to idempotencyKey
        return nextResult
    }

    fun reset() {
        nextResult = PepScreeningResult(
            status = PepScreeningStatus.CLEAR,
            matchScore = 0.05,
            matchedName = null,
        )
        calls.clear()
    }
}
