// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The four-eyes playbook publishing journey over the real HTTP surface: an editor drafts a
 * contact-centre playbook version, submits it for review, and an independent approver publishes
 * it — while the endpoints prove the boundaries the service enforces: a draft cannot be
 * published before submission, the maker cannot publish their own version, publishing supersedes
 * the previous published version, retiring the last one leaves the persona unpublished, and a
 * staff operator without comms roles cannot draft at all.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "playbook is authored, reviewed, published, and retired" story, so a wiring regression that
 * deregisters a route (#3371), drops the maker/checker binding at publish, or serves a retired
 * version to agent-assist fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = EDITOR, roles = ["ROLE_COMMS_EDITOR", "ROLE_COMMS_APPROVER"])
class PlaybookPublishingJourneyE2E {

    private val personaKey = "contact-centre"
    private lateinit var versionOneId: String
    private lateinit var versionTwoId: String

    @Test
    @Order(1)
    fun `1 - an editor drafts a playbook version for the contact-centre persona`() {
        val created = Given {
            contentType("application/json")
            body(playbookBody("card lost"))
        } When {
            post("/api/v1/personas/$personaKey/playbook-versions")
        } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
            body("version", equalTo(1))
        } Extract { path<String>("id") }
        versionOneId = created
    }

    @Test
    @Order(2)
    @TestSecurity(user = CHECKER, roles = ["ROLE_COMMS_APPROVER"])
    fun `2 - a checker cannot publish a draft that was never submitted`() {
        When { post("/api/v1/personas/playbook-versions/$versionOneId/publish") }
            .Then {
                statusCode(409)
                body("error", containsString("not in review"))
            }
    }

    @Test
    @Order(3)
    fun `3 - the maker submits the version for review`() {
        When { post("/api/v1/personas/playbook-versions/$versionOneId/submit") }
            .Then {
                statusCode(200)
                body("status", equalTo("IN_REVIEW"))
            }
    }

    @Test
    @Order(4)
    fun `4 - the maker cannot publish their own version`() {
        When { post("/api/v1/personas/playbook-versions/$versionOneId/publish") }
            .Then {
                statusCode(409)
                body("error", containsString("maker cannot publish their own playbook version"))
            }
    }

    @Test
    @Order(5)
    @TestSecurity(user = CHECKER, roles = ["ROLE_COMMS_APPROVER"])
    fun `5 - an independent checker publishes the version and is recorded as decider`() {
        When { post("/api/v1/personas/playbook-versions/$versionOneId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
                body("decidedBy", equalTo(CHECKER))
            }
    }

    @Test
    @Order(6)
    @TestSecurity(user = READER, roles = ["ROLE_API", "ROLE_OPERATOR"])
    fun `6 - the published playbook is served to a reader with the reader role`() {
        Given { this }.When { get("/api/v1/personas/$personaKey/playbook/published") }
            .Then {
                statusCode(200)
                body("playbookVersion", equalTo(1))
            }
    }

    @Test
    @Order(7)
    @TestSecurity(user = READER, roles = ["ROLE_API", "ROLE_OPERATOR"])
    fun `7 - agent-assist search finds the published approved answer`() {
        Given { queryParam("q", "card") }
            .When { get("/api/v1/personas/$personaKey/playbook/search") }
            .Then {
                statusCode(200)
                body("[0].answer.answer", containsString("Block the card"))
            }
    }

    @Test
    @Order(8)
    fun `8 - the maker drafts and submits a second version`() {
        val second = Given {
            contentType("application/json")
            body(playbookBody("payment overdue"))
        } When {
            post("/api/v1/personas/$personaKey/playbook-versions")
        } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
            body("version", equalTo(2))
        } Extract { path<String>("id") }
        versionTwoId = second

        When { post("/api/v1/personas/playbook-versions/$versionTwoId/submit") }
            .Then {
                statusCode(200)
                body("status", equalTo("IN_REVIEW"))
            }
    }

    @Test
    @Order(9)
    @TestSecurity(user = CHECKER, roles = ["ROLE_COMMS_APPROVER"])
    fun `9 - an independent checker publishes the second version`() {
        When { post("/api/v1/personas/playbook-versions/$versionTwoId/publish") }
            .Then {
                statusCode(200)
                body("status", equalTo("PUBLISHED"))
                body("decidedBy", equalTo(CHECKER))
            }
    }

    @Test
    @Order(10)
    @TestSecurity(user = READER, roles = ["ROLE_API", "ROLE_OPERATOR"])
    fun `10 - publishing the second version superseded the first in the published view`() {
        Given { this }.When { get("/api/v1/personas/$personaKey/playbook/published") }
            .Then {
                statusCode(200)
                body("playbookVersion", equalTo(2))
            }
    }

    @Test
    @Order(11)
    @TestSecurity(user = CHECKER, roles = ["ROLE_COMMS_APPROVER"])
    fun `11 - retiring the last published version refuses a second retire`() {
        When { post("/api/v1/personas/playbook-versions/$versionTwoId/retire") }
            .Then {
                statusCode(200)
                body("status", equalTo("RETIRED"))
            }

        When { post("/api/v1/personas/playbook-versions/$versionTwoId/retire") }
            .Then { statusCode(409) }
    }

    @Test
    @Order(12)
    @TestSecurity(user = READER, roles = ["ROLE_API", "ROLE_OPERATOR"])
    fun `12 - a persona whose last version retired has no published playbook`() {
        Given { this }.When { get("/api/v1/personas/$personaKey/playbook/published") }
            .Then { statusCode(404) }
    }

    @Test
    @Order(13)
    @TestSecurity(user = "ops-staff@openbank.test", roles = ["ROLE_OPERATOR"])
    fun `13 - a staff operator without comms roles cannot draft a playbook version`() {
        Given {
            contentType("application/json")
            body(playbookBody("unauthorised"))
        } When {
            post("/api/v1/personas/$personaKey/playbook-versions")
        } Then {
            statusCode(403)
        }
    }

    private fun playbookBody(situation: String) = """
        {"callScript":[
            {"order":1,"kind":"GREETING","text":"Dobry den, jak vam mohu pomoci?","mandatory":false},
            {"order":2,"kind":"MANDATORY_SENTENCE","text":"Hovor je nahravan.","mandatory":true}
        ],
        "approvedAnswers":[
            {"situation":"$situation","answer":"Block the card in the app under Cards Block."}
        ]}
    """.trimIndent()
}

private const val EDITOR = "editor-e2e@openbank.test"
private const val CHECKER = "checker-e2e@openbank.test"
private const val READER = "agent-assist@openbank.test"
