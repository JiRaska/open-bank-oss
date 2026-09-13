// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.integration

import com.openbank.sca.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ScaDeviceOwnershipIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "customer-without-party-identity", roles = ["ROLE_CUSTOMER"])
    fun `customer with an unresolvable party identity cannot enroll a device`() {
        val party = UUID.randomUUID()
        enroll(party, 403)
        assertNoEnrollment(party)
    }

    @Test
    @TestSecurity(user = "customer-without-party-identity", roles = ["ROLE_CUSTOMER", "ROLE_API"])
    fun `API role does not let an unidentified customer list another party devices`() {
        given().get("/api/v1/sca/parties/${UUID.randomUUID()}/devices").then().statusCode(403)
        pending(UUID.randomUUID(), 403)
    }

    @Test
    @TestSecurity(user = OWNER, roles = ["ROLE_CUSTOMER"])
    fun `identified customer can enroll and list only their own devices`() {
        val owner = UUID.fromString(OWNER)
        enroll(owner, 201)
        given().get("/api/v1/sca/parties/$owner/devices").then().statusCode(200)
        val other = UUID.randomUUID()
        enroll(other, 403)
        given().get("/api/v1/sca/parties/$other/devices").then().statusCode(403)
        assertNoEnrollment(other)
        pending(owner, 200)
        pending(other, 403)
    }

    @Test
    @TestSecurity(user = "test-device-operator", roles = ["ROLE_OPERATOR"])
    fun `operator can still enroll and list a party devices`() {
        val party = UUID.randomUUID()
        enroll(party, 201)
        given().get("/api/v1/sca/parties/$party/devices").then().statusCode(200)
        pending(party, 200)
    }

    @Test
    @TestSecurity(user = "test-device-service", roles = ["ROLE_API"])
    fun `service identity retains its existing device path`() {
        val party = UUID.randomUUID()
        enroll(party, 201)
        given().get("/api/v1/sca/parties/$party/devices").then().statusCode(200)
        pending(party, 200)
    }

    private fun enroll(party: UUID, expected: Int) {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val key = Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
        given().contentType("application/json").body(
            mapOf("credentialId" to "ownership-${UUID.randomUUID()}", "publicKey" to key, "algorithm" to "ES256"),
        ).post("/api/v1/sca/parties/$party/devices").then().statusCode(expected)
    }

    private fun pending(party: UUID, expected: Int) {
        given().get("/api/v1/sca/parties/$party/challenges/pending").then().statusCode(expected)
    }

    private fun assertNoEnrollment(party: UUID) {
        assertThat(countForParty("sca_enrolled_devices", "party_id", party)).isZero()
        assertThat(countForParty("sca_outbox", "aggregate_id", party)).isZero()
    }

    private fun countForParty(table: String, column: String, party: UUID): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use { query ->
                query.setObject(1, party)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private companion object {
        const val OWNER = "00000000-0000-0000-0000-000000000087"
    }
}
