// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.it.KycKafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Real broker -> consumer -> PostgreSQL -> HTTP, including acknowledged redelivery. */
@QuarkusTest
@TestProfile(BusinessAdverseMediaKafkaIT.KafkaProfile::class)
class BusinessAdverseMediaKafkaIT {
    class KafkaProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(KycKafkaTestResource::class.java))
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var objectMapper: ObjectMapper

    @Test
    @TestSecurity(user = "test-kyc-viewer", roles = ["ROLE_VIEWER"])
    fun `business event and its replay retain missing-source evidence without duplicate cases or outbox events`() {
        val partyId = UUID.randomUUID()
        val body = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "PARTY_CREATED",
                "partyId" to partyId.toString(),
                "partyType" to "COMPANY",
                "legalName" to "Synthetic KYB test company",
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
                repeat(2) {
                    val record = producer.send(ProducerRecord(KycKafkaTestResource.TOPIC, partyId.toString(), body))
                        .get(10, TimeUnit.SECONDS)
                    awaitCommitted(admin, TopicPartition(record.topic(), record.partition()), record.offset() + 1)
                    assertStoredCase(partyId)
                }
            }
        }
    }

    private fun awaitCommitted(admin: Admin, partition: TopicPartition, offset: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val committed = admin.listConsumerGroupOffsets(KycKafkaTestResource.GROUP)
                .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]
            if (committed != null && committed.offset() >= offset) return
            Thread.sleep(100)
        }
        error("KYC consumer did not acknowledge the business event")
    }

    private fun assertStoredCase(partyId: UUID) {
        val id = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT case_id, subject_type, status, checks_json, " +
                    "(SELECT count(*) FROM kyc_outbox o WHERE o.aggregate_id = c.case_id) AS events " +
                    "FROM kyc_cases c WHERE party_id = ?",
            ).use { query ->
                query.setObject(1, partyId)
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    val caseId = rows.getObject("case_id", UUID::class.java)
                    assertThat(rows.getString("subject_type")).isEqualTo("BUSINESS")
                    assertThat(rows.getString("status")).isEqualTo("OPEN")
                    assertThat(rows.getLong("events")).isEqualTo(1)
                    val check = objectMapper.readTree(rows.getString("checks_json"))
                        .single { it.path("checkType").asText() == "ADVERSE_MEDIA" }
                    assertThat(check.path("status").asText()).isEqualTo("MANUAL_REVIEW")
                    assertThat(check.path("result").asText()).isEqualTo("SOURCE_NOT_CONFIGURED")
                    assertThat(check.path("provider").isNull).isTrue()
                    assertThat(check.path("performedAt").isNull).isTrue()
                    assertThat(rows.next()).isFalse()
                    caseId
                }
            }
        }
        val check = given().get("/api/v1/kyc/cases/$id").then().statusCode(200).extract().jsonPath()
            .getMap<String, Any?>("checks.find { it.checkType == 'ADVERSE_MEDIA' }")
        assertThat(check["status"]).isEqualTo("MANUAL_REVIEW")
        assertThat(check["result"]).isEqualTo("SOURCE_NOT_CONFIGURED")
        assertThat(check).containsEntry("provider", null).containsEntry("performedAt", null)
    }
}
