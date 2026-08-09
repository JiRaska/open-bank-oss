// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.junit.jupiter.api.Test

/**
 * Falsification test for the non-nullable `@QueryParam` defect class (issue #3624).
 *
 * `GET /api/v1/tpp-registry/check` declared `tppId: String` and `role: String`. JAX-RS injects
 * **null** for an absent query parameter and Kotlin's null-safety is compile-time only, so the
 * declaration could not keep its promise: on a `suspend fun` no `Intrinsics.checkNotNullParameter`
 * is emitted at all, the null flowed into the body and died at `role.uppercase()` — a **500**.
 *
 * This test drives REAL HTTP, which is the only layer that can see it: a unit test calling
 * `TppRegistryResource.checkAuthorization(...)` directly supplies the argument itself and is
 * compile-time checked, so it can never reproduce the absent-parameter case.
 *
 * Measured on this branch: with the parameters declared non-nullable both cases answered **500**;
 * with them nullable + `requireNotNull` they answer **400** via libs-runtime's
 * `IllegalArgumentException` mapping (no service-local exception mapper — #526).
 */
@QuarkusTest
@QuarkusTestResource(TppRegistryMissingQueryParamIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.tppregistry.it.PostgresRedisTestResource::class)
@TestSecurity(user = "param-probe", roles = ["ROLE_API"])
class TppRegistryMissingQueryParamIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("tpp-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `an absent tppId answers 400, not 500`() {
        Given { this } When {
            get("/api/v1/tpp-registry/check?role=AISP")
        } Then { statusCode(400) }
    }

    @Test
    fun `an absent role answers 400, not 500`() {
        Given { this } When {
            get("/api/v1/tpp-registry/check?tppId=CZ-CNB-12345")
        } Then { statusCode(400) }
    }

    @Test
    fun `both parameters absent answers 400, not 500`() {
        Given { this } When { get("/api/v1/tpp-registry/check") } Then { statusCode(400) }
    }
}
