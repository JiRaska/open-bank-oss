// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.tax.integration.CompanyLedgerStub
import com.openbank.tax.integration.StatutoryReturnMessagingTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The ČNB supervisory filing journey over the real HTTP surface: a filer assembles PSP 10-12 PS
 * from the company's own frozen books, a second staff member approves (four-eyes, attesting the
 * content hash), the filer records the regulator reference, and the regulator's answer closes the
 * revision — while the boundaries the aggregate enforces hold at every step: an unfrozen period is
 * refused by name rather than zero-filled, a viewer cannot mutate, a machine token may not attest,
 * and the assembler may not approve their own figures.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "return is assembled, attested, filed and answered" story, so a wiring regression that
 * deregisters a route (#3371), lets a service account attest, or drops the maker/checker binding
 * fails here even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_e2e")],
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CompanyLedgerStub::class, restrictToAnnotatedClass = true)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "filer", roles = ["ROLE_OPERATOR"])
@TestProfile(StatutoryReturnFilingJourneyE2E.E2eProfile::class)
class StatutoryReturnFilingJourneyE2E {

    class E2eProfile : QuarkusTestProfile

    private val path = "/api/v1/statutory-returns"
    private lateinit var returnId: String

    @Test
    @Order(1)
    fun `1 - the filer assembles PSP 10-12 PS from the frozen books and it reads back`() {
        val body =
            """
            {"catalogueId":"cz-pension-cnb","returnCode":"PSP10-12-PS","entityId":"company","period":"2025-03"}
            """.trimIndent()
        val created = Given {
            contentType("application/json")
            body(body)
        } When {
            post("$path/assemble")
        } Then {
            statusCode(200)
            body("status", equalTo("ASSEMBLED"))
            body("assembledBy", equalTo("filer"))
            body("approvedBy", equalTo(null))
            body("contentHash", notNullValue())
        } Extract { path<String>("id") }
        returnId = created

        val assets: Number = Given { contentType("application/json") }
            .When { get("$path/$returnId") }
            .Then {
                statusCode(200)
                body("id", equalTo(returnId))
                body("status", equalTo("ASSEMBLED"))
            }
            .Extract { path("datapoints.total_assets") }
        assertThat(java.math.BigDecimal(assets.toString())).isEqualByComparingTo("50820000")

        Given { contentType("application/json") }
            .When { get("$path/00000000-0000-0000-0000-000000000099") }
            .Then { statusCode(404) }
    }

    @Test
    @Order(2)
    fun `2 - a month with no frozen evidence is refused by name, never zero-filled`() {
        Given {
            contentType("application/json")
            body(
                """
                {"catalogueId":"cz-pension-cnb","returnCode":"PSP10-12-PS","entityId":"company","period":"2025-04"}
                """.trimIndent(),
            )
        } When {
            post("$path/assemble")
        } Then {
            statusCode(503)
            body("error", containsString("409"))
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `3 - a viewer reads the catalogue and capability but cannot touch the lifecycle`() {
        Given { this } When { get("$path/catalogues") } Then {
            statusCode(200)
            body("[0].id", equalTo("cz-pension-cnb"))
        }
        Given { this } When { get("$path/capability") } Then { statusCode(200) }
        Given {
            contentType("application/json")
            body(
                """
                {"catalogueId":"cz-pension-cnb","returnCode":"PSP10-12-PS","entityId":"company","period":"2025-01"}
                """.trimIndent(),
            )
        } When { post("$path/assemble") } Then { statusCode(403) }
        Given { contentType("application/json") } When { post("$path/$returnId/approve") } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `4 - a service account may not attest a regulatory return whatever roles it holds`() {
        Given {
            contentType("application/json")
            body(
                """
                {"catalogueId":"cz-pension-cnb","returnCode":"PSP10-12-PS","entityId":"company","period":"2025-01"}
                """.trimIndent(),
            )
        } When {
            post("$path/assemble")
        } Then {
            statusCode(403)
            body("error", containsString("service account"))
        }
        Given { contentType("application/json") } When { post("$path/$returnId/approve") } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(5)
    fun `5 - the assembler cannot approve their own figures`() {
        Given { contentType("application/json") } When { post("$path/$returnId/approve") } Then {
            statusCode(409)
            body("error", containsString("Four-eyes"))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `6 - an independent checker attests and the filer records the submission reference`() {
        Given { contentType("application/json") } When { post("$path/$returnId/approve") } Then {
            statusCode(200)
            body("status", equalTo("APPROVED"))
            body("approvedBy", equalTo("checker"))
            body("attestedHash", notNullValue())
        }

        Given {
            contentType("application/json")
            body("""{"reference":"CNB-SDAT-2025-0312"}""")
        } When {
            post("$path/$returnId/submitted")
        } Then {
            statusCode(200)
            body("status", equalTo("SUBMITTED"))
            body("submissionReference", equalTo("CNB-SDAT-2025-0312"))
        }

        Given { contentType("application/json") } When { post("$path/$returnId/approve") } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(7)
    fun `7 - the regulator answer closes the revision and a correction is a new revision`() {
        Given {
            contentType("application/json")
            body("""{"accepted":true,"reference":"CNB-ACK-2025-4410"}""")
        } When {
            post("$path/$returnId/acknowledgement")
        } Then {
            statusCode(200)
            body("status", equalTo("ACKNOWLEDGED"))
            body("acknowledgedBy", equalTo("filer"))
        }

        Given {
            contentType("application/json")
            body("""{"accepted":true,"reference":"CNB-ACK-OTHER"}""")
        } When {
            post("$path/$returnId/acknowledgement")
        } Then { statusCode(409) }

        // The regulator holds the revision (ACKNOWLEDGED is closed), so a correction is a NEW
        // revision of the same return — never an edit of the attested one.
        val correction = Given {
            contentType("application/json")
            body(
                """
                {"catalogueId":"cz-pension-cnb","returnCode":"PSP10-12-PS","entityId":"company","period":"2025-03"}
                """.trimIndent(),
            )
        } When {
            post("$path/assemble")
        } Then {
            statusCode(200)
            body("status", equalTo("ASSEMBLED"))
            body("revision", equalTo(2))
        } Extract { path<String>("id") }

        // ...and the correction is a row of its own, not an edit of the filed revision.
        Given { contentType("application/json") }
            .When { get("$path/$returnId") }
            .Then {
                statusCode(200)
                body("status", equalTo("ACKNOWLEDGED"))
                body("revision", equalTo(1))
            }
        Given { contentType("application/json") }
            .When { get("$path/$correction") }
            .Then {
                statusCode(200)
                body("revision", equalTo(2))
                body("status", equalTo("ASSEMBLED"))
            }
    }
}
