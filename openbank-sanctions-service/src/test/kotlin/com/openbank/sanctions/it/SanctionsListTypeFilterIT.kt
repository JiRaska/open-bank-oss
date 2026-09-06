// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `listTypes` decides WHICH sanctions lists a screening actually consults, so a value that is
 * dropped rather than rejected produces a result whose scope nobody chose (#8699).
 *
 * `resolveTargetLists` used to `mapNotNull` over `SanctionsListType.valueOf`, which is two
 * distinct defects depending on how many names were misspelled:
 *
 *  - **Partial screen reported as a complete one.** `["OFAC_SDN", "EU_CONSOLIDTED"]` kept OFAC,
 *    dropped EU, and returned a normal 201 — typically `CLEAR` — computed without ever consulting
 *    the EU list. `checkedLists` records what was checked, not what was asked for, so the response
 *    does not contradict itself and no caller can tell.
 *  - **A single typo widens to everything.** `["EU_CONSOLIDTED"]` mapped to an empty list, and
 *    `takeIf { it.isNotEmpty() }` then fell through to all seven lists.
 *
 * Both are invisible to a unit test that asserts a 2xx, which is why this drives the real
 * endpoint and asserts the status. The 201 controls are what keep the fix from becoming a blanket
 * rejection: ABSENT `listTypes` must go on meaning "all lists", so absent and unparseable stay
 * distinguishable.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsListTypeFilterIT {

    private fun screen(body: String) = Given {
        contentType("application/json")
        body(body)
    } When {
        post("/api/v1/sanctions/screen")
    }

    private fun payload(listTypes: String?) = buildString {
        append("""{"entityType":"INDIVIDUAL","name":"Filter Probe",""")
        append(""""idempotencyKey":"${UUID.randomUUID()}"""")
        if (listTypes != null) append(""","listTypes":$listTypes""")
        append("}")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `one unparseable list type among valid ones is rejected, not silently dropped`() {
        screen(payload("""["OFAC_SDN","EU_CONSOLIDTED"]""")) Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `an entirely unparseable list type is rejected, not widened to every list`() {
        screen(payload("""["EU_CONSOLIDTED"]""")) Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `valid list types are screened and reported as the checked scope`() {
        screen(payload("""["OFAC_SDN","EU_CONSOLIDATED"]""")) Then {
            statusCode(201)
            body("checkedLists", hasSize<String>(2))
            body("checkedLists", hasItem("OFAC_SDN"))
            body("checkedLists", hasItem("EU_CONSOLIDATED"))
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `absent list types still means every list`() {
        screen(payload(null)) Then {
            statusCode(201)
            body("checkedLists", hasSize<String>(SanctionsListTypeCount.ALL))
        }
    }
}

private object SanctionsListTypeCount {
    const val ALL = 7
}
