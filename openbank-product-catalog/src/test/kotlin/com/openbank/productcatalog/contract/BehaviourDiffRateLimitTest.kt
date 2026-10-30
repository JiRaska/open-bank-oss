// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The backtest's admission limit reaches HTTP as a 429 with `Retry-After`, not a 500 and not a queue.
 * With no slots configured every backtest is refused, which proves the mapping without a timing race;
 * `ConcurrencyLimitTest` proves the slot accounting itself.
 */
@QuarkusTest
@TestProfile(BehaviourDiffRateLimitTest.NoSlots::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class BehaviourDiffRateLimitTest {

    class NoSlots : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.catalog.behaviour-diff.max-concurrent" to "0")
    }

    @Test
    fun `a backtest with no free slot is a 429 with Retry-After`() {
        Given {
            contentType("application/json")
            body(
                """{"portfolio":{"accounts":[{"id":"a",""" +
                    """"state":{"currency":"EUR","balance":"1.00","openedOn":"2026-01-01"}}]},""" +
                    """"from":"2026-01-01","to":"2026-01-02"}""",
            )
        } When {
            post("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/behaviour-diff")
        } Then {
            statusCode(429)
            header("Retry-After", notNullValue())
        }
    }
}
