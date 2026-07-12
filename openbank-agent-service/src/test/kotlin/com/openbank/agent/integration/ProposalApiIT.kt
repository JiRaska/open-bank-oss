// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.application.ProposalService
import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

/**
 * First integration test for agent-service (ADR-0011 L2): boots the full Quarkus app against an
 * isolated PostgreSQL (Flyway migrates, Agroal datasource, CDI wiring all exercised) and drives the
 * proposal review surface end-to-end through HTTP. The proposal store is empty on a fresh container,
 * so listing pending proposals returns an empty array — a deterministic boot+query smoke that also
 * locks the RBAC boundary on the maker-checker endpoint (ADR-0031).
 */
@QuarkusTest
// restrictToAnnotatedClass=true (explicit, not just relying on the framework default): without it,
// PostgresTestResource's injected `agent.model.openai.api-key=test-not-used` placeholder can leak
// into other @QuarkusTest classes sharing the same test JVM whose own @TestProfile expects an empty
// key (e.g. OpenAiCompatibleModelProviderBootTest) — surfaced by the Quarkus 3.37.2 BOM bump, which
// let this un-isolated resource's config win over a sibling class's TestProfile override.
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class ProposalApiIT {

    @Inject
    lateinit var service: ProposalService

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `GET proposals returns an empty list on a fresh store`() {
        given()
            .`when`().get("/api/v1/proposals?state=pending")
            .then()
            .statusCode(200)
            .body("size()", `is`(0))
    }

    @Test
    fun `GET proposals without an authenticated role is rejected`() {
        given()
            .`when`().get("/api/v1/proposals?state=pending")
            .then()
            .statusCode(anyOf(equalTo(401), equalTo(403)))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `GET proposals with agentId only returns that agent's own proposals`() {
        service.create(
            title = "finops proposal",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "finops-agent-itest",
            modelId = null,
            correlationId = null,
        )
        service.create(
            title = "devops proposal",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "devops-agent-itest",
            modelId = null,
            correlationId = null,
        )

        given()
            .`when`().get("/api/v1/proposals?state=all&agentId=finops-agent-itest")
            .then()
            .statusCode(200)
            .body("size()", `is`(1))
            .body("[0].proposedBy", `is`("finops-agent-itest"))
            .body("[0].title", `is`("finops proposal"))
    }
}
