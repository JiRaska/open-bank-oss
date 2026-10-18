// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.party.integration.TestJsonWebToken
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.ClaimType
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The customer-identity half of the party journey over real HTTP against real Postgres +
 * Redpanda: mobile self-registration and its idempotent resume, the three GDPR export caller
 * shapes (staff, subject JWT, customer edge), Art. 17 erasure with its anonymized tombstone,
 * and the ADR-0179 duplicate merge with its fail-closed account precondition. Durable state is
 * re-read over JDBC (`parties` row, `party_outbox` envelope) — the suite proves the evidence a
 * regulator or a downstream consumer would see, not just the HTTP status codes.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
@Suppress("LongMethod")
class PartyIdentityE2E : PartyE2EBase() {

    @Inject
    lateinit var accountGuard: E2EPartyAccountGuard

    // ----------------------------------------------------------------------------------------------
    // Mobile self-registration
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "e2e-self-register-sub", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "e2e-self-register-sub"),
            Claim(key = "email_verified", value = "true", type = ClaimType.BOOLEAN),
            Claim(key = "email", value = "e2e-self-register@example.test"),
        ],
    )
    fun `self register then me then resume is idempotent by keycloak sub`() {
        TestJsonWebToken.configure(
            sub = "e2e-self-register-sub",
            email = "e2e-self-register@example.test",
            emailVerified = true,
        )
        try {
            val body = "{\"partyType\":\"INDIVIDUAL\",\"legalName\":\"E2E Self ${UUID.randomUUID()}\"," +
                "\"email\":\"e2e-self-register@example.test\"}"

            val partyId = Given {
                spec(jsonSpec(body))
            } When {
                post("/api/v1/parties/self-register")
            } Then {
                statusCode(201)
                body("email", org.hamcrest.Matchers.equalTo("e2e-self-register@example.test"))
            } Extract {
                UUID.fromString(path<String>("id"))
            }

            Given { this } When {
                get("/api/v1/parties/me")
            } Then {
                statusCode(200)
                body("id", org.hamcrest.Matchers.equalTo(partyId.toString()))
            }

            // A repeat registration with the same sub resumes the existing party instead of
            // minting a duplicate — the response says so on X-Resumed.
            val resumed = Given {
                spec(jsonSpec(body))
            } When {
                post("/api/v1/parties/self-register")
            } Then {
                statusCode(200)
                header("X-Resumed", "true")
                body("id", org.hamcrest.Matchers.equalTo(partyId.toString()))
            } Extract {
                UUID.fromString(path<String>("id"))
            }
            assertThat(resumed).isEqualTo(partyId)

            assertThat(
                queryLong("SELECT count(*) FROM parties WHERE keycloak_sub = ?", "e2e-self-register-sub"),
            ).isEqualTo(1)
        } finally {
            TestJsonWebToken.reset()
        }
    }

    @Test
    @TestSecurity(user = "e2e-unverified-sub", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "e2e-unverified-sub"),
            Claim(key = "email_verified", value = "false", type = ClaimType.BOOLEAN),
            Claim(key = "email", value = "e2e-unverified@example.test"),
        ],
    )
    fun `an unverified email cannot self register`() {
        TestJsonWebToken.configure(
            sub = "e2e-unverified-sub",
            email = "e2e-unverified@example.test",
            emailVerified = false,
        )
        try {
            Given {
                spec(
                    jsonSpec(
                        "{\"partyType\":\"INDIVIDUAL\",\"legalName\":\"E2E Unverified\"," +
                            "\"email\":\"e2e-unverified@example.test\"}",
                    ),
                )
            } When {
                post("/api/v1/parties/self-register")
            } Then {
                statusCode(403)
                body("code", org.hamcrest.Matchers.equalTo("EMAIL_NOT_VERIFIED"))
            }
        } finally {
            TestJsonWebToken.reset()
        }
    }

    @Test
    fun `anonymous callers are rejected from me and self-register`() {
        Given { this } When {
            get("/api/v1/parties/me")
        } Then {
            statusCode(401)
        }

        Given {
            spec(jsonSpec("{\"partyType\":\"INDIVIDUAL\",\"legalName\":\"Anon\",\"email\":\"a@b.c\"}"))
        } When {
            post("/api/v1/parties/self-register")
        } Then {
            statusCode(401)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // GDPR exports — three caller shapes
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "e2e-gdpr-admin", roles = ["ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-gdpr-admin"),
            Claim(key = "preferred_username", value = "e2e-gdpr-admin"),
        ],
    )
    fun `admin reads the art 15 export and the art 20 portability export`() {
        val partyId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given { this } When {
            get("/api/v1/parties/$partyId/gdpr-export")
        } Then {
            statusCode(200)
            body("subject.id", org.hamcrest.Matchers.equalTo(partyId.toString()))
            body("subject.email", org.hamcrest.Matchers.notNullValue())
            body("scope", org.hamcrest.Matchers.notNullValue())
        }

        Given { this } When {
            get("/api/v1/parties/$partyId/gdpr-portability-export")
        } Then {
            statusCode(200)
        }

        Given { this } When {
            get("/api/v1/parties/${UUID.randomUUID()}/gdpr-export")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "e2e-gdpr-dpo", roles = ["ROLE_DPO"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-gdpr-dpo"),
            Claim(key = "preferred_username", value = "e2e-gdpr-dpo"),
        ],
    )
    fun `the dpo reads the art 15 export`() {
        // ROLE_DPO holds no party-create role: seed the subject row directly (the same minimal
        // insert GdprSubjectAccessViaEdgeIT uses) and exercise only the right being tested.
        val partyId = UUID.randomUUID()
        insertParty(partyId, "e2e-${UUID.randomUUID()}@example.test", "E2E DPO Subject ${UUID.randomUUID()}")

        Given { this } When {
            get("/api/v1/parties/$partyId/gdpr-export")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "e2e-gdpr-subject", roles = ["ROLE_CUSTOMER"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "e2e-gdpr-subject"),
            Claim(key = "email_verified", value = "true", type = ClaimType.BOOLEAN),
            Claim(key = "email", value = "e2e-gdpr-subject@example.test"),
        ],
    )
    fun `the subject jwt reads its own export through the self branch`() {
        TestJsonWebToken.configure(
            sub = "e2e-gdpr-subject",
            email = "e2e-gdpr-subject@example.test",
            emailVerified = true,
        )
        try {
            val partyId = Given {
                spec(
                    jsonSpec(
                        "{\"partyType\":\"INDIVIDUAL\",\"legalName\":\"E2E Subject ${UUID.randomUUID()}\"," +
                            "\"email\":\"e2e-gdpr-subject@example.test\"}",
                    ),
                )
            } When {
                post("/api/v1/parties/self-register")
            } Then {
                statusCode(201)
            } Extract {
                UUID.fromString(path<String>("id"))
            }

            Given { this } When {
                get("/api/v1/parties/$partyId/gdpr-export")
            } Then {
                statusCode(200)
                body("subject.id", org.hamcrest.Matchers.equalTo(partyId.toString()))
            }

            Given { this } When {
                get("/api/v1/parties/$partyId/gdpr-portability-export")
            } Then {
                statusCode(200)
            }
        } finally {
            TestJsonWebToken.reset()
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "service-account-openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `the customer edge reads only the subject its header names`() {
        val partyId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given {
            header("X-Customer-Party-Id", partyId.toString())
        } When {
            get("/api/v1/parties/$partyId/gdpr-export")
        } Then {
            statusCode(200)
        }

        Given {
            header("X-Customer-Party-Id", partyId.toString())
        } When {
            get("/api/v1/parties/$partyId/gdpr-portability-export")
        } Then {
            statusCode(200)
        }

        // Header naming a DIFFERENT party: the edge cannot read a subject it did not authenticate.
        Given {
            header("X-Customer-Party-Id", UUID.randomUUID().toString())
        } When {
            get("/api/v1/parties/$partyId/gdpr-export")
        } Then {
            statusCode(403)
        }

        Given { this } When {
            get("/api/v1/parties/$partyId/gdpr-export")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `plain staff without admin or dpo role is refused the export`() {
        Given { this } When {
            get("/api/v1/parties/${UUID.randomUUID()}/gdpr-export")
        } Then {
            statusCode(403)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // GDPR Art. 17 erasure
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "party-e2e-admin", roles = ["ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-admin"),
            Claim(key = "preferred_username", value = "party-e2e-admin"),
        ],
    )
    fun `erasure anonymizes the row writes PARTY_ERASED and keeps the tombstone readable`() {
        val email = "e2e-${UUID.randomUUID()}@example.test"
        val partyId = Given {
            spec(
                createSpec(
                    createPartyBody(email, phone = "+420603999888"),
                    "key-${UUID.randomUUID()}",
                ),
            )
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        Given { this } When {
            delete("/api/v1/parties/$partyId")
        } Then {
            statusCode(204)
        }

        // The durable row is the regulatory artefact: every PII field is gone, the tombstone
        // email stays unique without being derivable from the party id.
        assertThat(partyField(partyId, "legal_name")).isEqualTo("ANONYMIZED")
        assertThat(partyField(partyId, "email")).matches("erased-[0-9a-f-]+@erased\\.invalid")
        assertThat(partyField(partyId, "phone")).isNull()
        assertThat(partyField(partyId, "status")).isEqualTo("CLOSED")

        val payload = outboxPayload(partyId, "PARTY_ERASED")
        assertThat(payload["eventType"].asText()).isEqualTo("PARTY_ERASED")
        assertThat(payload["partyId"].asText()).isEqualTo(partyId.toString())
        assertThat(payload["erasedAt"].asText()).isNotBlank()
        // #3994: erasure deliberately carries no actor — a subject identifier must not be
        // re-published on the broadcast topic by the very event that erases it.
        assertThat(payload["actorId"]).isNull()

        // The record stays addressable (a consumer holding the id sees the CLOSED tombstone).
        Given { this } When {
            get("/api/v1/parties/$partyId")
        } Then {
            statusCode(200)
            body("status", org.hamcrest.Matchers.equalTo("CLOSED"))
            body("legalName", org.hamcrest.Matchers.equalTo("ANONYMIZED"))
        }
    }

    @Test
    @TestSecurity(user = "party-e2e-viewer", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-viewer"),
            Claim(key = "preferred_username", value = "party-e2e-viewer"),
        ],
    )
    fun `erasure is admin only and an unknown party is a 404`() {
        Given { this } When {
            delete("/api/v1/parties/${UUID.randomUUID()}")
        } Then {
            statusCode(403)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // ADR-0179 duplicate merge
    // ----------------------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "party-e2e-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "party-e2e-operator"),
            Claim(key = "preferred_username", value = "party-e2e-operator"),
        ],
    )
    fun `merge retires the duplicate only after the account guard is clear`() {
        val survivorId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        val duplicateId = Given {
            spec(createSpec(createPartyBody("e2e-${UUID.randomUUID()}@example.test"), "key-${UUID.randomUUID()}"))
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            UUID.fromString(path<String>("id"))
        }

        // Fail-closed: an open account blocks the merge and names the blocking condition.
        accountGuard.openAccounts[duplicateId] = listOf("acct-e2e-1")
        Given {
            spec(jsonSpec("{\"mergedIntoPartyId\":\"$survivorId\",\"reason\":\"e2e duplicate\"}"))
        } When {
            post("/api/v1/parties/$duplicateId/merge")
        } Then {
            statusCode(409)
        }

        // Sweep done, account closed: the merge now commits with PARTY_MERGED evidence.
        accountGuard.openAccounts.remove(duplicateId)
        Given {
            spec(jsonSpec("{\"mergedIntoPartyId\":\"$survivorId\",\"reason\":\"e2e duplicate\"}"))
        } When {
            post("/api/v1/parties/$duplicateId/merge")
        } Then {
            statusCode(200)
            body("status", org.hamcrest.Matchers.equalTo("MERGED"))
            body("mergedIntoPartyId", org.hamcrest.Matchers.equalTo(survivorId.toString()))
        }

        assertThat(partyField(duplicateId, "status")).isEqualTo("MERGED")
        val payload = outboxPayload(duplicateId, "PARTY_MERGED")
        assertThat(payload["eventType"].asText()).isEqualTo("PARTY_MERGED")
        assertThat(payload["partyId"].asText()).isEqualTo(duplicateId.toString())
        assertThat(payload["mergedIntoPartyId"].asText()).isEqualTo(survivorId.toString())

        // A stale consumer following the retired id learns where to go next.
        Given { this } When {
            get("/api/v1/parties/$duplicateId")
        } Then {
            statusCode(200)
            body("status", org.hamcrest.Matchers.equalTo("MERGED"))
            body("mergedIntoPartyId", org.hamcrest.Matchers.equalTo(survivorId.toString()))
        }

        // MERGED is terminal: re-merging, self-merging, or merging into a retired party all fail.
        Given {
            spec(jsonSpec("{\"mergedIntoPartyId\":\"$survivorId\",\"reason\":\"again\"}"))
        } When {
            post("/api/v1/parties/$duplicateId/merge")
        } Then {
            statusCode(409)
        }

        Given {
            spec(jsonSpec("{\"mergedIntoPartyId\":\"$survivorId\",\"reason\":\"self\"}"))
        } When {
            post("/api/v1/parties/$survivorId/merge")
        } Then {
            statusCode(409)
        }

        Given {
            spec(jsonSpec("{\"mergedIntoPartyId\":\"$duplicateId\",\"reason\":\"chain\"}"))
        } When {
            post("/api/v1/parties/$survivorId/merge")
        } Then {
            statusCode(409)
        }

        Given {
            spec(jsonSpec("{\"mergedIntoPartyId\":\"${UUID.randomUUID()}\",\"reason\":\"ghost\"}"))
        } When {
            post("/api/v1/parties/$survivorId/merge")
        } Then {
            statusCode(404)
        }
    }
}
