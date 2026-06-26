// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Runtime enforcement of the K7 / ADR-0018 access-control contract through the real HTTP
 * stack (complements the reflection-based ClearingSecurityContractTest): settlement and
 * cycle triggering are the high-blast-radius money-path actions, so a read-only role or
 * an anonymous caller must never reach them.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.clearing.it.PostgresRedpandaRedisTestResource::class)
class ClearingApiAccessControlIT {

    @Test
    fun `anonymous caller cannot submit a payment`() {
        Given {
            contentType("application/json")
            body("""{"paymentId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/clearing/submit")
        } Then {
            statusCode(401)
        }
    }

    @Test
    fun `anonymous caller cannot settle a batch`() {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/batches/${UUID.randomUUID()}/settle")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `viewer cannot settle a batch`() {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/batches/${UUID.randomUUID()}/settle")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `viewer cannot trigger a clearing cycle`() {
        Given { contentType("application/json") } When {
            post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `viewer can read batches`() {
        Given { this } When {
            get("/api/v1/clearing/batches")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET batch by unknown id returns 404`() {
        Given { this } When {
            get("/api/v1/clearing/batches/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }
}
