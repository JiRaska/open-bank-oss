// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * A missing Berlin Group `Consent-ID` must answer 400 (#3624, follow-up to #3104).
 *
 * This has to be driven over REAL HTTP. The defect lives in JAX-RS parameter injection, so a unit
 * test that calls the handler supplies the very argument the framework does not — it passes against
 * the broken code and can never see the bug.
 *
 * What the old signature actually did here is worth recording, because it is NOT the 500 the issue
 * predicts. `getAccounts` is `suspend`, so Kotlin emits no `Intrinsics.checkNotNullParameter` and
 * the null simply flowed into the body — where `ctx.getProperty("tppId")` short-circuits to
 * `missingTpp()` first. Measured by reverting the fix and re-running this test: **401**, not 500.
 * That is the third and worst outcome the gate's header describes: with a valid TPP certificate
 * nothing short-circuits, and the null is carried into `GetAccountsQuery.consentId` — a field the
 * signature promised could not be null. The 401 is also a lie in its own right: it blames the
 * caller's certificate for a missing header.
 *
 * The second test is the control. With a `Consent-ID` present the request gets PAST the new guard
 * and fails on the absent TPP certificate instead — it passes both before and after the fix on
 * purpose, so that the first test's redness is attributable to the guard and nothing else.
 */
@QuarkusTest
@TestProfile(Psd2MissingHeaderStatusIT.AuthzOffProfile::class)
@QuarkusTestResource(com.openbank.psd2.it.PostgresRedisTestResource::class)
class Psd2MissingHeaderStatusIT {

    /**
     * `authz.enforce` defaults to true and there is no OPA sidecar under test, so the PDP fails
     * CLOSED and every `@Authorize` endpoint answers 503 before the handler is ever entered — which
     * would make this IT green about nothing (measured: both assertions saw 503 until this profile
     * was added). Turning enforcement off is what lets the request reach the parameter binding this
     * test is about. Literal values only: a profile loads in a different classloader from the test
     * class, so anything computed here is computed twice.
     */
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    fun `an absent Consent-ID on the AIS accounts read answers 400`() {
        Given { this } When { get("/open-banking/v2/accounts") } Then { statusCode(400) }
    }

    @Test
    fun `a present Consent-ID gets past the guard and fails on the absent TPP certificate`() {
        Given {
            header("Consent-ID", "11111111-2222-3333-4444-555555555555")
        } When {
            get("/open-banking/v2/accounts")
        } Then {
            statusCode(401)
        }
    }
}
