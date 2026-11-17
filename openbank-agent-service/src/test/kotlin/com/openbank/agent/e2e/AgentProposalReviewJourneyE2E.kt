// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.e2e

import com.openbank.agent.application.ProposalService
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The maker/checker proposal journey over the real HTTP surface (ADR-0031): an agent files a
 * proposal, it lists for review, the proposing identity cannot approve its own suggestion, and a
 * second operator decides while the authenticated principal — never the request body — is recorded
 * as the decider.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "agent proposes, a human reviews, the decision is auditable" story, so a wiring regression that
 * deregisters a route (#3371), trusts a body-supplied decider (#3051), or drops the
 * separation-of-duties guard fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    restrictToAnnotatedClass = true,
    initArgs = [ResourceArg(name = "db", value = "openbank_agent_e2e")],
)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "checker@openbank.test", roles = ["ROLE_OPERATOR"])
class AgentProposalReviewJourneyE2E {

    @jakarta.inject.Inject
    lateinit var service: ProposalService

    private val path = "/api/v1/proposals"
    private val makerAgent = "maker-agent-e2e-${UUID.randomUUID()}"
    private lateinit var proposalId: String
    private lateinit var rejectId: String

    private fun seed(title: String, proposedBy: String = makerAgent): String {
        val proposal = service.create(
            title = title,
            rationale = "rationale",
            suggestedAction = "apply the change",
            proposedBy = proposedBy,
            modelId = null,
            correlationId = null,
        )
        return proposal.id.toString()
    }

    @Test
    @Order(1)
    fun `1 - a filed agent proposal lists for review and filters by its agent id`() {
        proposalId = seed("rotate the finops credentials")

        Given { this } When { get("$path?state=pending") } Then {
            statusCode(200)
            body("id", hasItem(proposalId))
        }

        Given { this } When { get("$path?state=pending&agentId=$makerAgent") } Then {
            statusCode(200)
            body("size()", equalTo(1))
            body("[0].proposedBy", equalTo(makerAgent))
            body("[0].state", equalTo("PROPOSED"))
            body("[0].decidedBy", equalTo(null))
        }

        Given { this } When { get("$path?state=pending&agentId=someone-else") } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "nobody@openbank.test")
    fun `2 - the review surface rejects a caller without the review role`() {
        Given { this } When { get("$path?state=pending") } Then {
            statusCode(anyOf(equalTo(401), equalTo(403)))
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `3 - the proposing identity cannot decide its own suggestion`() {
        val selfProposal = seed("self-approval guard proposal", proposedBy = "operator")

        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"self approval"}""")
        } When {
            post("$path/$selfProposal/decision")
        } Then { statusCode(409) }
    }

    @Test
    @Order(4)
    fun `4 - a checker decides and the token principal wins over any body-supplied decider`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"decidedBy":"spoofed-name","reason":"verified out of band"}""")
        } When {
            post("$path/$proposalId/decision")
        } Then {
            statusCode(200)
            body("state", equalTo("APPROVED"))
            body("decidedBy", equalTo("checker@openbank.test"))
            body("decisionReason", equalTo("verified out of band"))
        }

        Given { this } When { get("$path?state=all&agentId=$makerAgent") } Then {
            statusCode(200)
            body("size()", equalTo(1))
            body("[0].decidedBy", equalTo("checker@openbank.test"))
        }
    }

    @Test
    @Order(5)
    fun `5 - a decided proposal refuses a second decision`() {
        Given {
            contentType("application/json")
            body("""{"approve":false,"reason":"changed my mind"}""")
        } When {
            post("$path/$proposalId/decision")
        } Then { statusCode(409) }
    }

    @Test
    @Order(6)
    fun `6 - a reject decision closes a proposal with its reason`() {
        rejectId = seed("retire the unused devops runner")

        Given {
            contentType("application/json")
            body("""{"approve":false,"reason":"runner still serves the nightly batch"}""")
        } When {
            post("$path/$rejectId/decision")
        } Then {
            statusCode(200)
            body("state", equalTo("REJECTED"))
            body("decidedBy", equalTo("checker@openbank.test"))
            body("decisionReason", equalTo("runner still serves the nightly batch"))
        }

        Given { this } When { get("$path?state=pending&agentId=$makerAgent") } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
    }
}
