// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Representation attestation over real HTTP against a real Postgres (#9711).
 *
 * A unit test cannot tell a served route from an unserved one, and it cannot prove the partial
 * unique index actually holds — both matter here, since the whole control is "a row exists for this
 * entity and this rule text". The superseding write is read back with plain JDBC.
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RepresentationAttestationApiIT {

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    /** Reserved for this IT: the module's ITs share one database (see StubAresAdapter). */
    // One reserved entity per test that WRITES. The module's ITs share a database, so a test
    // confirming an entity another test reads would make both order-dependent (see StubAresAdapter).
    private val fresh = "76543218" // never confirmed — keeps UNATTESTED assertable
    private val stored = "63183609"
    private val stale = "12345679"
    private val superseded = "27182819"

    @Test
    @TestSecurity(user = "operator-anna", roles = ["ROLE_OPERATOR"])
    fun `an entity starts UNATTESTED, and a case for it will not proceed on the parser alone`() {
        val hash = Given {
            accept("application/json")
        } When {
            get("/api/v1/kyb/representation/CZ_ICO/$fresh")
        } Then {
            statusCode(200)
            body("state", equalTo("UNATTESTED"))
            // The parser's opinion is offered as a SUGGESTION for the form, not as a decision.
            body("parserSuggestsSigners", equalTo(2))
            body("attestation", org.hamcrest.Matchers.nullValue())
        } Extract {
            path<String>("ruleTextHash")
        }

        assertThat(hash).hasSize(64)
    }

    @Test
    @TestSecurity(user = "operator-anna", roles = ["ROLE_OPERATOR"])
    fun `a confirmation is stored, named and readable back as ATTESTED`() {
        val hash = currentHash(stored)

        Given {
            contentType("application/json")
            body("""{"confirmedSigners":3,"ruleTextHash":"$hash","note":"board minutes"}""")
        } When {
            post("/api/v1/kyb/representation/CZ_ICO/$stored")
        } Then {
            statusCode(200)
            body("confirmedSigners", equalTo(3))
            body("attestedBy", equalTo("operator-anna"))
            body("parsedSigners", equalTo(2))
        }

        Given {
            accept("application/json")
        } When {
            get("/api/v1/kyb/representation/CZ_ICO/$stored")
        } Then {
            statusCode(200)
            body("state", equalTo("ATTESTED"))
            body("attestation.confirmedSigners", equalTo(3))
        }

        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(
                "SELECT confirmed_signers, attested_by, parsed_signers FROM kyb_representation_attestations " +
                    "WHERE identifier_value = '$stored' AND superseded_at IS NULL",
            ).use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(3)
                assertThat(rs.getString(2)).isEqualTo("operator-anna")
                assertThat(rs.getInt(3))
                    .describedAs("the parser's verdict is recorded so the two can be compared later")
                    .isEqualTo(2)
                assertThat(rs.next()).describedAs("exactly one active row per entity").isFalse()
            }
        }
    }

    @Test
    @TestSecurity(user = "operator-bob", roles = ["ROLE_OPERATOR"])
    fun `a stale hash is refused with 409, not silently accepted`() {
        Given {
            contentType("application/json")
            body("""{"confirmedSigners":1,"ruleTextHash":"${"0".repeat(64)}"}""")
        } When {
            post("/api/v1/kyb/representation/CZ_ICO/$stale")
        } Then {
            statusCode(409)
            body("error", equalTo("REPRESENTATION_RULE_CHANGED"))
        }
    }

    @Test
    @TestSecurity(user = "operator-bob", roles = ["ROLE_OPERATOR"])
    fun `re-confirming supersedes rather than replaces, and history keeps both`() {
        val hash = currentHash(superseded)
        repeat(2) { i ->
            Given {
                contentType("application/json")
                body("""{"confirmedSigners":${i + 1},"ruleTextHash":"$hash"}""")
            } When {
                post("/api/v1/kyb/representation/CZ_ICO/$superseded")
            } Then {
                statusCode(200)
            }
        }

        Given {
            accept("application/json")
        } When {
            get("/api/v1/kyb/representation/CZ_ICO/$superseded/history")
        } Then {
            statusCode(200)
            body("size()", org.hamcrest.Matchers.greaterThanOrEqualTo(2))
        }

        DriverManager.getConnection(jdbcUrl, "openbank", "openbank_secret").use { c ->
            c.createStatement().executeQuery(
                "SELECT count(*) FROM kyb_representation_attestations " +
                    "WHERE identifier_value = '$superseded' AND superseded_at IS NULL",
            ).use { rs ->
                rs.next()
                assertThat(rs.getInt(1))
                    .describedAs("the partial unique index permits exactly one active row")
                    .isEqualTo(1)
            }
        }
    }

    @Test
    fun `the attestation surface is not anonymous`() {
        Given {
            accept("application/json")
        } When {
            get("/api/v1/kyb/representation/CZ_ICO/$fresh")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "operator-anna", roles = ["ROLE_OPERATOR"])
    fun `an entity no register knows is a 404, and a malformed one a 400`() {
        // 27074358 is a checksum-valid IČO the stub register does not serve — "not found".
        Given {
            accept("application/json")
        } When {
            get("/api/v1/kyb/representation/CZ_ICO/27074358")
        } Then {
            statusCode(404)
        }

        // 99999999 fails the IČO checksum, so it is a bad request rather than a missing company —
        // the two must not collapse into one answer.
        Given {
            accept("application/json")
        } When {
            get("/api/v1/kyb/representation/CZ_ICO/99999999")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "fixture-kyc-staff", roles = ["ROLE_KYC"])
    fun `statutory publication requires the exact confirmation and interactive staff identity`() {
        val route = "/api/v1/kyb/cases/${UUID.randomUUID()}/statutory-representation"
        Given {
            contentType("application/json")
            body("{}")
        } When {
            post(route)
        } Then {
            statusCode(403)
        }

        useJwt("fixture-kyc-staff", "openbank-admin-ui", "ROLE_KYC")
        Given {
            contentType("application/json")
            body("{}")
        } When {
            post(route)
        } Then {
            statusCode(400)
        }
        Given {
            contentType("application/json")
            body("""{"attestationId":"${UUID.randomUUID()}"}""")
        } When {
            post(route)
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "fixture-statutory-reader", roles = ["ROLE_API"])
    fun `only the verified delegation client may query current statutory evidence`() {
        val route = "/api/v1/kyb/cases/${UUID.randomUUID()}/statutory-representation/${UUID.randomUUID()}/current"
        useJwt("service-account-openbank-services", "openbank-services", "ROLE_API")
        Given {
            queryParam("entityPartyId", UUID.randomUUID())
        } When {
            get(route)
        } Then {
            statusCode(403)
        }

        useJwt("service-account-openbank-delegation", "openbank-delegation", "ROLE_API")
        Given {
            queryParam("entityPartyId", UUID.randomUUID())
        } When {
            get(route)
        } Then {
            statusCode(404)
            header("Cache-Control", "no-store")
        }
    }

    private fun useJwt(username: String, clientId: String, role: String) {
        val jwt = object : JsonWebToken {
            private val claims = mapOf("sub" to "fixture-subject", "preferred_username" to username, "azp" to clientId)

            override fun getName(): String = username
            override fun getClaimNames(): Set<String> = claims.keys

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
        }
        testIdentity.setTestIdentity(QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRole(role).build())
    }

    private fun currentHash(ico: String): String = Given {
        accept("application/json")
    } When {
        get("/api/v1/kyb/representation/CZ_ICO/$ico")
    } Then {
        statusCode(200)
    } Extract {
        path<String>("ruleTextHash")
    }
}
