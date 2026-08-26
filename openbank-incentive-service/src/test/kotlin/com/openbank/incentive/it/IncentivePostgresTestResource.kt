// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class IncentivePostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping incentive HTTP IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16.3-alpine"))
            .withUsername("openbank")
            .withPassword("openbank_secret")
            .withDatabaseName("openbank_incentive_it")
        pg.start()
        postgres = pg
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_incentive_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_incentive_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
            "openbank.incentive.code-pepper" to "integration-pepper-with-32-characters-minimum",
        )
    }

    override fun stop() {
        postgres?.stop()
    }
}
