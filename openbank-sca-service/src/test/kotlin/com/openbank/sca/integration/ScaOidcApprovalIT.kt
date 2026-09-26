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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
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
    fun `real oidc enrollment signs and spends a challenge then revocation fences later decisions`() {
        val maker = token("sca-oidc-maker")
        val checker = token("sca-oidc-checker")
        val party = UUID.randomUUID()
        val keys = es256()
        val credentialId = "oidc-lifecycle-${UUID.randomUUID()}"
        val request = mapOf(
            "credentialId" to credentialId,
            "publicKey" to Base64.getEncoder().encodeToString(keys.public.encoded),
            "algorithm" to "ES256",
        )
        val devicePath = "/api/v1/sca/parties/$party/devices"
        val enrollmentApproval = given().auth().oauth2(maker).contentType("application/json").body(request)
            .post(devicePath).then().statusCode(202).extract().path<String>("approvalId")
        decide(checker, enrollmentApproval, 200)
        val deviceId = given().auth().oauth2(maker).header("X-Approval-Id", enrollmentApproval)
            .contentType("application/json").body(request).post(devicePath).then().statusCode(201)
            .extract().path<String>("id")
        assertThat(enrolled(party)).isEqualTo(1)

        val consumedChallenge = initiateLogin(maker, party)
        signDecision(maker, consumedChallenge, credentialId, keys, 200)
        val consumeBody = mapOf("partyId" to party)
        val services = serviceToken("openbank-services")
        given().auth().oauth2(services).contentType("application/json").body(consumeBody)
            .post("/api/v1/sca/challenges/$consumedChallenge/consume").then().statusCode(200)
        given().auth().oauth2(services).contentType("application/json").body(consumeBody)
            .post("/api/v1/sca/challenges/$consumedChallenge/consume").then().statusCode(409)

        val cancelledChallenge = initiateLogin(maker, party)
        signDecision(maker, cancelledChallenge, credentialId, keys, 200)
        val rejectedAfterRevoke = initiateLogin(maker, party)
        val revokePath = "$devicePath/$deviceId"
        val revokeApproval = given().auth().oauth2(maker).delete(revokePath).then().statusCode(202)
            .extract().path<String>("approvalId")
        decide(checker, revokeApproval, 200)
        given().auth().oauth2(maker).header("X-Approval-Id", revokeApproval)
            .delete(revokePath).then().statusCode(204)

        given().auth().oauth2(maker).get("/api/v1/sca/challenges/$cancelledChallenge").then().statusCode(200)
            .body("status", equalTo("CANCELLED"))
        signDecision(maker, rejectedAfterRevoke, credentialId, keys, 403)
        given().auth().oauth2(maker).get("$devicePath").then().statusCode(200)
            .body("[0].id", equalTo(deviceId))
            .body("[0].revokedAt", org.hamcrest.Matchers.notNullValue())

        assertThat(
            count(
                "sca_device_decisions",
                "challenge_id = '$consumedChallenge' OR challenge_id = '$cancelledChallenge'",
            ),
        ).isEqualTo(2)
        assertThat(count("sca_challenges", "id = '$consumedChallenge' AND consumed_at IS NOT NULL")).isEqualTo(1)
        assertThat(count("sca_challenges", "id = '$cancelledChallenge' AND status = 'CANCELLED'")).isEqualTo(1)
        assertThat(count("sca_enrolled_devices", "id = '$deviceId' AND revoked_at IS NOT NULL")).isEqualTo(1)
        assertThat(count("sca_outbox", "aggregate_id = '$deviceId' AND event_type = 'DEVICE_REVOKED'")).isEqualTo(1)
        assertThat(count("sca_device_decisions", "challenge_id = '$rejectedAfterRevoke'")).isZero()
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

    private fun initiateLogin(token: String, party: UUID): String =
        given().auth().oauth2(token).contentType("application/json").body(
            mapOf("partyId" to party, "purpose" to "LOGIN", "preferredMethod" to "PUSH_NOTIFICATION"),
        ).post("/api/v1/sca/challenges").then().statusCode(201).extract().path("id")

    private fun signDecision(token: String, challenge: String, credentialId: String, keys: KeyPair, expected: Int) {
        val payload = "$challenge|APPROVED||||"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(payload.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        given().auth().oauth2(token).contentType("application/json")
            .body(mapOf("credentialId" to credentialId, "decision" to "APPROVED", "signature" to signature))
            .post("/api/v1/sca/challenges/$challenge/decision").then().statusCode(expected)
    }

    private fun es256(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun count(table: String, predicate: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table WHERE $predicate").use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
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
