// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.onboarding.integration

import com.openbank.libs.security.Roles
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * `?stage=` is a FILTER, and the one outcome it must never produce is "more rows than you asked
 * for". Before this change `OnboardingResource.listRecords` parsed the parameter with
 * `runCatching { FunnelStage.valueOf(it) }.getOrNull()`, so an unrecognised stage became a legal
 * `null`, and `OnboardingProjectionService.listRecords` omits the predicate entirely when the
 * stage is null (`if (stage != null) repo.listByStage(...) else repo.listAll(...)`).
 *
 * The result was a **200 carrying every onboarding record** — PII included (legalName, email) —
 * for a caller who asked for one funnel stage and mistyped it. Nothing could observe it: the
 * response is well-formed, the status is a success, and `stageFilter` is simply absent from the
 * body rather than contradicting anything. Only a test that sends a deliberately unparseable
 * value and asserts the status can (issue #8699).
 *
 * The two 200 controls matter as much as the 400: they are what stops the fix from being a blanket
 * rejection. An ABSENT stage must keep meaning "all stages", so `absent` and `unparseable` stay
 * distinguishable — the property #8699 asks for by name.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.onboarding.it.OnboardingPostgresTestResource::class)
class OnboardingStageFilterIT {

    @Test
    @TestSecurity(user = "operator", roles = [Roles.OPERATOR])
    fun `an unparseable stage is rejected with 400, not answered with every record`() {
        Given {
            queryParam("stage", "KYC_OPEEN")
        } When {
            get("/api/v1/onboarding/records")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = [Roles.OPERATOR])
    fun `a valid stage is still accepted`() {
        Given {
            queryParam("stage", "kyc_open")
        } When {
            get("/api/v1/onboarding/records")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = [Roles.OPERATOR])
    fun `an absent stage still means all stages`() {
        When {
            get("/api/v1/onboarding/records")
        } Then {
            statusCode(200)
        }
    }
}
