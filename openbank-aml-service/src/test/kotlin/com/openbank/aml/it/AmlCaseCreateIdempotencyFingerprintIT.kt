// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.usecase.AmlCaseService
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.arc.ClientProxy
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * #10916 through real HTTP and real Redis: the Idempotency-Key of `POST /api/v1/aml/cases` is
 * bound to the request it was first used for. A retry replays; the same key with a different body
 * is refused 409 IDEMPOTENCY_KEY_REUSED and opens no case; key order, whitespace and an explicit
 * `null` do not count as a different body; a failed create releases the key; and the durable
 * `aml_cases.request_hash` check still refuses a reused key once the Redis record is gone.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlCaseCreateIdempotencyFingerprintIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Inject
    lateinit var useCase: AmlCaseUseCase

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `same key and same body replays the first case and opens no second one`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ALERT-A"), expect = 201)
        assertThat(caseCount(party)).isEqualTo(1)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body(party, "ALERT-A"))
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `same key with a different body is refused 409 and opens no case`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        create(key, body(party, "ALERT-A"), expect = 201)

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(body(party, "ALERT-B"))
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(409)
            body("code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        }
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `same body with different key order and whitespace still replays`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ALERT-A"), expect = 201)
        val reordered = """
            {  "alertCode" : "ALERT-A",  "riskLevel":"MEDIUM",
               "screeningType":"MANUAL_INVESTIGATION",   "customerReference":"REF-1",
               "partyId":"$party" }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", key)
            body(reordered)
        } When {
            post("/api/v1/aml/cases")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", "true")
            body("id", equalTo(first))
        }
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `an explicit null field fingerprints the same as an absent one`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ALERT-A"), expect = 201)
        val withNull = body(party, "ALERT-A").replace("}", ""","alertDetail":null}""")

        val replay = post(key, withNull)
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(first)
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `a failed create releases the key so the same request can be retried`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val real = ClientProxy.unwrap(useCase) as AmlCaseService
        val failingOnce = mockk<AmlCaseService>()
        var calls = 0
        coEvery { failingOnce.createCase(any()) } coAnswers {
            check(++calls > 1) { "transient failure on the first create" }
            real.createCase(firstArg())
        }
        QuarkusMock.installMockForType(failingOnce, AmlCaseUseCase::class.java)

        // IllegalStateException is mapped to 422 by libs-runtime; any non-2xx proves the create failed.
        assertThat(post(key, body(party, "ALERT-A")).statusCode).isEqualTo(422)
        assertThat(caseCount(party)).isEqualTo(0)

        // Without release() the in-flight marker would hold the key: 409 IN_PROGRESS for 5 minutes.
        val retry = post(key, body(party, "ALERT-A"))
        assertThat(retry.statusCode).isEqualTo(201)
        assertThat(retry.header("X-Idempotency-Replayed")).isNull()
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `after the Redis record is gone a different body under the same key is still refused by the database`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        create(key, body(party, "ALERT-A"), expect = 201)
        evictRedis(key)

        val reused = post(key, body(party, "ALERT-B"))
        assertThat(reused.statusCode).isEqualTo(409)
        assertThat(reused.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(caseCount(party)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "u-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `after the Redis record is gone the same body under the same key returns the existing case`() {
        val party = UUID.randomUUID()
        val key = "idem-${UUID.randomUUID()}"
        val first = create(key, body(party, "ALERT-A"), expect = 201)
        evictRedis(key)

        val again = post(key, body(party, "ALERT-A"))
        assertThat(again.statusCode).isEqualTo(201)
        assertThat(again.jsonPath().getString("id")).isEqualTo(first)
        assertThat(caseCount(party)).isEqualTo(1)
    }

    private fun evictRedis(key: String) {
        redis.key().del("idempotency:$key").await().indefinitely()
    }

    private fun post(key: String, json: String): Response = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body(json)
    } When {
        post("/api/v1/aml/cases")
    } Extract {
        response()
    }

    private fun body(party: UUID, alertCode: String) =
        """{"partyId":"$party","customerReference":"REF-1","screeningType":"MANUAL_INVESTIGATION",""" +
            """"riskLevel":"MEDIUM","alertCode":"$alertCode"}"""

    private fun create(key: String, json: String, expect: Int): String = Given {
        contentType("application/json")
        header("Idempotency-Key", key)
        body(json)
    } When {
        post("/api/v1/aml/cases")
    } Then {
        statusCode(expect)
    } Extract {
        path("id")
    }

    private fun caseCount(party: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from aml_cases where party_id = ?").use { ps ->
            ps.setObject(1, party)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}
