// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.KycService
import com.openbank.kyc.it.KycKafkaTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Automated customer screening at KYC case opening (issue #15723): real broker -> consumer ->
 * HTTP call to a stub of sanctions-service's v3 screen -> PostgreSQL -> HTTP read.
 *
 * The stub answers only a request that carries `"profile":"CUSTOMER"` and a `subjectId`; anything
 * else gets a 400, which the adapter treats as "no result". So a passing must-clear case also
 * proves the profile was sent — without that, "CLEAR" could mean "screened against nothing".
 */
@QuarkusTest
@TestProfile(KycCustomerScreeningKafkaIT.Profile::class)
@TestSecurity(user = "test-kyc-operator", roles = ["ROLE_OPERATOR"])
class KycCustomerScreeningKafkaIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(WithSanctionsStub::class.java))
    }

    class WithSanctionsStub : KycKafkaTestResource() {
        private var server: HttpServer? = null

        override fun overrides(): Map<String, String> {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/api/v3/sanctions/screen") { exchange ->
                val request = String(exchange.requestBody.readAllBytes())
                val (status, body) = answer(request)
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            http.start()
            server = http
            return mapOf(
                "quarkus.rest-client.sanctions-service.url" to "http://127.0.0.1:${http.address.port}",
                // One outage case must not open the breaker for the cases that follow it.
                "com.openbank.kyc.infrastructure.client.CustomerScreeningAdapter/screenWithResilience/" +
                    "CircuitBreaker/enabled" to "false",
            )
        }

        override fun stop() {
            server?.stop(0)
            super.stop()
        }

        private fun answer(request: String): Pair<Int, String> = when {
            !request.contains("\"profile\":\"CUSTOMER\"") || !request.contains("\"subjectId\":\"") ->
                400 to """{"error":"stub: a CUSTOMER profile and a subjectId are required"}"""
            request.contains(OUTAGE) -> 503 to """{"error":"stub outage"}"""
            request.contains(SANCTIONED) ->
                201 to
                    """{"status":"HIT","overallScore":0.97,"profile":"CUSTOMER","pepStatus":"CLEAR","matches":[""" +
                    """{"listType":"OFAC_SDN","matchKind":"SANCTIONS","matchedName":"$SANCTIONED","matchScore":0.97}]}"""
            request.contains(PEP) ->
                201 to
                    """{"status":"CLEAR","overallScore":0.0,"profile":"CUSTOMER","pepStatus":"HIT","matches":[""" +
                    """{"listType":"PEP_GLOBAL","matchKind":"PEP","matchedName":"$PEP","matchScore":0.96}]}"""
            else ->
                201 to
                    """{"status":"CLEAR","overallScore":0.0,"profile":"CUSTOMER","pepStatus":"CLEAR","matches":[]}"""
        }
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var kycService: KycService

    @AfterEach
    fun sandboxOff() {
        kycService.autoApprove = false
    }

    // ── Production shape: auto-approve off ────────────────────────────────────

    @Test
    fun `must-clear - an unlisted party has its sanctions check passed by the screen`() {
        val case = onboard(CLEAR)
        assertThat(check(case, "SANCTIONS_SCREENING")).containsEntry("status", "PASSED")
            .containsEntry("provider", "openbank-sanctions-service:CUSTOMER")
        assertThat(check(case, "PEP_SCREENING")).containsEntry("status", "PASSED")
        assertThat(case.getString("status")).isEqualTo("OPEN")
    }

    @Test
    fun `must-hit - a sanctioned party goes to review and cannot be approved`() {
        val case = onboard(SANCTIONED)
        assertThat(check(case, "SANCTIONS_SCREENING")).containsEntry("status", "MANUAL_REVIEW")
        assertThat(check(case, "SANCTIONS_SCREENING")["performedAt"]).isNotNull()
        assertThat(case.getString("riskLevel")).isEqualTo("VERY_HIGH")
        assertThat(case.getString("status")).isEqualTo("OPEN")

        given().contentType("application/json").body("""{"reason":"attempt to approve a sanctions hit"}""")
            .post("/api/v1/kyc/cases/${case.getString("id")}/approve").then().statusCode(422)
    }

    @Test
    fun `a PEP is a risk input, not a sanctions hit`() {
        val case = onboard(PEP)
        assertThat(check(case, "SANCTIONS_SCREENING")).containsEntry("status", "PASSED")
        assertThat(check(case, "PEP_SCREENING")).containsEntry("status", "MANUAL_REVIEW")
        assertThat(case.getString("riskLevel")).isEqualTo("HIGH")
    }

    @Test
    fun `an unavailable screen leaves the sanctions check pending, never passed`() {
        val case = onboard(OUTAGE)
        val sanctions = check(case, "SANCTIONS_SCREENING")
        assertThat(sanctions).containsEntry("status", "PENDING").containsEntry("performedAt", null)
            .containsEntry("provider", null)
        assertThat(case.getString("status")).isEqualTo("OPEN")
    }

    // ── Sandbox shape: auto-approve on ────────────────────────────────────────

    @Test
    fun `sandbox auto-approve still approves a party screened clear`() {
        kycService.autoApprove = true
        val case = onboard(CLEAR)
        assertThat(case.getString("status")).isEqualTo("APPROVED")
        // The real screening result is kept, not overwritten by the sandbox stamp.
        assertThat(check(case, "SANCTIONS_SCREENING")).containsEntry("provider", "openbank-sanctions-service:CUSTOMER")
    }

    @Test
    fun `sandbox auto-approve never approves a sanctions hit`() {
        kycService.autoApprove = true
        val case = onboard(SANCTIONED)
        assertThat(case.getString("status")).isEqualTo("OPEN")
        assertThat(check(case, "SANCTIONS_SCREENING")).containsEntry("status", "MANUAL_REVIEW")
    }

    @Test
    fun `sandbox auto-approve never approves a party it could not screen`() {
        kycService.autoApprove = true
        val case = onboard(OUTAGE)
        assertThat(case.getString("status")).isEqualTo("OPEN")
        assertThat(check(case, "SANCTIONS_SCREENING")).containsEntry("status", "PENDING")
    }

    @Test
    fun `sandbox auto-approve leaves a PEP for review`() {
        kycService.autoApprove = true
        val case = onboard(PEP)
        assertThat(case.getString("status")).isEqualTo("OPEN")
        assertThat(check(case, "PEP_SCREENING")).containsEntry("status", "MANUAL_REVIEW")
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    /** Publish PARTY_CREATED, wait for the consumer to acknowledge it, read the case over HTTP. */
    private fun onboard(legalName: String): JsonPath {
        val partyId = UUID.randomUUID()
        val body = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "PARTY_CREATED",
                "partyId" to partyId.toString(),
                "partyType" to "INDIVIDUAL",
                "legalName" to legalName,
            ),
        )
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val record = producer.send(ProducerRecord(KycKafkaTestResource.TOPIC, partyId.toString(), body))
                    .get(10, TimeUnit.SECONDS)
                awaitCommitted(admin, TopicPartition(record.topic(), record.partition()), record.offset() + 1)
            }
        }
        val caseId = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT case_id FROM kyc_cases WHERE party_id = ?").use { query ->
                query.setObject(1, partyId)
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    rows.getObject("case_id", UUID::class.java)
                }
            }
        }
        return given().get("/api/v1/kyc/cases/$caseId").then().statusCode(200).extract().jsonPath()
    }

    private fun check(case: JsonPath, type: String): Map<String, Any?> =
        case.getMap("checks.find { it.checkType == '$type' }")

    private fun awaitCommitted(admin: Admin, partition: TopicPartition, offset: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            val committed = admin.listConsumerGroupOffsets(KycKafkaTestResource.GROUP)
                .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]
            if (committed != null && committed.offset() >= offset) return
            Thread.sleep(100)
        }
        error("KYC consumer did not acknowledge the party event")
    }

    private companion object {
        const val CLEAR = "Synthetic Clear Person"
        const val SANCTIONED = "Synthetic Sanctioned Person"
        const val PEP = "Synthetic Politician Person"
        const val OUTAGE = "Synthetic Outage Person"
    }
}
