// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import com.openbank.sca.it.ScaOpaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** Ordered identities drive one real maker/checker journey through OPA, Redis and PostgreSQL. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(ScaOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(ScaFourEyesProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScaFourEyesFlowIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    @Order(1)
    @TestSecurity(user = "sca-test-maker", roles = ["ROLE_OPERATOR"])
    fun `a maker must obtain approval before revoking a credential`() {
        seedDevices()
        approvalId = given().delete(path(firstDevice)).then().statusCode(202)
            .body("status", equalTo("PENDING_APPROVAL")).extract().path("approvalId")
        assertThat(approvalId).isNotBlank()
        assertThat(revoked(firstDevice)).isFalse()
        assertThat(revoked(secondDevice)).isFalse()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "sca-test-maker", roles = ["ROLE_OPERATOR"])
    fun `the maker cannot approve their own revocation`() {
        requireApproval()
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/$approvalId").then().statusCode(403)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "sca-test-checker", roles = ["ROLE_OPERATOR"])
    fun `a distinct checker approves the exact revocation target`() {
        requireApproval()
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/$approvalId").then().statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("resourceId", equalTo("$party@$firstDevice"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "sca-test-maker", roles = ["ROLE_OPERATOR"])
    fun `approval for one device cannot revoke another device of the same party`() {
        requireApproval()
        given().header("X-Approval-Id", approvalId).delete(path(secondDevice)).then().statusCode(202)
        assertThat(revoked(secondDevice)).isFalse()
    }

    @Test
    @Order(5)
    @TestSecurity(user = "sca-test-maker", roles = ["ROLE_OPERATOR"])
    fun `the original approved target can be revoked exactly once`() {
        requireApproval()
        given().header("X-Approval-Id", approvalId).delete(path(firstDevice)).then().statusCode(204)
        assertThat(revoked(firstDevice)).isTrue()
        given().header("X-Approval-Id", approvalId).delete(path(secondDevice)).then().statusCode(202)
        assertThat(revoked(secondDevice)).isFalse()
    }

    @Test
    @Order(6)
    @TestSecurity(user = "sca-test-maker", roles = ["ROLE_OPERATOR"])
    fun `enrollment is parked before writing its credential`() {
        enrollmentApprovalId = given().contentType("application/json").body(enrollment)
            .post(enrollmentPath()).then().statusCode(202).extract().path("approvalId")
        assertThat(enrolled()).isFalse()
    }

    @Test
    @Order(7)
    @TestSecurity(user = "sca-test-checker", roles = ["ROLE_OPERATOR"])
    fun `checker can list and approve the pending enrollment`() {
        assertThat(enrollmentApprovalId).isNotBlank()
        val pending: List<String> = given().get("/api/v1/sca/approvals?limit=200").then()
            .statusCode(200).extract().path("id")
        assertThat(pending).contains(enrollmentApprovalId)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/$enrollmentApprovalId").then().statusCode(200)
            .body("status", equalTo("APPROVED"))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "sca-test-maker", roles = ["ROLE_OPERATOR"])
    fun `changed credential key algorithm or party cannot borrow enrollment approval`() {
        assertThat(enrollmentApprovalId).isNotBlank()
        val variants = listOf(
            enrollment + ("credentialId" to "changed-credential"),
            enrollment + ("publicKey" to newPublicKey()),
            enrollment + ("algorithm" to "ED25519"),
        )
        for (request in variants) {
            given().header("X-Approval-Id", enrollmentApprovalId).contentType("application/json").body(request)
                .post(enrollmentPath()).then().statusCode(202)
        }
        given().header("X-Approval-Id", enrollmentApprovalId).contentType("application/json").body(enrollment)
            .post("/api/v1/sca/parties/${UUID.randomUUID()}/devices").then().statusCode(202)
        assertThat(enrolled()).isFalse()
        given().header("X-Approval-Id", enrollmentApprovalId).contentType("application/json").body(enrollment)
            .post(enrollmentPath()).then().statusCode(201)
            .body("credentialId", equalTo(enrollment.getValue("credentialId")))
        assertThat(enrolled()).isTrue()
        given().header("X-Approval-Id", enrollmentApprovalId).contentType("application/json").body(enrollment)
            .post(enrollmentPath()).then().statusCode(202)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "sca-test-checker", roles = ["ROLE_OPERATOR"])
    fun `approval endpoint rejects missing bodies unknown ids and repeated decisions`() {
        given().contentType("application/json").body("null")
            .patch("/api/v1/sca/approvals/$enrollmentApprovalId").then().statusCode(400)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/${UUID.randomUUID()}").then().statusCode(404)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/$enrollmentApprovalId").then().statusCode(409)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "sca-test-customer", roles = ["ROLE_CUSTOMER"])
    fun `customer cannot read or decide the operator queue`() {
        given().get("/api/v1/sca/approvals").then().statusCode(403)
        given().contentType("application/json").body(mapOf("approve" to true))
            .patch("/api/v1/sca/approvals/$enrollmentApprovalId").then().statusCode(403)
    }

    @Test
    @Order(11)
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    fun `edge self service enrollment retains its existing policy exemption`() {
        given().contentType("application/json")
            .body(enrollment + ("credentialId" to "edge-${UUID.randomUUID()}"))
            .post(enrollmentPath()).then().statusCode(201)
    }

    @Test
    @Order(12)
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    fun `shared service consume reaches domain validation under its existing exemption`() {
        given().contentType("application/json").body(mapOf("partyId" to party))
            .post("/api/v1/sca/challenges/${UUID.randomUUID()}/consume").then().statusCode(404)
    }

    @Test
    @Order(13)
    @TestSecurity(user = "536a4369-2604-439e-bab1-3c681e5a513d", roles = ["ROLE_CUSTOMER"])
    fun `owner grant parses the composite target and rejects a foreign party`() {
        given().delete("/api/v1/sca/parties/536a4369-2604-439e-bab1-3c681e5a513d/devices/$secondDevice")
            .then().statusCode(202)
        given().delete(path(secondDevice)).then().statusCode(403)
    }

    @Test
    @Order(14)
    @TestSecurity(user = "sca-test-checker", roles = ["ROLE_OPERATOR"])
    fun `checker can inspect the authorization after it leaves the pending queue`() {
        assertThat(enrollmentApprovalId).isNotBlank()
        given().get("/api/v1/sca/approvals/$enrollmentApprovalId").then().statusCode(200)
            .body("status", equalTo("EXECUTED"))
            .body("makerId", equalTo("sca-test-maker"))
            .body("decidedBy", equalTo("sca-test-checker"))
        given().get("/api/v1/sca/approvals/${UUID.randomUUID()}").then().statusCode(404)
    }

    private fun enrolled(): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM sca_enrolled_devices WHERE credential_id = ?)")
            .use { query ->
                query.setString(1, enrollment.getValue("credentialId"))
                query.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getBoolean(1)
                }
            }
    }

    private fun enrollmentPath() = "/api/v1/sca/parties/$party/devices"

    private fun seedDevices() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO sca_enrolled_devices " +
                    "(id, party_id, credential_id, public_key_spki, algorithm, created_at) " +
                    "VALUES (?, ?, ?, 'test-public-key', 'ES256', now())",
            ).use { query ->
                for (device in listOf(firstDevice, secondDevice)) {
                    query.setObject(1, device)
                    query.setObject(2, party)
                    query.setString(3, "flow-$device")
                    assertThat(query.executeUpdate()).isEqualTo(1)
                }
            }
        }
    }

    private fun revoked(id: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT revoked_at IS NOT NULL FROM sca_enrolled_devices WHERE id = ?",
        ).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
    }

    private fun requireApproval() {
        assertThat(approvalId).describedAs("the maker stage must have created an approval").isNotBlank()
    }

    private fun path(device: UUID) = "/api/v1/sca/parties/$party/devices/$device"

    private companion object {
        val party: UUID = UUID.randomUUID()
        val firstDevice: UUID = UUID.randomUUID()
        val secondDevice: UUID = UUID.randomUUID()
        var approvalId: String = ""
        var enrollmentApprovalId: String = ""
        val enrollment = mapOf(
            "credentialId" to "flow-enroll-${UUID.randomUUID()}",
            "publicKey" to newPublicKey(),
            "algorithm" to "ES256",
        )

        fun newPublicKey(): String {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
            return Base64.getEncoder().encodeToString(keys.public.encoded)
        }
    }
}

class ScaFourEyesProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "true",
        "authz.four-eyes.enforce" to "true",
        "quarkus.scheduler.enabled" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
    )
}
