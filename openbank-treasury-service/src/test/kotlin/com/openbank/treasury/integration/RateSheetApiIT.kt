// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Contract 8.5 over real HTTP and Postgres: back office proposes a rate sheet (PENDING); the proposer
 * cannot approve it even holding the approver role (four-eyes); a different approver activates it; a
 * second decision is 409; activating a newer sheet of the same name supersedes the older one.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RateSheetApiIT {
    private val base = "/api/v1/treasury/market-data/internal-rate-sheets"
    private val name get() = sheetName

    private fun propose(): String = given().contentType("application/json")
        .body(
            """{"name":"$name","effectiveFrom":"2026-10-20","rates":[{"label":"1M","value":3.5},{"label":"3M","value":3.6}]}""",
        )
        .`when`().post(base).then().statusCode(201).body("status", equalTo("PENDING")).extract().path("id")

    @Test
    @Order(1)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE", "ROLE_TREASURY_APPROVER"])
    fun `1 - proposed, and its proposer cannot approve it`() {
        first = propose()
        given().`when`().post(
            "$base/$first/approve",
        ).then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
        given().contentType("application/json").body("""{"name":"X","effectiveFrom":"2026-10-20","rates":[]}""")
            .`when`().post(base).then().statusCode(400)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - a different approver activates it, and a second decision is a conflict`() {
        given().`when`().post("$base/$first/approve").then().statusCode(200).body("status", equalTo("ACTIVE"))
            .body("decidedBy", equalTo("olga.approver"))
        given().contentType("application/json").body("""{"reason":"late"}""")
            .`when`().post("$base/$first/reject").then().statusCode(409)
        given().contentType(
            "application/json",
        ).body("""{"name":"X","effectiveFrom":"2026-10-20","rates":[{"label":"1M","value":1}]}""")
            .`when`().post(base).then().statusCode(403)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `3 - a newer sheet of the same name is proposed`() {
        second = propose()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `4 - activating it supersedes the older one`() {
        given().`when`().post("$base/$second/approve").then().statusCode(200).body("status", equalTo("ACTIVE"))
        given().`when`().get(
            "$base?status=ACTIVE",
        ).then().statusCode(200).body("findAll { it.name == '$name' }.id", equalTo(listOf(second)))
        given().`when`().get("$base?status=SUPERSEDED").then().statusCode(200)
            .body("findAll { it.name == '$name' }.id", equalTo(listOf(first)))
        given().`when`().post("$base/0191c0de-0000-7000-8000-000000000000/approve").then().statusCode(404)
    }

    private companion object {
        lateinit var first: String
        lateinit var second: String
        val sheetName = "Term deposits ${java.util.UUID.randomUUID()}"
    }
}
