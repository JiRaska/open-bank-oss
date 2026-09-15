// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import com.openbank.sca.it.ScaOidcTestResource
import com.openbank.sca.it.ScaOpaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** No TestSecurity identity: every protected request carries an actual Keycloak token. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(ScaOidcTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ScaOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(ScaOidcApprovalProfile::class)
class ScaOidcApprovalIT {
    @Inject lateinit var dataSource: DataSource

    @ConfigProperty(name = "openbank.test.oidc.issuer")
    lateinit var issuer: String

    @ConfigProperty(name = "openbank.test.oidc.password")
    lateinit var password: String

    @ConfigProperty(name = "openbank.test.oidc.secret")
    lateinit var serviceSecret: String

    @Test
    fun `real operator tokens retain maker checker binding and exactly one business write`() {
        val maker = token("sca-oidc-maker")
        val checker = token("sca-oidc-checker")
        val customer = token("sca-oidc-customer")
        val party = UUID.randomUUID()
        val request = enrollment()
        val path = "/api/v1/sca/parties/$party/devices"
        val approval = given().auth().oauth2(maker).contentType("application/json").body(request)
            .post(path).then().statusCode(202).extract().path<String>("approvalId")
        assertThat(enrolled(party)).isZero()
        decide(maker, approval, 403)
        decide(customer, approval, 403)
        decide(checker, approval, 200)
        given().auth().oauth2(checker).get("/api/v1/sca/approvals/$approval").then().statusCode(200)
            .body("makerId", equalTo("sca-oidc-maker"))
            .body("decidedBy", equalTo("sca-oidc-checker"))
        given().auth().oauth2(maker).header("X-Approval-Id", approval).contentType("application/json")
            .body(request + ("credentialId" to "changed-${UUID.randomUUID()}"))
            .post(path).then().statusCode(202)
        assertThat(enrolled(party)).isZero()
        given().auth().oauth2(maker).header("X-Approval-Id", approval).contentType("application/json")
            .body(request).post(path).then().statusCode(201)
        given().auth().oauth2(maker).header("X-Approval-Id", approval).contentType("application/json")
            .body(request).post(path).then().statusCode(202)
        assertThat(enrolled(party)).isEqualTo(1)
        given().auth().oauth2(checker).get("/api/v1/sca/approvals/$approval").then().statusCode(200)
            .body("status", equalTo("EXECUTED"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT payload::jsonb->>'actorId' FROM sca_outbox WHERE aggregate_id = ? ORDER BY id",
            ).use { query ->
                query.setObject(1, UUID.fromString(approval))
                query.executeQuery().use { rows ->
                    val actors = buildList { while (rows.next()) add(rows.getString(1)) }
                    assertThat(actors).containsExactly("sca-oidc-maker", "sca-oidc-checker", "sca-oidc-maker")
                }
            }
        }
    }

    @Test
    fun `missing tampered and unprivileged tokens cannot reach protected writes`() {
        val valid = token("sca-oidc-maker")
        val parts = valid.split('.')
        val changedSignature = (if (parts[2][0] == 'A') "B" else "A") + parts[2].drop(1)
        val tampered = "${parts[0]}.${parts[1]}.$changedSignature"
        val customer = token("sca-oidc-customer")
        val party = UUID.randomUUID()
        val path = "/api/v1/sca/parties/$party/devices"
        val request = enrollment()
        given().contentType("application/json").body(request).post(path).then().statusCode(401)
        given().auth().oauth2(tampered).contentType("application/json").body(request)
            .post(path).then().statusCode(401)
        given().auth().oauth2(customer).contentType("application/json").body(request)
            .post(path).then().statusCode(403)
        given().auth().oauth2(customer).get("/api/v1/sca/approvals").then().statusCode(403)
        assertThat(enrolled(party)).isZero()
    }

    @Test
    fun `real service account tokens retain the explicit automated ceremony exemptions`() {
        val shared = serviceToken("openbank-services")
        val edge = serviceToken("openbank-edge")
        val party = UUID.randomUUID()
        given().auth().oauth2(shared).contentType("application/json").body(mapOf("partyId" to party))
            .post("/api/v1/sca/challenges/${UUID.randomUUID()}/consume").then().statusCode(404)
        given().auth().oauth2(edge).contentType("application/json").body(enrollment())
            .post("/api/v1/sca/parties/$party/devices").then().statusCode(201)
        assertThat(enrolled(party)).isEqualTo(1)
    }

    private fun token(user: String): String = given().contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "password").formParam("client_id", "sca-proof-browser")
        .formParam("username", user).formParam("password", password)
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200).extract().path("access_token")

    private fun serviceToken(client: String): String = given().contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "client_credentials").formParam("client_id", client)
        .formParam("client_secret", serviceSecret)
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200).extract().path("access_token")

    private fun decide(token: String, id: String, expected: Int) {
        given().auth().oauth2(token).contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/$id").then().statusCode(expected)
    }

    private fun enrollment(): Map<String, String> {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        return mapOf(
            "credentialId" to "oidc-${UUID.randomUUID()}",
            "publicKey" to Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded),
            "algorithm" to "ES256",
        )
    }

    private fun enrolled(party: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM sca_enrolled_devices WHERE party_id = ?").use { query ->
            query.setObject(1, party)
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }
}

class ScaOidcApprovalProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = ScaFourEyesProfile().getConfigOverrides() + mapOf(
        "quarkus.oidc.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "true",
    )
}
