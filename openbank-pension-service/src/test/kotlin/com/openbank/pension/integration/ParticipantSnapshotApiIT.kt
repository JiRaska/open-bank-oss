// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.ClaimType
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ParticipantSnapshotApiIT {
    @Inject lateinit var mapper: ObjectMapper

    private val company = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val path = "/api/v2/pension/reporting/participant-snapshots"
    private val crossClientReplayKey = "participant-snapshot-cross-client-replay"
    private val body = mapOf(
        "companyId" to company.toString(),
        "periodStart" to "2002-03-01",
        "periodEnd" to "2002-03-31",
    )

    @Test
    @Order(1)
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.example.invalid"),
            Claim(key = "sub", value = "tax-client-subject"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `valid tax client creates receipt before wrong client replay attempt`() {
        given().header("Idempotency-Key", crossClientReplayKey).contentType("application/json").body(body)
            .`when`().post(path).then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.example.invalid"),
            Claim(key = "sub", value = "tax-client-subject"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `lost response replay survives source correction and historical bytes remain immutable`() {
        val key = UUID.randomUUID().toString()
        val first = given().header("Idempotency-Key", key).contentType("application/json").body(body)
            .`when`().post(path).then().statusCode(200).extract().asString()
        val receipt = mapper.readTree(first)
        val id = receipt["snapshotId"].asText()
        val original = given().queryParam("companyId", company).`when`().get("$path/$id")
            .then().statusCode(200).extract().asString()
        val frozen = mapper.readTree(original)["payloadJson"].asText()
        val digest = MessageDigest.getInstance("SHA-256").digest(frozen.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertThat(digest).isEqualTo(receipt["sha256"].asText())
        assertThat(frozen).doesNotContain("participantPartyId", "contractId")

        assertSnapshotRowsImmutable(id)

        val contractId = UUID.randomUUID()
        insertCorrectionContract(contractId)
        try {
            val replayResponse = given().header("Idempotency-Key", key).contentType("application/json").body(body)
                .`when`().post(path).then().statusCode(200).extract().response()
            assertThat(replayResponse.getHeader("Idempotent-Replayed")).isNull()
            val replay = replayResponse.asString()
            assertThat(mapper.readTree(replay)["snapshotId"].asText()).isEqualTo(id)
            val corrected = given().header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body(body).`when`().post(path)
                .then().statusCode(200).extract().asString()
            val correctedReceipt = mapper.readTree(corrected)
            assertThat(correctedReceipt["snapshotId"].asText()).isNotEqualTo(id)
            assertThat(correctedReceipt["revision"].asInt()).isEqualTo(receipt["revision"].asInt() + 1)
            assertThat(correctedReceipt["sha256"].asText()).isNotEqualTo(digest)
            val correctedPayload = given().queryParam("companyId", company).`when`()
                .get("$path/${correctedReceipt["snapshotId"].asText()}")
                .then().statusCode(200).extract().path<String>("payloadJson")
            assertThat(correctedPayload).contains("CHANGED_AFTER_PERIOD_END")
            val historical = given().queryParam("companyId", company).`when`().get("$path/$id")
                .then().statusCode(200).extract().asString()
            assertThat(mapper.readTree(historical)["payloadJson"].asText()).isEqualTo(frozen)
            given().queryParam("companyId", UUID.randomUUID()).`when`().get("$path/$id")
                .then().statusCode(404)
        } finally {
            connection().use { db ->
                db.prepareStatement("DELETE FROM pension_contracts WHERE contract_id = ?").use {
                    it.setObject(1, contractId)
                    it.executeUpdate()
                }
            }
        }
    }

    private fun assertSnapshotRowsImmutable(id: String) {
        connection().use { db ->
            assertThatThrownBy {
                db.prepareStatement(
                    "UPDATE pension_participant_snapshots SET payload_json = '{}' WHERE snapshot_id = ?",
                )
                    .use {
                        it.setObject(1, UUID.fromString(id))
                        it.executeUpdate()
                    }
            }.hasMessageContaining("immutable")
            assertThatThrownBy {
                db.createStatement().use { it.execute("TRUNCATE pension_participant_snapshot_receipts") }
            }.hasMessageContaining("immutable")
        }
    }

    private fun insertCorrectionContract(contractId: UUID) {
        connection().use { db ->
            db.prepareStatement(
                """INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction,
                   pack_version, provider_entity_id, provider_type, participant_birth_date, status,
                   contribution_amount, contribution_currency, contribution_frequency, start_date, created_at, updated_at)
                   VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 2, ?,
                   'PENSION_COMPANY', '1970-01-01', 'ACTIVE', 1000, 'CZK', 'MONTHLY', '2001-01-01',
                   '2001-01-01T00:00:00Z', '2002-04-01T00:00:00Z')""",
            ).use { query ->
                query.setObject(1, contractId)
                query.setObject(2, UUID.randomUUID())
                query.setObject(3, company)
                query.executeUpdate()
            }
        }
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.example.invalid"),
            Claim(key = "sub", value = "tax-client-subject"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `concurrent first capture with same key creates one durable receipt`() {
        given().header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
            .body(body + ("companyId" to UUID.randomUUID().toString()))
            .`when`().post(path).then().statusCode(400)
        val key = UUID.randomUUID().toString()
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { workers ->
            val requests = (1..2).map {
                workers.submit(
                    Callable {
                        start.await()
                        given().header("Idempotency-Key", key).contentType("application/json").body(body)
                            .`when`().post(path).then().statusCode(200).extract().path<String>("snapshotId")
                    },
                )
            }
            start.countDown()
            assertThat(requests.map { it.get() }.distinct()).hasSize(1)
        }
        val wrongBody = body + ("periodEnd" to "2002-04-30")
        given().header("Idempotency-Key", key).contentType("application/json").body(wrongBody)
            .`when`().post(path).then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.example.invalid"),
            Claim(key = "sub", value = "tax-client-subject"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `unknown immutable schema is not decoded as current figures`() {
        val unknownId = UUID.randomUUID()
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { db ->
            db.prepareStatement(
                """INSERT INTO pension_participant_snapshots
                   (snapshot_id, company_id, period_start, period_end, revision, payload_schema_version, payload_json, sha256)
                   VALUES (?, ?, '1980-01-01', '1980-01-31', 1, 'unknown/1', '{}', ?)""",
            ).use { query ->
                query.setObject(1, unknownId)
                query.setObject(2, company)
                query.setString(
                    3,
                    MessageDigest.getInstance("SHA-256").digest("{}".toByteArray())
                        .joinToString("") { "%02x".format(it) },
                )
                query.executeUpdate()
            }
        }
        given().queryParam("companyId", company).`when`().get("$path/$unknownId")
            .then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    fun `display name without verified client token cannot capture`() {
        given().header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").body(body)
            .`when`().post(path).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.example.invalid"),
            Claim(key = "sub", value = "wrong-client-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    @Order(2)
    fun `wrong verified OAuth client cannot capture despite tax display name`() {
        val response = given().header("Idempotency-Key", crossClientReplayKey)
            .contentType("application/json").body(body)
            .`when`().post(path).then().statusCode(403).extract().response()
        assertThat(response.getHeader("Idempotent-Replayed")).isNull()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://issuer.example.invalid"),
            Claim(key = "sub", value = "tax-client-subject"),
            Claim(key = "azp", value = "[\"openbank-tax-reporting\"]", type = ClaimType.JSON_ARRAY),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `wrong type OAuth client claim fails closed`() {
        given().header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").body(body)
            .`when`().post(path).then().statusCode(403)
    }
}
