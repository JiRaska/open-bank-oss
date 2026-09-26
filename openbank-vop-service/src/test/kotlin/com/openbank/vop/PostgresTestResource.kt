// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Spins up the two backing stores a real boot needs and points the app at them.
 *
 * **Postgres** — so the boot exercises Flyway + Hibernate + the JDBC driver against a live DB: the
 * "released but never booted" defect class (missing runtime driver, duplicate config key, broken
 * migration).
 *
 * **Valkey** — not optional, and worth understanding rather than stubbing out. `quarkus-redis-client`
 * registers a *readiness* health check, so with no Valkey the service boots but reports 503 and
 * Kubernetes takes it out of the Service. That is the fleet norm (customer-edge, which also uses
 * Valkey, does not disable the check either) and the end behaviour stays fail-open: no VoP endpoint
 * → the caller's circuit breaker trips → `no_data` → the payment proceeds with a warning, exactly as
 * a 429 from the rate limiter would (ADR-0171 §3). Booting against a real Valkey keeps this test
 * honest about what production actually requires, instead of hiding the dependency behind a disabled
 * health check.
 *
 * Same image as the platform runs (`gitops/components/payments/redis.yaml`).
 */
class PostgresTestResource : QuarkusTestResourceLifecycleManager {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var valkey: GenericContainer<*>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("openbank_vop")
            .withUsername("openbank")
            .withPassword("openbank")
        postgres.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")

        valkey = GenericContainer(VALKEY_IMAGE).withExposedPorts(VALKEY_PORT)
        valkey.start()
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")

        // reactive URL for Panache (vertx-pg-client) + JDBC URL for Flyway.
        val reactiveUrl = "postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"
        return mapOf(
            "quarkus.datasource.reactive.url" to reactiveUrl,
            "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
            "quarkus.redis.hosts" to "redis://${valkey.host}:${valkey.getMappedPort(VALKEY_PORT)}",
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) {
            postgres.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
        if (::valkey.isInitialized) {
            valkey.stop()
            TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
        }
    }

    private companion object {
        const val VALKEY_PORT = 6379
        const val POSTGRES_IMAGE = "postgres:18-alpine"
        const val VALKEY_IMAGE = "docker.io/valkey/valkey:8-alpine"
    }
}
