// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PartyApiIT {

    companion object {
        private var createdPartyId: String? = null
        private val uniqueEmail = "test.${UUID.randomUUID()}@openbank.test"
    }

    @Test
    @Order(1)
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-party-service")
    }

    @Test
    @Order(2)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET parties returns paginated list`() {
        Given {
            queryParam("page", 0)
            queryParam("size", 20)
        } When {
            get("/api/v1/parties")
        } Then {
            statusCode(200)
            body("items", notNullValue())
            body("total", notNullValue())
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST parties creates individual party and returns 201`() {
        val payload = """
            {
              "partyType": "INDIVIDUAL",
              "legalName": "Jan Novak",
              "tradingName": null,
              "email": "$uniqueEmail",
              "phone": "+420777111222",
              "dateOfBirth": "1985-06-15",
              "nationality": "CZE"
            }
        """.trimIndent()

        val response = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("legalName", equalTo("Jan Novak"))
            body("partyType", equalTo("INDIVIDUAL"))
            body("kycStatus", equalTo("NOT_STARTED"))
        }

        createdPartyId = response.extract().body().jsonPath().getString("id")
        assertThat(createdPartyId).isNotNull
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET party by id returns created party`() {
        val id = createdPartyId ?: return
        Given { this } When {
            get("/api/v1/parties/$id")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("email", equalTo(uniqueEmail))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `PATCH party updates contact details`() {
        val id = createdPartyId ?: return
        Given {
            contentType("application/json")
            body("""{"phone": "+420999888777"}""")
        } When {
            patch("/api/v1/parties/$id")
        } Then {
            statusCode(200)
            body("phone", equalTo("+420999888777"))
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR", "ROLE_KYC"])
    fun `POST party document adds document`() {
        val id = createdPartyId ?: return
        val payload = """
            {
              "documentType": "PASSPORT",
              "documentNumber": "AB123456",
              "issuingCountry": "CZ",
              "expiryDate": "2030-12-31"
            }
        """.trimIndent()
        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/parties/$id/documents")
        } Then {
            statusCode(201)
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET party documents returns added document`() {
        val id = createdPartyId ?: return
        val body = (
            Given { this } When {
                get("/api/v1/parties/$id/documents")
            } Then {
                statusCode(200)
            }
            ).extract().body().asString()
        assertThat(body).contains("PASSPORT")
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN", "ROLE_KYC"])
    fun `PUT kyc-status updates KYC to APPROVED`() {
        val id = createdPartyId ?: return
        Given {
            contentType("application/json")
            body("""{"kycStatus": "APPROVED"}""")
        } When {
            put("/api/v1/parties/$id/kyc-status")
        } Then {
            statusCode(200)
            body("kycStatus", equalTo("APPROVED"))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET party by unknown id returns 404`() {
        Given { this } When {
            get("/api/v1/parties/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(11)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST duplicate email returns 409`() {
        val payload = """
            {
              "partyType": "INDIVIDUAL",
              "legalName": "Duplicate User",
              "email": "$uniqueEmail",
              "phone": "+420111222333"
            }
        """.trimIndent()
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(409)
        }
    }

    @Test
    @Order(12)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST party without email returns a diagnosable 400, not a silent empty body`() {
        // Contract guard: openapi.yaml marks email required. A request that omits it must
        // fail with a structured VALIDATION_ERROR body — never the body-less 400 a missing
        // non-null Kotlin field used to produce (the onboarding silent-400 footgun).
        val payload = """
            {
              "partyType": "INDIVIDUAL",
              "legalName": "No Email User",
              "phone": "+420111000999"
            }
        """.trimIndent()

        val body = (
            Given {
                contentType("application/json")
                header("Idempotency-Key", UUID.randomUUID().toString())
                body(payload)
            } When {
                post("/api/v1/parties")
            } Then {
                statusCode(400)
            }
            ).extract().body().asString()

        assertThat(body).isNotBlank()
        assertThat(body.lowercase()).contains("email")
    }

    // ── ADR-0118 GDPR Art. 15 subject-access export ───────────────────────

    @Test
    @Order(13)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN"])
    fun `GET gdpr-export returns the subject PII and document metadata`() {
        val id = createdPartyId ?: return
        Given { this } When {
            get("/api/v1/parties/$id/gdpr-export")
        } Then {
            statusCode(200)
            body("subject.id", equalTo(id))
            body("subject.email", equalTo(uniqueEmail))
            body("documents[0].documentType", notNullValue())
            body("exportedAt", notNullValue())
            body("scope", notNullValue())
        }
    }

    @Test
    @Order(14)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_ADMIN"])
    fun `GET gdpr-export for an unknown party returns 404`() {
        Given { this } When {
            get("/api/v1/parties/${UUID.randomUUID()}/gdpr-export")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(15)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET gdpr-export with a non-ADMIN role returns 403`() {
        // Subject-access export is ADMIN-only (GDPR Art. 15 is privileged). A read role
        // must not be able to pull the full PII set.
        val id = createdPartyId ?: return
        Given { this } When {
            get("/api/v1/parties/$id/gdpr-export")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(17)
    fun `GET party by id with no identity at all returns 401`() {
        // The VoP hop-2 path (GET /api/v1/parties/{id}). A recorded 401 pact cannot survive
        // provider replay — the replay TestAuthMechanism authenticates every request — so the
        // negative case is asserted here instead (#8552 class).
        Given { this } When {
            get("/api/v1/parties/${java.util.UUID.randomUUID()}")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @Order(16)
    fun `GET gdpr-export with no identity at all returns 401`() {
        // Previously this endpoint carried no @Authenticated/@RolesAllowed annotation and an
        // anonymous caller reached the handler's own manual role check; now @Authenticated
        // rejects it before that.
        val id = createdPartyId ?: return
        Given { this } When {
            get("/api/v1/parties/$id/gdpr-export")
        } Then {
            statusCode(401)
        }
    }

    // ── ADR-0055 name search ──────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `GET search by name returns matching party, data-minimised`() {
        val marker = "Zphinx${UUID.randomUUID().toString().take(6)}"
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{"partyType":"INDIVIDUAL","legalName":"$marker Novak","email":"s.${UUID.randomUUID()}@openbank.test","phone":"+420777000111"}""",
            )
        } When { post("/api/v1/parties") } Then { statusCode(201) }

        Given {
            queryParam("q", marker)
            queryParam("limit", 20)
        } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(200)
            body("data[0].legalName", equalTo("$marker Novak"))
            // Data-minimisation: the search summary must not leak phone/address/DOB.
            body("data[0].phone", org.hamcrest.Matchers.nullValue())
            body("pagination.hasNextPage", equalTo(false))
        }
    }

    @Test
    @Order(21)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET search with sub-2-char term returns an empty page, never a scan`() {
        Given { queryParam("q", "a") } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(200)
            body("data.size()", equalTo(0))
            body("pagination.hasNextPage", equalTo(false))
        }
    }

    @Test
    @Order(22)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `GET search finds a party by email, phone and registration-number fragments (ADR-0228)`() {
        val marker = UUID.randomUUID().toString().take(8)
        val email = "ops.$marker@openbank.test"
        val phone = "+420777${(100000..999999).random()}"
        val regNo = "IC$marker"
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{"partyType":"COMPANY","legalName":"Searchco $marker","email":"$email","phone":"$phone","registrationNumber":"$regNo"}""",
            )
        } When { post("/api/v1/parties") } Then { statusCode(201) }

        listOf(email, marker).forEach { q ->
            Given { queryParam("q", q) } When {
                get("/api/v1/parties/search")
            } Then {
                statusCode(200)
                body("data[0].legalName", equalTo("Searchco $marker"))
            }
        }
        // The same fragment matches via phone and via registration number (the OR covers both).
        Given { queryParam("q", phone) } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(200)
            body("data[0].legalName", equalTo("Searchco $marker"))
        }
        Given { queryParam("q", regNo) } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(200)
            body("data[0].legalName", equalTo("Searchco $marker"))
        }
    }

    @Test
    @Order(22)
    fun `GET search without auth returns 401`() {
        Given { queryParam("q", "novak") } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @Order(23)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_COMPLIANCE"])
    fun `GET search with a role outside the permitted set returns 403`() {
        Given { queryParam("q", "novak") } When {
            get("/api/v1/parties/search")
        } Then {
            statusCode(403)
        }
    }

    /**
     * An absent body, and a null element inside `phoneHashes`.
     *
     * `lookupDirectory` is a `suspend fun`, and the Kotlin compiler emits NO
     * `Intrinsics.checkNotNullParameter` for a suspending function, so the null JAX-RS injects for
     * an absent body did not fail at offset 0 -- it flowed into the body and died at the first
     * dereference with `Parameter specified as non-null is null` (#5913).
     *
     * The null element is the same defect one level down: Jackson's Kotlin module null-checks
     * constructor PARAMETERS, never the ELEMENTS of a collection, so `[null]` deserialises into a
     * `List<String>` holding a null and `PartyService.lookupByPhoneHashes` NPEs on `it.trim()`.
     *
     * Both are malformed input from the customer edge, so both must be 400.
     */
    @Test
    @Order(24)
    @TestSecurity(user = "directory-lookup-it", roles = ["ROLE_API"])
    fun `POST directory lookup answers 400 for an absent body and for a null hash`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/parties/directory/lookup")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
            body("""{"phoneHashes": [null]}""")
        } When {
            post("/api/v1/parties/directory/lookup")
        } Then {
            statusCode(400)
        }
    }
}
