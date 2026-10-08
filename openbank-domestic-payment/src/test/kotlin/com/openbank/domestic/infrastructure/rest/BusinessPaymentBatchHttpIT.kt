// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.integration.DomesticPaymentBootSmokeIT
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private const val BATCH_BASE = "/api/v1/business-payment-batches"
private const val COMPANY = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val HUMAN = "11111111-1111-4111-8111-111111111111"
private const val FOREIGN = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        Claim(key = "azp", value = "openbank-edge"),
    ],
)
class BusinessPaymentBatchHttpIT {
    @Test
    // One transaction lifecycle exercises create, durable replay, company isolation and optimistic replacement.
    @Suppress("LongMethod")
    fun `draft HTTP contract scopes company and preserves idempotency`() {
        val key = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID()
        val body = """
            {
              "debtorAccountId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              "items":[{
                "itemId":"$itemId",
                "creditorAccountNumber":"123456789",
                "creditorBankCode":"0800",
                "creditorName":"Supplier",
                "amountMinor":125,
                "currency":"CZK"
              }]
            }
        """.trimIndent()
        val noActor = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .body(body)
            .post(BATCH_BASE)
        assertEquals(400, noActor.statusCode)
        val tooLarge = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .body("{" + "x".repeat(65_000) + "}")
            .post(BATCH_BASE)
        assertEquals(400, tooLarge.statusCode)
        val empty = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .body("""{"debtorAccountId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","items":[]}""")
            .post(BATCH_BASE)
        assertEquals(400, empty.statusCode)

        val created = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .body(body)
            .post(BATCH_BASE)
        assertEquals(201, created.statusCode, created.asString())
        assertDraftResponse(created.asString())
        assertEquals("DRAFT", created.jsonPath().getString("state"))
        assertEquals(125L, created.jsonPath().getLong("totalAmountMinor"))
        val id = created.jsonPath().getString("id")

        val replay = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .body(body)
            .post(BATCH_BASE)
        assertEquals(200, replay.statusCode)
        assertDraftResponse(replay.asString())
        assertEquals(id, replay.jsonPath().getString("id"))

        val changedInvalid = body.replace("\"amountMinor\":125", "\"amountMinor\":0")
        val conflict = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .body(changedInvalid)
            .post(BATCH_BASE)
        assertEquals(409, conflict.statusCode)
        val unknownChanged = body.dropLast(1) + ",\"state\":\"COMPLETE\"}"
        val unknownConflict = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .body(unknownChanged)
            .post(BATCH_BASE)
        assertEquals(409, unknownConflict.statusCode)

        assertEquals(404, RestAssured.given().header("X-Customer-Party-Id", FOREIGN).get("$BATCH_BASE/$id").statusCode)
        val foreignList = RestAssured.given().header("X-Customer-Party-Id", FOREIGN).get(BATCH_BASE)
        assertEquals(200, foreignList.statusCode)
        assertEquals(0, foreignList.jsonPath().getList<Any>("data").size)
        val listing = RestAssured.given().header("X-Customer-Party-Id", COMPANY).get(BATCH_BASE)
        assertEquals(200, listing.statusCode)
        assertEquals(
            setOf("data", "page", "size"),
            ObjectMapper().readTree(listing.asString()).fieldNames().asSequence().toSet(),
        )
        assertEquals(id, listing.jsonPath().getString("data[0].id"))
        val detail = RestAssured.given().header("X-Customer-Party-Id", COMPANY).get("$BATCH_BASE/$id")
        assertEquals(200, detail.statusCode)
        assertDraftResponse(detail.asString())
        assertEquals(1, detail.jsonPath().getInt("itemCount"))
        val replacementItems = ObjectMapper().readTree(body).get("items").toString()
            .replace("\"amountMinor\":125", "\"amountMinor\":250")
        val replace = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("If-Match", "0")
            .contentType("application/json")
            .body("""{"items":$replacementItems}""")
            .put("$BATCH_BASE/$id/items")
        assertEquals(200, replace.statusCode)
        assertDraftResponse(replace.asString())
        assertEquals(1, replace.jsonPath().getInt("itemCount"))
        assertEquals("DRAFT", replace.jsonPath().getString("state"))
        assertEquals(250L, replace.jsonPath().getLong("totalAmountMinor"))
        val replayAfterReplace = RestAssured.given()
            .header("X-Customer-Party-Id", COMPANY)
            .header("X-Actor-Party-Id", HUMAN)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .body(body)
            .post(BATCH_BASE)
        assertEquals(200, replayAfterReplace.statusCode)
        assertEquals(
            ObjectMapper().readTree(created.asString()),
            ObjectMapper().readTree(replayAfterReplace.asString()),
        )
        val current = RestAssured.given().header("X-Customer-Party-Id", COMPANY).get("$BATCH_BASE/$id")
        assertEquals(250L, current.jsonPath().getLong("totalAmountMinor"))
    }

    private fun assertDraftResponse(body: String) {
        val node = ObjectMapper().readTree(body)
        assertEquals(
            setOf(
                "id", "state", "debtorAccountId", "itemCount", "totalAmountMinor",
                "currency", "revision", "createdAt", "updatedAt", "items",
            ),
            node.fieldNames().asSequence().toSet(),
        )
        assertTrue(node.path("items").isArray)
        val itemFields = node.path("items").first().fieldNames().asSequence().toSet()
        assertTrue(
            itemFields.containsAll(
                setOf("itemId", "creditorAccountNumber", "creditorBankCode", "creditorName", "amountMinor", "currency"),
            ),
        )
    }
}
