// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * Real HTTP/Postgres proof that, with the period lock in ENFORCE mode, a posting into a FROZEN
 * month is refused with 409 and the machine code a caller can branch on, while a posting into an
 * open month still succeeds. The expected code is a LITERAL, not the constant: deriving both sides
 * from the same constant would stay green if the wire value drifted.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    restrictToAnnotatedClass = true,
)
@TestProfile(FrozenPeriodRefusalCodeIT.EnforcePeriodLockProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FrozenPeriodRefusalCodeIT {

    class EnforcePeriodLockProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.ledger.period-lock.mode" to "enforce",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = "lock-maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts and drafts March`() {
        post("2024-03-15").statusCode(201)
        given().contentType("application/json").`when`().post("/api/v1/ledger/periods/MONTH/2024-03-31")
            .then().statusCode(200)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "lock-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes March`() {
        given().contentType("application/json").`when`().post("/api/v1/ledger/periods/MONTH/2024-03-31/freeze")
            .then().statusCode(200)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "lock-maker", roles = ["ROLE_OPERATOR"])
    fun `a posting into the frozen month is a 409 carrying ACCOUNTING_PERIOD_FROZEN`() {
        post("2024-03-20")
            .statusCode(409)
            .body("code", org.hamcrest.Matchers.equalTo("ACCOUNTING_PERIOD_FROZEN"))
            .body("error", org.hamcrest.Matchers.containsString("FROZEN"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "lock-maker", roles = ["ROLE_OPERATOR"])
    fun `negative case - a posting into the still open next month is accepted`() {
        post("2024-04-10").statusCode(201)
    }

    private fun post(date: String): ValidatableResponse {
        val journal = """
            {"idempotencyKey":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$date","valueDate":"$date","description":"period lock code IT",
             "createdBy":"00000000-0000-0000-0000-000000000703","lines":[
             {"glAccountId":"a0000000-0000-0000-0000-000000000001","side":"DEBIT","amount":"10","currencyCode":"CZK","baseAmount":"10","baseCurrencyCode":"CZK"},
             {"glAccountId":"a0000000-0000-0000-0000-000000004100","side":"CREDIT","amount":"10","currencyCode":"CZK","baseAmount":"10","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
        return given().contentType("application/json").body(journal).`when`().post("/api/v1/journals").then()
    }
}
