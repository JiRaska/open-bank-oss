// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.integration

import com.openbank.communication.it.CommunicationPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

/** Mirrors `CommunicationStyleRestContractIT`'s shape for the playbook surface. */
@QuarkusTest
@QuarkusTestResource(CommunicationPostgresTestResource::class)
class CommunicationPlaybookRestContractIT {

    @Test
    // Both roles for the same reason as the style IT's equivalent test: publish() is
    // @RolesAllowed(ROLE_COMMS_APPROVER, ROLE_ADMIN); without it this 403s before the domain
    // check runs at all (found the hard way building the style IT — see its own comment).
    @TestSecurity(user = "editor-p@openbank.test", roles = ["ROLE_COMMS_EDITOR", "ROLE_COMMS_APPROVER"])
    fun `draft submit and self-publish is refused, then a different checker publishes and search finds it`() {
        val versionId = Given {
            contentType("application/json")
            body(
                """{"callScript":[
                        {"order":1,"kind":"GREETING","text":"Dobry den, jak vam mohu pomoci?","mandatory":false},
                        {"order":2,"kind":"MANDATORY_SENTENCE","text":"Hovor je nahravan.","mandatory":true}
                    ],
                    "approvedAnswers":[
                        {"situation":"card lost","answer":"Block the card in the app under Cards Block."}
                    ]}""",
            )
        } When {
            post("/api/v1/personas/collections/playbook-versions")
        } Then {
            statusCode(201)
            body("status", equalTo("DRAFT"))
        } Extract { path<String>("id") }

        Given { this }.When {
            post("/api/v1/personas/playbook-versions/$versionId/submit")
        } Then {
            statusCode(200)
            body("status", equalTo("IN_REVIEW"))
        }

        Given { this }.When {
            post("/api/v1/personas/playbook-versions/$versionId/publish")
        } Then {
            // Same principal (editor-p) drafted it — maker!=checker refuses.
            statusCode(409)
        }
    }

    @Test
    @TestSecurity(user = "checker-p@openbank.test", roles = ["ROLE_COMMS_APPROVER", "ROLE_API"])
    fun `an unpublished persona's playbook search returns an empty list, not an error`() {
        Given { this }.When {
            get("/api/v1/personas/back-office-written/playbook/search?q=anything")
        } Then {
            statusCode(200)
            body("", hasSize<Any>(0))
        }
    }
}
