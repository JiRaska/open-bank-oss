// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** Mirrors `openbank-referral-service`'s `ReferralPostgresTestResource` exactly. */
class CommunicationPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping communication HTTP IT")
        }
        val pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_communication_it")
        pg.start()
        postgres = pg
        TestInfrastructureEvidence.record("postgres", POSTGRES_IMAGE, "started")
        val host = pg.host
        val port = pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        return mapOf(
            "quarkus.datasource.reactive.url" to "vertx-reactive:postgresql://$host:$port/openbank_communication_it",
            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://$host:$port/openbank_communication_it",
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
