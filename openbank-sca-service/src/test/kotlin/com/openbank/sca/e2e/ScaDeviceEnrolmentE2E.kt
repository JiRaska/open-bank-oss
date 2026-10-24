// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.e2e

import com.openbank.sca.integration.OutboxDispatchDisabledProfile
import com.openbank.sca.it.PostgresRedisTestResource
import com.openbank.sca.it.StubPartyTypeLookup
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The ADR-0021 device-enrolment half of the SCA journey over real HTTP against real Postgres +
 * Valkey: enrol mints the device and its DEVICE_ENROLLED event in ONE transaction, the party-type
 * guard (#10281 item 1) fails closed, a credential is unique across parties, and the customer
 * realm may touch only its own devices. The outbox stays disabled so the JDBC evidence below is
 * owned by this suite (OutboxDispatchDisabledProfile).
 */
@QuarkusTest
@TestProfile(OutboxDispatchDisabledProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaDeviceEnrolmentE2E : ScaE2EBase() {

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `enrol mints the device lists it and writes DEVICE_ENROLLED to the outbox atomically`() {
        val party = UUID.randomUUID()
        val keys = newEs256KeyPair()
        val credential = "cred-e2e-${UUID.randomUUID()}"

        val deviceId = Given {
            spec(enrollSpec(party, credential, keys.spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }

        Given {
            pathParam("party", party)
        } When {
            get("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(200)
            body("credentialId", hasItem(credential))
        }

        // The device row and its event committed together (#8679): the outbox row is the enrolment
        // evidence and carries the eventType discriminator consumers switch on (#4353).
        assertThat(outboxCount(party, "DEVICE_ENROLLED")).isEqualTo(1)
        val payload = outboxPayload(party, "DEVICE_ENROLLED")
        assertThat(payload["eventType"].asText()).isEqualTo("DEVICE_ENROLLED")
        assertThat(payload["partyId"].asText()).isEqualTo(party.toString())
        assertThat(payload["deviceId"].asText()).isEqualTo(deviceId)
        assertThat(payload["credentialId"].asText()).isEqualTo(credential)
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `enrolment fails closed for a company party and an unavailable party register`() {
        val company = UUID.randomUUID()
        val unavailable = UUID.randomUUID()
        StubPartyTypeLookup.TYPES[company] = "COMPANY"
        StubPartyTypeLookup.UNAVAILABLE.add(unavailable)
        try {
            Given {
                spec(enrollSpec(company, "cred-company-${UUID.randomUUID()}", newEs256KeyPair().spkiB64()))
            } When {
                post("/api/v1/sca/parties/{party}/devices")
            } Then {
                statusCode(422)
            }
            Given {
                spec(enrollSpec(unavailable, "cred-unavailable-${UUID.randomUUID()}", newEs256KeyPair().spkiB64()))
            } When {
                post("/api/v1/sca/parties/{party}/devices")
            } Then {
                statusCode(503)
            }
            // Nothing was enrolled behind either refusal.
            assertThat(
                queryLong(
                    "SELECT count(*) FROM sca_enrolled_devices WHERE party_id IN (?, ?)",
                    company,
                    unavailable,
                ),
            ).isZero()
        } finally {
            StubPartyTypeLookup.TYPES.remove(company)
            StubPartyTypeLookup.UNAVAILABLE.remove(unavailable)
        }
    }

    @Test
    @TestSecurity(user = "sca-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a credential is unique across parties and reenrol of an active own credential is a no-op`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val credential = "cred-shared-${UUID.randomUUID()}"

        val first = Given {
            spec(enrollSpec(partyA, credential, newEs256KeyPair().spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }

        // Same party, same live credential: the service returns the existing device, still a 201.
        val again = Given {
            spec(enrollSpec(partyA, credential, newEs256KeyPair().spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }
        assertThat(again).isEqualTo(first)

        // A different party may not claim the same credential.
        Given {
            spec(enrollSpec(partyB, credential, newEs256KeyPair().spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(409)
        }
        assertThat(
            queryLong("SELECT count(*) FROM sca_enrolled_devices WHERE credential_id = ?", credential),
        ).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "sca-e2e-customer", roles = ["ROLE_CUSTOMER"])
    fun `a customer principal without a UUID identity is refused on every party-scoped route`() {
        val party = UUID.randomUUID()
        Given {
            spec(enrollSpec(party, "cred-${UUID.randomUUID()}", newEs256KeyPair().spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(403)
        }
        Given {
            pathParam("party", party)
        } When {
            get("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(403)
        }
        Given {
            pathParam("party", party)
        } When {
            get("/api/v1/sca/parties/{party}/challenges/pending")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = OTHER_PARTY, roles = ["ROLE_CUSTOMER"])
    fun `a customer may never reach another party's devices or pending list`() {
        Given {
            pathParam("party", OWN_PARTY)
        } When {
            get("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(403)
        }
        Given {
            spec(enrollSpec(OWN_PARTY_UUID, "cred-other-${UUID.randomUUID()}", newEs256KeyPair().spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(403)
        }
        Given {
            pathParam("party", OWN_PARTY)
        } When {
            get("/api/v1/sca/parties/{party}/challenges/pending")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = OWN_PARTY, roles = ["ROLE_CUSTOMER"])
    fun `a customer self-service principal manages and lists only its own party`() {
        val credential = "cred-own-${UUID.randomUUID()}"
        Given {
            spec(enrollSpec(OWN_PARTY_UUID, credential, newEs256KeyPair().spkiB64()))
        } When {
            post("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(201)
        }
        Given {
            pathParam("party", OWN_PARTY_UUID)
        } When {
            get("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(200)
            body("credentialId", hasItem(credential))
        }
        Given {
            pathParam("party", OWN_PARTY_UUID)
        } When {
            get("/api/v1/sca/parties/{party}/challenges/pending")
        } Then {
            statusCode(200)
        }
    }

    @Test
    fun `an anonymous caller is rejected before any handler runs`() {
        Given {
            pathParam("id", UUID.randomUUID())
        } When {
            get("/api/v1/sca/challenges/{id}")
        } Then {
            statusCode(401)
        }
        Given {
            spec(jsonSpec(loginInitiateBody(UUID.randomUUID())))
        } When {
            post("/api/v1/sca/challenges")
        } Then {
            statusCode(401)
        }
        Given {
            pathParam("party", UUID.randomUUID())
        } When {
            get("/api/v1/sca/parties/{party}/devices")
        } Then {
            statusCode(401)
        }
    }

    private companion object {
        const val OWN_PARTY = "1f2e3d4c-5b6a-7980-a1b2-c3d4e5f60718"
        const val OTHER_PARTY = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d"
        val OWN_PARTY_UUID: UUID = UUID.fromString(OWN_PARTY)
    }
}
