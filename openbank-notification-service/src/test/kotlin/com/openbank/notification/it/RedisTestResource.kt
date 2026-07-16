// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated Valkey (Redis) per test JVM, for the four-eyes `ApprovalStore` (ADR-0155,
 * ADR-0176 D5). Separate from [PostgresTestResource] — most tests need only Postgres, and
 * `@QuarkusTestResource` composes cleanly, so a Redis-touching test adds this alongside it
 * rather than folding both into one resource. Mirrors
 * `openbank-lending-service/.../it/PostgresRedisTestResource.kt`'s Redis half.
 */
class RedisTestResource : QuarkusTestResourceLifecycleManager {

    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val rd = GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379)
        rd.start()
        redis = rd
        return mapOf(
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
        )
    }

    override fun stop() {
        redis?.stop()
    }
}
