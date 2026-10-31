// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsApiVersioningIT {

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v3 screening reports the Czech anti-terrorism list`() {
        Given {
            contentType("application/json")
            body(payload(listOf("CZ_TERRORISTS")))
        } When {
            post("/api/v3/sanctions/screen")
        } Then {
            statusCode(201)
            body("checkedLists", hasItem("CZ_TERRORISTS"))
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v1 default screening keeps the original seven-list scope`() {
        Given {
            contentType("application/json")
            body(payload(null))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(201)
            body("checkedLists", hasSize<String>(7))
            body("checkedLists", not(hasItem("CZ_TERRORISTS")))
            header("Deprecation", "true")
            header("Link", equalTo("</api/v3/sanctions/screen>; rel=\"successor-version\""))
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v1 rejects the v3-only list instead of returning an unknown enum`() {
        Given {
            contentType("application/json")
            body(payload(listOf("CZ_TERRORISTS")))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v1 idempotent replay cannot leak a v3-only result`() {
        val key = UUID.randomUUID().toString()
        Given {
            contentType("application/json")
            body(payload(listOf("CZ_TERRORISTS"), key))
        } When {
            post("/api/v3/sanctions/screen")
        } Then {
            statusCode(201)
        }

        Given {
            contentType("application/json")
            body(payload(null, key))
        } When {
            post("/api/v1/sanctions/screen")
        } Then {
            statusCode(400)
            body("checkedLists", nullValue())
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `v3-only checks are readable and reviewable only through v3`() {
        val id = createV3OnlyCheck()
        review(id, 1, "HIT", 404)
        review(id, 3, "POTENTIAL_HIT", 200)

        Given { this } When { get("/api/v1/sanctions/pending") } Then {
            statusCode(200)
            body("id", not(hasItem(id)))
        }
        Given { this } When { get("/api/v3/sanctions/pending") } Then {
            statusCode(200)
            body("id", hasItem(id))
        }

        review(id, 3, "HIT", 200)
        Given { this } When { get("/api/v1/sanctions/hits") } Then {
            statusCode(200)
            body("id", not(hasItem(id)))
        }
        Given { this } When { get("/api/v3/sanctions/hits") } Then {
            statusCode(200)
            body("id", hasItem(id))
        }
        Given { this } When { get("/api/v1/sanctions") } Then {
            statusCode(200)
            body("id", not(hasItem(id)))
        }
        Given { this } When { get("/api/v3/sanctions") } Then {
            statusCode(200)
            body("id", hasItem(id))
        }
        Given { this } When { get("/api/v1/sanctions/$id") } Then { statusCode(404) }
        Given { this } When { get("/api/v3/sanctions/$id") } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("checkedLists", hasItem("CZ_TERRORISTS"))
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `only superseded v1 checks carry deprecation and successor headers`() {
        val id = createV3OnlyCheck()
        listOf("/api/v1/sanctions", "/api/v1/sanctions/$id").forEach { path ->
            Given { this } When { get(path) } Then {
                header("X-API-Version", "v1")
                header("Deprecation", "true")
                header("Sunset", notNullValue())
                header("Link", equalTo("<${path.replace("/api/v1/", "/api/v3/")}>; rel=\"successor-version\""))
            }
        }
        Given { this } When { get("/api/v1/sanctions/lists") } Then {
            header("Deprecation", nullValue())
        }
        Given { this } When { get("/api/v3/sanctions/$id") } Then {
            header("X-API-Version", "v3")
            header("Deprecation", nullValue())
        }
    }

    private fun createV3OnlyCheck(): String = Given {
        contentType("application/json")
        body(payload(listOf("CZ_TERRORISTS")))
    } When {
        post("/api/v3/sanctions/screen")
    } Then {
        statusCode(201)
    } Extract {
        path("id")
    }

    private fun review(id: String, major: Int, status: String, expectedStatus: Int) {
        Given {
            contentType("application/json")
            body("""{"checkId":"$id","reviewedBy":"reviewer","note":"version boundary","newStatus":"$status"}""")
        } When {
            post("/api/v$major/sanctions/review")
        } Then {
            statusCode(expectedStatus)
        }
    }

    private fun payload(listTypes: List<String>?, key: String = UUID.randomUUID().toString()): String {
        val lists = listTypes?.joinToString(",") { "\"$it\"" }?.let { ",\"listTypes\":[$it]" }.orEmpty()
        return """{"entityType":"INDIVIDUAL","name":"API Version Probe","idempotencyKey":"$key"$lists}"""
    }
}
