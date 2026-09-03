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
 * UnknownHostException. The default always returns CLEAR so account-open tests proceed.
 *
 * [behaviour] lets one test drive a HIT/REVIEW/unavailable outcome through the real endpoint —
 * `AccountOpeningScreeningStatusIT` uses it to assert the HTTP status of the gate's two refusal
 * paths, which no mocked-repository unit test can observe. `QuarkusMock.installMockForType` is not
 * an option here: it requires the replacement to be assignable to the resolved *bean class*, and a
 * mock of the port interface is not (`is not assignable to class TestSanctionsScreeningPort`).
 * Every test that sets it must [reset] afterwards — this bean is application-scoped and shared by
 * the whole IT suite.
 */
@Mock
@ApplicationScoped
class TestSanctionsScreeningPort : AccountSanctionsScreeningPort {

    /** `(name, idempotencyKey) -> result`; may also throw, to exercise the fail-closed path. */
    var behaviour: (String, String) -> SanctionsScreenResult = CLEAR

    override suspend fun screen(name: String, idempotencyKey: String): SanctionsScreenResult =
        behaviour(name, idempotencyKey)

    fun reset() {
        behaviour = CLEAR
    }

    companion object {
        private val CLEAR: (String, String) -> SanctionsScreenResult =
            { _, _ -> SanctionsScreenResult(status = "CLEAR", matchScore = 0.0, matchedName = null) }
    }
}
