// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.persistence

import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.infrastructure.rest.DeliveryOverviewResourceIT
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DeliveryOverviewResourceIT.Profile::class)
class CpuAdvisoryPersistenceIT {
    @Path("/test/cpu-advisories")
    class Fixture(private val repository: FindingRepository) {
        @POST
        @RolesAllowed("ROLE_ADMIN")
        suspend fun save(finding: DevOpsFinding): DevOpsFinding = repository.saveAdvisoryIfAbsent(finding)
    }

    @Test
    @TestSecurity(user = "fixture-admin", roles = ["ROLE_ADMIN"])
    fun `concurrent deliveries create one durable row and never overwrite human disposition`() {
        val id = UUID.randomUUID().toString()
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val finding = DevOpsFinding(
            id, DetectorId.D1_CI_PIPELINE_HEALTH, FindingSeverity.WARNING, at, "CPU measurement request",
            BigDecimal.TEN, BigDecimal.ZERO, "ci-gate:lint", rootCause = "exact fixture provenance",
            proposedRemediation = "paired whole-gate benchmark required", status = FindingStatus.DIAGNOSED,
            diagnosedAt = at,
        )
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                pool.submit(
                    Callable {
                        given().contentType("application/json").body(finding)
                            .post("/test/cpu-advisories").then().statusCode(200)
                    },
                )
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement("SELECT count(*) FROM findings WHERE id=?").use {
                it.setObject(1, UUID.fromString(id))
                it.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getInt(1)).isEqualTo(1)
                }
            }
            connection.prepareStatement(
                "UPDATE findings SET status='APPROVED', root_cause='human decision' WHERE id=?",
            ).use {
                it.setObject(1, UUID.fromString(id))
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            val repeated = given().contentType("application/json").body(finding.copy(rootCause = "must not overwrite"))
                .post("/test/cpu-advisories").then().statusCode(200).extract().`as`(DevOpsFinding::class.java)
            assertThat(repeated.status).isEqualTo(FindingStatus.APPROVED)
            assertThat(repeated.rootCause).isEqualTo("human decision")
            assertThat(repeated.proposedRemediation).isEqualTo(finding.proposedRemediation)
        }
    }
}
