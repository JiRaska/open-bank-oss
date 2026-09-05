// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated Valkey (Redis) per test JVM, for the four-eyes `ApprovalStore` (ADR-0155,
 * ADR-0176 D5). Separate from [PostgresTestResource] — most tests need only Postgres, and
 * `@QuarkusTestResource` composes cleanly, so a Redis-touching test adds this alongside it
 * rather than folding both into one resource. Mirrors
 * `openbank-lending-service/.../it/PostgresRedisTestResource.kt`'s Redis half — including,
 * since issue #1395, its Docker-availability guard, which the first cut of this file only
 * copied the container-start mechanics of, not the check itself.
 */
class RedisTestResource : QuarkusTestResourceLifecycleManager {

    private companion object {
        const val VALKEY_IMAGE = "valkey/valkey:8-alpine"
    }

    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val rd = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(6379)
        // Assign the field BEFORE start() (issue #1395), not after: if start() throws after the
        // container process was actually created on the Docker daemon (a wait-strategy timeout,
        // as opposed to Docker being wholly unavailable), the field must still be reachable so
        // stop() can tear it down. Assigning only on successful return left stop()'s `redis?.stop()`
        // a silent no-op for exactly the partial-start case, leaking the container on a shared CI
        // host with no external reaper other than Ryuk to eventually catch it.
        redis = rd
        rd.start()
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")
        return mapOf(
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getFirstMappedPort()}",
        )
    }

    override fun stop() {
        redis?.let {
            it.stop()
            TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
        }
    }
}
