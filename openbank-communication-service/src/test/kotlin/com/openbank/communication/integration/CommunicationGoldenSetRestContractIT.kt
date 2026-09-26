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

/** Mirrors `CommunicationPlaybookRestContractIT`'s shape for the golden-set CRUD surface. */
@QuarkusTest
@QuarkusTestResource(CommunicationPostgresTestResource::class)
class CommunicationGoldenSetRestContractIT {

    @Test
    @TestSecurity(user = "editor-g@openbank.test", roles = ["ROLE_COMMS_EDITOR"])
    fun `create, list and delete a golden-set entry`() {
        val id = Given {
            contentType("application/json")
            body(
                """{"question":"Kolik mam na uctu?",
                    "expectedLanguage":"cs",
                    "expectNoFigureFromMemory":true,
                    "expectedToneMarkers":["brief"],
                    "requiredComplianceSentence":"Hovor je nahravan."}""",
            )
        } When {
            post("/api/v1/personas/collections/golden-set")
        } Then {
            statusCode(201)
            body("expectedLanguage", equalTo("cs"))
        } Extract { path<String>("id") }

        Given { this }.When {
            get("/api/v1/personas/collections/golden-set")
        } Then {
            statusCode(200)
            body("", hasSize<Any>(1))
        }

        Given { this }.When {
            delete("/api/v1/personas/golden-set/$id")
        } Then {
            statusCode(204)
        }

        Given { this }.When {
            get("/api/v1/personas/collections/golden-set")
        } Then {
            statusCode(200)
            body("", hasSize<Any>(0))
        }
    }

    @Test
    @TestSecurity(user = "editor-g2@openbank.test", roles = ["ROLE_COMMS_EDITOR"])
    fun `an unknown persona 404s on create`() {
        Given {
            contentType("application/json")
            body("""{"question":"x","expectedLanguage":"cs"}""")
        } When {
            post("/api/v1/personas/no-such-persona/golden-set")
        } Then {
            statusCode(404)
        }
    }
}
