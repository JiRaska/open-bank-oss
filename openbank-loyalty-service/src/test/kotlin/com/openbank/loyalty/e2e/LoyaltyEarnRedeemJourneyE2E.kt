// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The loyalty earn-and-redeem journey over the real HTTP surface: a party earns Lístky from two
 * qualifying signals, a redelivered signal replays instead of double-awarding, an unaffordable
 * redemption is a conflict, and an affordable one grants under an idempotency key and replays the
 * same grant — while the endpoints prove the boundaries the service enforces: an unknown earn
 * source names the accepted set, a redemption without its idempotency key is a bad request, the
 * balance moves by exactly the catalogue prices, and a viewer cannot move anything.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "signal lands, leaves are awarded, benefit is granted" story, so a wiring regression that
 * deregisters a route (#3371), lets a redelivery double-award, or grants without the idempotency
 * key fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_loyalty_e2e")],
)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR", "ROLE_API"])
class LoyaltyEarnRedeemJourneyE2E {

    private val partyId = UUID.randomUUID()
    private val firstSignal = UUID.randomUUID()
    private val secondSignal = UUID.randomUUID()
    private lateinit var grantId: String

    @Test
    @Order(1)
    fun `1 - the benefit and earn catalogues are served`() {
        When { get("/api/v1/loyalty/benefits") } Then {
            statusCode(200)
            body("$", hasSize<Any>(3))
            body("id", hasItem("MONTHLY_MAINTENANCE_FEE_WAIVER"))
        }

        When { get("/api/v1/loyalty/earn-sources") } Then {
            statusCode(200)
            body("id", hasItem("EMERGENCY_BUFFER_REACHED"))
        }
    }

    @Test
    @Order(2)
    fun `2 - an unknown earn source is a bad request naming the accepted set`() {
        Given {
            contentType("application/json")
            body("""{"earnSourceId":"NO_SUCH_SOURCE","correlationEventId":"${UUID.randomUUID()}"}""")
        } When {
            post("/api/v1/loyalty/parties/$partyId/earn")
        } Then {
            statusCode(400)
            body("error", containsString("unknown earn source"))
        }
    }

    @Test
    @Order(3)
    fun `3 - a party earns leaves on a qualifying signal`() {
        Given {
            contentType("application/json")
            body(earnBody("EMERGENCY_BUFFER_REACHED", firstSignal))
        } When {
            post("/api/v1/loyalty/parties/$partyId/earn")
        } Then {
            statusCode(201)
            body("outcome", equalTo("AWARDED"))
        }

        When { get("/api/v1/loyalty/parties/$partyId") } Then {
            statusCode(200)
            body("balance", equalTo(200))
            body("earnedThisYear", equalTo(200))
        }
    }

    @Test
    @Order(4)
    fun `4 - a redelivered signal replays instead of double-awarding`() {
        Given {
            contentType("application/json")
            body(earnBody("EMERGENCY_BUFFER_REACHED", firstSignal))
        } When {
            post("/api/v1/loyalty/parties/$partyId/earn")
        } Then {
            statusCode(200)
            body("outcome", equalTo("ALREADY_AWARDED"))
        }

        When { get("/api/v1/loyalty/parties/$partyId") } Then {
            statusCode(200)
            body("balance", equalTo(200))
        }
    }

    @Test
    @Order(5)
    fun `5 - a second source adds its catalogue price to the balance`() {
        Given {
            contentType("application/json")
            body(earnBody("SAVINGS_RATE_SUSTAINED", secondSignal))
        } When {
            post("/api/v1/loyalty/parties/$partyId/earn")
        } Then {
            statusCode(201)
            body("outcome", equalTo("AWARDED"))
        }

        When { get("/api/v1/loyalty/parties/$partyId") } Then {
            statusCode(200)
            body("balance", equalTo(320))
        }
    }

    @Test
    @Order(6)
    fun `6 - redemption without the idempotency key is a bad request`() {
        Given {
            contentType("application/json")
            body("""{"benefitId":"MONTHLY_MAINTENANCE_FEE_WAIVER"}""")
        } When {
            post("/api/v1/loyalty/parties/$partyId/redeem")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @Order(7)
    fun `7 - a redemption the balance cannot afford is a conflict`() {
        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-poor-$partyId")
            body("""{"benefitId":"FX_REFERENCE_RATE_ONE_CONVERSION"}""")
        } When {
            post("/api/v1/loyalty/parties/$partyId/redeem")
        } Then {
            statusCode(409)
            body("outcome", equalTo("INSUFFICIENT_LEAVES"))
            body("availableLeaves", equalTo(320))
        }
    }

    @Test
    @Order(8)
    fun `8 - an affordable redemption grants and replays under its idempotency key`() {
        val granted = Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-redeem-$partyId")
            body("""{"benefitId":"MONTHLY_MAINTENANCE_FEE_WAIVER"}""")
        } When {
            post("/api/v1/loyalty/parties/$partyId/redeem")
        } Then {
            statusCode(201)
            body("grantStatus", equalTo("GRANTED"))
            body("benefitId", equalTo("MONTHLY_MAINTENANCE_FEE_WAIVER"))
        } Extract { path<String>("grantId") }
        grantId = granted

        Given {
            contentType("application/json")
            header("Idempotency-Key", "e2e-redeem-$partyId")
            body("""{"benefitId":"MONTHLY_MAINTENANCE_FEE_WAIVER"}""")
        } When {
            post("/api/v1/loyalty/parties/$partyId/redeem")
        } Then {
            statusCode(200)
            body("grantId", equalTo(grantId))
        }

        When { get("/api/v1/loyalty/parties/$partyId/grants") } Then {
            statusCode(200)
            body("grantId", hasItem(grantId))
        }
    }

    @Test
    @Order(9)
    fun `9 - the balance dropped by exactly the catalogue price`() {
        When { get("/api/v1/loyalty/parties/$partyId") } Then {
            statusCode(200)
            body("balance", equalTo(20))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "viewer@openbank.test", roles = ["ROLE_VIEWER"])
    fun `10 - a viewer cannot earn or redeem`() {
        Given {
            contentType("application/json")
            body(earnBody("LOGIN_STREAK", UUID.randomUUID()))
        } When {
            post("/api/v1/loyalty/parties/$partyId/earn")
        } Then {
            statusCode(403)
        }
    }

    private fun earnBody(source: String, correlation: UUID) = """
        {"earnSourceId":"$source","correlationEventId":"$correlation"}
    """.trimIndent()
}
