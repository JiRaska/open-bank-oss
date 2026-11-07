// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.it

import com.openbank.libs.testing.containers.DockerRequirement
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.toxiproxy.ToxiproxyContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/** Application connections cross the proxy; observer JDBC bypasses the injected outage. */
class PostgresToxiproxyTestResource : QuarkusTestResourceLifecycleManager {
    private val scopeId = UUID.randomUUID().toString()
    private var network: Network? = null
    private var postgres: PostgreSQLContainer<*>? = null
    private var toxiproxy: ToxiproxyContainer? = null

    override fun start(): Map<String, String> {
        DockerRequirement.require()
        return runCatching { startResources() }.getOrElse { failure ->
            runCatching { stop() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun startResources(): Map<String, String> {
        val sharedNetwork = Network.newNetwork().also { network = it }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withNetwork(sharedNetwork).withNetworkAliases("postgres")
            .withDatabaseName("openbank_ledger_it")
            .withUsername("openbank").withPassword("openbank_secret")
            .also { postgres = it }
        startContainer(pg, "postgres", POSTGRES_IMAGE)
        val proxy = ToxiproxyContainer(DockerImageName.parse(TOXIPROXY_IMAGE))
            .withNetwork(sharedNetwork).also { toxiproxy = it }
        startContainer(proxy, "toxiproxy", TOXIPROXY_IMAGE)
        ToxiproxyClient(proxy.host, proxy.controlPort)
            .createProxy("ledger-postgres", "0.0.0.0:8666", "postgres:5432")
        val address = "${proxy.host}:${proxy.getMappedPort(8666)}/${pg.databaseName}"
        val observerAddress = "${pg.host}:${pg.getMappedPort(5432)}/${pg.databaseName}"
        val observerUrl = "jdbc:postgresql://$observerAddress?connectTimeout=5&socketTimeout=5"
        return InMemoryConnector.switchOutgoingChannelsToInMemory("ledger-events-out") + mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$address",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$address",
            "quarkus.datasource.username" to pg.username,
            "quarkus.datasource.password" to pg.password,
            "quarkus.datasource.reactive.reconnect-attempts" to "0",
            "quarkus.devservices.enabled" to "false",
            "quarkus.scheduler.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            "test.database-outage.direct-jdbc-url" to observerUrl,
            "test.database-outage.proxy-admin-url" to "http://${proxy.host}:${proxy.controlPort}",
        )
    }

    private fun startContainer(container: GenericContainer<*>, resource: String, image: String) {
        container.start()
        TestInfrastructureEvidence.record(resource, image, "started", scopeId, containerId = container.containerId)
    }

    override fun stop() {
        // Attempt every cleanup even if a container or evidence sink fails; retain all failures.
        val failures = listOf(
            runCatching { toxiproxy?.let { stopContainer(it, "toxiproxy", TOXIPROXY_IMAGE) } },
            runCatching { postgres?.let { stopContainer(it, "postgres", POSTGRES_IMAGE) } },
            runCatching { network?.close() },
            runCatching { InMemoryConnector.clear() },
        ).mapNotNull { it.exceptionOrNull() }
        toxiproxy = null
        postgres = null
        network = null
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Database outage test resource cleanup failed", failures.first())
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun stopContainer(container: GenericContainer<*>, resource: String, image: String) {
        val containerId = container.containerId
        container.stop()
        if (containerId != null) {
            TestInfrastructureEvidence.record(resource, image, "stopped", scopeId, containerId = containerId)
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:18.6-alpine"
        const val TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0"
    }
}
