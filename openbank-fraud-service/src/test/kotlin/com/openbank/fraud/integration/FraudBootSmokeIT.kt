// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * Boot smoke guarding the "released-but-never-booted" defect class (#2469). fraud-service now wires a
 * Redis-backed online feature store (ADR-0140) via `FeatureStoreConfig`'s `@Produces` — a CDI/ArC or
 * `quarkus.redis.hosts` mis-wiring only surfaces at first boot, not in unit tests. A green readiness
 * probe proves Postgres + Flyway + Redis + the new feature-store/ML CDI graph survive a real Quarkus
 * boot. The `transaction-signal` Kafka channel is disabled in `%test`, so no broker is needed.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FraudBootSmokeIT {

    @Test
    fun `the app boots with the feature-store and ML wiring and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }
}
