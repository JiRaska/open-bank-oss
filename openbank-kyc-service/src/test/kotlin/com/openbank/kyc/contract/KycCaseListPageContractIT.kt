// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.contract

import com.openbank.kyc.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Issue #8163: the committed `openapi.yaml` (1.7.0) declared `GET /api/v1/kyc/cases` as a raw
 * array while `KycResource.listCases` has always returned the envelope
 * `{items,total,page,size,statusFilter}`. #8164 corrected the DOCUMENT (bumped to 1.8.0) — this
 * test is the runtime half that PR deliberately deferred: it proves the corrected document
 * actually matches what the live endpoint returns, for both the first page and a later page, so
 * the Admin UI's server-backed pagination (this same PR) can rely on the declared contract
 * instead of the implementation shape "opportunistically".
 */
@QuarkusTest
@QuarkusTestResource(KycCaseListPageContractIT.NoAuthzResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class KycCaseListPageContractIT {

    class NoAuthzResource : QuarkusTestResourceLifecycleManager {
        // No OPA sidecar in a test JVM — see KycOutboxWriteIT for the same rationale. The subject
        // here is the response envelope, not the authz decision.
        override fun start(): Map<String, String> = mapOf("authz.enforce" to "false")
        override fun stop() = Unit
    }

    private fun openCase(partyId: UUID = UUID.randomUUID()): UUID {
        val id = Given {
            contentType("application/json")
            body("""{"partyId":"$partyId"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        assertThat(id).isNotBlank()
        return UUID.fromString(id)
    }

    @Test
    @TestSecurity(user = "contract-it", roles = ["ROLE_ADMIN"])
    fun `page 0 returns the declared envelope shape with size respected and no filter echoed`() {
        repeat(3) { openCase() }

        Given {
            queryParam("page", 0)
            queryParam("size", 2)
        } When {
            get("/api/v1/kyc/cases")
        } Then {
            statusCode(200)
            body("page", org.hamcrest.Matchers.equalTo(0))
            body("size", org.hamcrest.Matchers.equalTo(2))
            body("items", org.hamcrest.Matchers.hasSize<Any>(2))
            body("total", org.hamcrest.Matchers.greaterThanOrEqualTo(3))
            body("statusFilter", org.hamcrest.Matchers.nullValue())
        }
    }

    @Test
    @TestSecurity(user = "contract-it", roles = ["ROLE_ADMIN"])
    fun `a later page echoes its own page number and total stays consistent across pages`() {
        repeat(5) { openCase() }

        val page0Total = Given {
            queryParam("page", 0)
            queryParam("size", 2)
        } When {
            get("/api/v1/kyc/cases")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getLong("total")
        }

        Given {
            queryParam("page", 1)
            queryParam("size", 2)
        } When {
            get("/api/v1/kyc/cases")
        } Then {
            statusCode(200)
            body("page", org.hamcrest.Matchers.equalTo(1))
            body("total", org.hamcrest.Matchers.equalTo(page0Total.toInt()))
        }
    }

    @Test
    @TestSecurity(user = "contract-it", roles = ["ROLE_ADMIN"])
    fun `status filter is echoed back as statusFilter, matching the declared enum`() {
        Given {
            queryParam("status", "OPEN")
        } When {
            get("/api/v1/kyc/cases")
        } Then {
            statusCode(200)
            body("statusFilter", org.hamcrest.Matchers.equalTo("OPEN"))
        }
    }
}
