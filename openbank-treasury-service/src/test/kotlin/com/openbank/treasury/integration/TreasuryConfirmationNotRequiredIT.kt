// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0315 D2 backward compatibility, through the real config source: with
 * `openbank.treasury.confirmation.required=false` a BOOKED deal — which is exactly what every deal
 * booked before CONFIRMED existed still is, since there is no data migration — settles directly,
 * as it did before. [TreasuryDealConfirmationIT] is the other half (the default, true).
 */
@QuarkusTest
@TestProfile(TreasuryConfirmationNotRequiredIT.NotRequired::class)
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryConfirmationNotRequiredIT {

    /** Literal values only: a profile loads in a different classloader from the test (root CLAUDE.md). */
    class NotRequired : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.treasury.confirmation.required" to "false")
    }

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun action(id: String, verb: String) = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .`when`().post("/api/v1/treasury/deals/$id/$verb")

    @Test
    @Order(1)
    @TestSecurity(user = "lena.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts and submits`() {
        dealId = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"product":"MM_BORROWING","counterpartyId":"SIMBK-A","currency":"CZK","principal":1500.00,
                   "rate":3.90,"valueDate":"$today","maturityDate":"${today.plusDays(7)}"}""",
            )
            .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")
        action(dealId, "submit").then().statusCode(200)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "otto.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - a BOOKED deal settles without a confirmation step`() {
        action(dealId, "approve").then().statusCode(200).body("state", equalTo("BOOKED"))
        action(dealId, "settle").then().statusCode(200)
            .body("state", equalTo("SETTLED"))
            .body("history.to", not(hasItem("CONFIRMED")))
    }

    companion object {
        private lateinit var dealId: String
    }
}
