// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.rest

import com.openbank.devops.application.port.out.DeliveryEvidencePort
import com.openbank.devops.domain.model.DeliveryEvidence
import com.openbank.devops.domain.model.DeliveryEvidenceSource
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DeliveryPullRequest
import com.openbank.devops.domain.model.DeliveryWorkflowRun
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DeliveryOverviewResourceIT.Profile::class)
class DeliveryOverviewResourceIT {
    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.devops.delivery-shadow-enabled" to "true",
            "openbank.devops.delivery-shadow-cron" to "*/1 * * * * ?",
            "openbank.devops.github-owner" to "example",
            "openbank.devops.github-repo" to "repo",
        )
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FixtureEvidence::class.java)
    }

    @Alternative
    @ApplicationScoped
    class FixtureEvidence : DeliveryEvidencePort {
        override suspend fun collect(windowStart: Instant, windowEnd: Instant): DeliveryEvidence = DeliveryEvidence(
            "example/repo",
            DeliveryEvidenceSource(
                DeliveryEvidenceState.COMPLETE,
                listOf(
                    DeliveryWorkflowRun(
                        1,
                        ".github/workflows/auto-deploy.yml",
                        "completed",
                        "success",
                        windowStart,
                        windowStart,
                        1,
                    ),
                ),
            ),
            DeliveryEvidenceSource(
                DeliveryEvidenceState.COMPLETE,
                listOf(
                    DeliveryPullRequest(
                        1,
                        windowStart,
                        windowStart,
                        windowStart,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `real route rejects anonymous requests`() {
        given().`when`().get("/api/v1/devops/delivery/overview").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "other", roles = ["ROLE_OTHER"])
    fun `real route rejects identities without a read role`() {
        given().`when`().get("/api/v1/devops/delivery/overview").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `real route exposes read-only shadow evidence without claiming deployment verification`() {
        given().`when`().get("/api/v1/devops/delivery/overview").then().statusCode(200)
            .body("mode", equalTo("SHADOW"))
            .body("workflowEvidence", equalTo("COMPLETE"))
            .body("mergedPrsPerDay", equalTo(1))
            .body("deploymentVerification", equalTo("NOT_COLLECTED"))
            .body("verifiedDeploymentsPerDay", nullValue())
            .body("workflows[1].successful", equalTo(1))
    }
}
