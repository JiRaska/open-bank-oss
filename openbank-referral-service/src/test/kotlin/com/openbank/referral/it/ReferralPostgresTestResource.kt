package com.openbank.referral.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class ReferralPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null
    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping referral HTTP IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_referral_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_referral_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_referral_it",
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            "quarkus.devservices.enabled" to "false",
        )
    }
    override fun stop() {
        postgres?.let {
            it.stop()
            TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "stopped")
        }
    }

    private companion object {
        const val POSTGRES_IMAGE = "postgres:16.3-alpine"
    }
}
