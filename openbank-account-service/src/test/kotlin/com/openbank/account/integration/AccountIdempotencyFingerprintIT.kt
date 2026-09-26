// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.usecase.AccountService
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.arc.ClientProxy
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #10916 — an `Idempotency-Key` is bound to the request it was first used with. Driven through the
 * real REST endpoint with the real Redis store and Postgres: a same-body retry replays, a different
 * body under the same key is refused with 409 IDEMPOTENCY_KEY_REUSED and opens nothing, a retry
 * whose JSON differs only in key order, whitespace or an explicit `null` still replays (the
 * fingerprint is taken over the canonicalised DTO), a failed open releases the key, and the
 * durable `account_idempotency.request_hash` check still refuses a reused key once the Redis
 * record is gone.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountIdempotencyFingerprintIT {

    private val productId = UUID.fromString("00000000-2222-0000-0000-000000000001")

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    lateinit var useCase: AccountUseCase

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `same key and same body replays the first response without a second account`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        val first = open(key, body(partyId, "Test Customer"))
        assertThat(first.statusCode).isEqualTo(201)

        val replay = open(key, body(partyId, "Test Customer"))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `same key and a different body is refused with 409 and opens nothing`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        assertThat(open(key, body(partyId, "Test Customer")).statusCode).isEqualTo(201)

        val reused = open(key, body(partyId, "Someone Else"))
        assertThat(reused.statusCode).isEqualTo(409)
        assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(reused.header("X-Idempotency-Replayed")).isNull()
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `same body with different key order and whitespace still replays`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        assertThat(open(key, body(partyId, "Test Customer")).statusCode).isEqualTo(201)

        val reordered = """{"legalName":"Test Customer",  "currencyCode":"CZK","accountType":"CURRENT",
            "productId":"$productId",
               "partyId":"$partyId"}"""
        val replay = open(key, reordered)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `an explicit null field fingerprints the same as an absent one`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        assertThat(open(key, body(partyId, "Test Customer")).statusCode).isEqualTo(201)

        val withNull = """
            {"partyId":"$partyId","productId":"$productId","accountType":"CURRENT",
             "currencyCode":"CZK","termsVersion":null,"legalName":"Test Customer"}
        """.trimIndent()
        val replay = open(key, withNull)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a failed open releases the key so the same request can be retried`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        val real = ClientProxy.unwrap(useCase) as AccountService
        val failingOnce = mockk<AccountService>()
        var calls = 0
        coEvery { failingOnce.openAccount(any()) } coAnswers {
            check(++calls > 1) { "transient failure on the first open" }
            real.openAccount(firstArg())
        }
        // The GET used by accountsOf() goes through the same (now mocked) bean.
        coEvery { failingOnce.listAccounts(any()) } coAnswers { real.listAccounts(firstArg()) }
        QuarkusMock.installMockForType(failingOnce, AccountUseCase::class.java)

        // IllegalStateException is mapped to 422 by libs-runtime; any non-2xx proves the open failed.
        assertThat(open(key, body(partyId, "Test Customer")).statusCode).isEqualTo(422)
        assertThat(accountsOf(partyId)).isEmpty()

        // Without release() the in-flight marker would hold the key: 409 IN_PROGRESS for 5 minutes.
        val retry = open(key, body(partyId, "Test Customer"))
        assertThat(retry.statusCode).isEqualTo(201)
        assertThat(retry.header("X-Idempotency-Replayed")).isNull()
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `after the Redis record is gone a different body under the same key is still refused by the database`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        assertThat(open(key, body(partyId, "Test Customer")).statusCode).isEqualTo(201)
        evictRedis(key)

        val reused = open(key, body(partyId, "Someone Else"))
        assertThat(reused.statusCode).isEqualTo(409)
        assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `after the Redis record is gone the same body under the same key returns the existing account`() {
        val key = UUID.randomUUID().toString()
        val partyId = UUID.randomUUID()
        val first = open(key, body(partyId, "Test Customer"))
        assertThat(first.statusCode).isEqualTo(201)
        evictRedis(key)

        val again = open(key, body(partyId, "Test Customer"))
        assertThat(again.statusCode).isEqualTo(201)
        assertThat(again.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
        assertThat(accountsOf(partyId)).hasSize(1)
    }

    private fun evictRedis(key: String) {
        redis.key().del("idempotency:$key").await().indefinitely()
    }

    private fun open(key: String, json: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(json)
        .post("/api/v1/accounts")

    private fun body(partyId: UUID, legalName: String) = """
        {
          "partyId": "$partyId",
          "productId": "$productId",
          "accountType": "CURRENT",
          "currencyCode": "CZK",
          "legalName": "$legalName"
        }
    """.trimIndent()

    private fun accountsOf(partyId: UUID): List<Map<String, Any>> {
        val resp = RestAssured.given()
            .queryParam("partyId", partyId.toString())
            .get("/api/v1/accounts")
        check(resp.statusCode == 200) { "GET accounts -> ${resp.statusCode}: ${resp.body.asString()}" }
        return resp.jsonPath().getList("data")
    }

    private companion object {
        const val OPERATOR = "00000000-0000-0000-0000-000000000099"
    }
}
