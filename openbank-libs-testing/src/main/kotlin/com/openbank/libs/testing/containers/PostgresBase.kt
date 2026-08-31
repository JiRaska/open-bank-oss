// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.containers

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared PostgreSQL provisioning for the `Postgres*TestResource` family below (issue #467,
 * CI infra sweep #578). Every service independently copy-pasted this same container setup
 * (confirmed: 36 near-identical `Postgres*TestResource.kt` files fleet-wide, differing only
 * in database name, doc comment, and whether a companion Redis/Redpanda container was added).
 *
 * Database name comes from `initArgs["db"]` (`@QuarkusTestResource(value = ...,
 * initArgs = [ResourceArg(name = "db", value = "openbank_<service>_it")])`), defaulting to
 * `openbank_it` if omitted — every consumer already used a service-specific name, so callers
 * should always set it explicitly to avoid cross-service collisions if tests ever run in a
 * shared Postgres instance.
 */
abstract class PostgresBase(
    // Quarkus reprovisions a fresh manager instance; this scope must therefore identify the
    // logical shared-resource family for the test JVM, not the individual object instance.
    protected val resourceScopeId: String,
) : QuarkusTestResourceLifecycleManager {

    private var postgres: PostgreSQLContainer<*>? = null
    private var dbName: String = "openbank_it"

    override fun init(initArgs: Map<String, String>) {
        initArgs["db"]?.let { dbName = it }
    }

    protected fun startPostgres(): PostgreSQLContainer<*> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName(dbName)
        pg.start()
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started", resourceScopeId)
        postgres = pg
        return pg
    }

    protected fun postgresConfig(pg: PostgreSQLContainer<*>): Map<String, String> {
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/$dbName",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/$dbName",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        postgres?.stop()
        if (postgres != null) TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped", resourceScopeId)
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }
}
