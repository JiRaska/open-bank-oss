// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The recorded reviewer of a sanctions decision is the authenticated principal, never a body field.
 *
 * Drives the REAL HTTP surface (the use-case ITs bypass the resource, which is where the
 * contract lives). Two defects were measured before the fix, on both `/api/v1` and `/api/v3`:
 *  1. the admin-ui sends `{checkId, note, newStatus}` and the required, non-null `reviewedBy`
 *     constructor parameter made that body a 400, so the console's decision action never worked;
 *  2. a caller who did supply `reviewedBy` chose the reviewer recorded on a sanctions decision
 *     (spoofing / repudiation).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsReviewReviewerIdentityIT {

    private fun screen(major: Int): String = Given {
        contentType("application/json")
        body(
            """{"entityType":"INDIVIDUAL","name":"Reviewer Identity Probe","idempotencyKey":"${UUID.randomUUID()}"}""",
        )
    } When {
        post("/api/v$major/sanctions/screen")
    } Then {
        statusCode(201)
    } Extract {
        path("id")
    }

    private fun review(major: Int, body: String, expected: Int) {
        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v$major/sanctions/review")
        } Then {
            statusCode(expected)
        }
    }

    private fun storedReviewer(major: Int, id: String): String? = Given { this } When {
        get("/api/v$major/sanctions/$id")
    } Then {
        statusCode(200)
    } Extract {
        path("reviewedBy")
    }

    @Test
    @TestSecurity(user = "alice.operator", roles = ["ROLE_OPERATOR"])
    fun `v1 the exact admin-ui body is accepted and the reviewer is the principal`() {
        val id = screen(1)
        review(1, """{"checkId":"$id","note":"false positive","newStatus":"CLEAR"}""", 200)
        org.assertj.core.api.Assertions.assertThat(storedReviewer(1, id)).isEqualTo("alice.operator")
    }

    @Test
    @TestSecurity(user = "alice.operator", roles = ["ROLE_OPERATOR"])
    fun `v1 a body supplied reviewedBy is ignored in favour of the principal`() {
        val id = screen(1)
        review(
            1,
            """{"checkId":"$id","reviewedBy":"someone.else","note":"false positive","newStatus":"CLEAR"}""",
            200,
        )
        org.assertj.core.api.Assertions.assertThat(storedReviewer(1, id)).isEqualTo("alice.operator")
    }

    @Test
    @TestSecurity(user = "alice.operator", roles = ["ROLE_OPERATOR"])
    fun `v3 the exact admin-ui body is accepted and the reviewer is the principal`() {
        val id = screen(3)
        review(3, """{"checkId":"$id","note":"false positive","newStatus":"CLEAR"}""", 200)
        org.assertj.core.api.Assertions.assertThat(storedReviewer(3, id)).isEqualTo("alice.operator")
    }

    @Test
    @TestSecurity(user = "alice.operator", roles = ["ROLE_OPERATOR"])
    fun `v3 a body supplied reviewedBy is ignored in favour of the principal`() {
        val id = screen(3)
        review(
            3,
            """{"checkId":"$id","reviewedBy":"someone.else","note":"false positive","newStatus":"CLEAR"}""",
            200,
        )
        org.assertj.core.api.Assertions.assertThat(storedReviewer(3, id)).isEqualTo("alice.operator")
    }

    @Test
    @TestSecurity(user = "alice.operator", roles = ["ROLE_OPERATOR"])
    fun `a body without a checkId is still a 400`() {
        review(1, """{"note":"x","newStatus":"CLEAR"}""", 400)
    }
}
