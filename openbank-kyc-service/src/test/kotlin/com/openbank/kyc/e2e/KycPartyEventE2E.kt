// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import com.openbank.kyc.it.KycKafkaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
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

/**
 * The event-driven half of KYC onboarding (ADR-0068): a `PARTY_CREATED` event on
 * `openbank.party.events` auto-opens a case without any operator call. Covers the individual
 * check set, the KYB set of a BUSINESS subject with the platform's honest adverse-media gap
 * (ADR-0256 D5 — `SOURCE_NOT_CONFIGURED` is a permanent state, never a clean screen), and the
 * idempotent replay the at-least-once consumer promises. The journey crosses the real broker →
 * consumer → Postgres → HTTP boundary, and the producer asserts the consumer group's committed
 * offset so an acknowledged-but-unprocessed record cannot pass silently.
 */
@QuarkusTest
@TestProfile(KycE2EProfile::class)
class KycPartyEventE2E : KycE2EBase() {

    @Inject
    lateinit var stub: E2EPepScreeningStub

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `PARTY_CREATED for an individual auto-opens a case with the four mandatory checks`() {
        val partyId = UUID.randomUUID()
        publishPartyCreated(partyId, partyType = null, legalName = "E2E Individual")

        awaitCaseOpened(partyId)

        val caseId = caseIdForParty(partyId)
        // The consumer screens the party's legal name at case-open time (ADR-0116); with the
        // stubbed CLEAR outcome the PEP check settles asynchronously right after the row appears,
        // so poll for the settled check instead of racing it.
        awaitPepScreened(caseId)

        val row = caseRow(caseId)
        assertThat(row.status).isEqualTo("OPEN")
        assertThat(row.subjectType).isEqualTo("INDIVIDUAL")
        assertThat(checksViaApi(caseId)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "IDENTITY" to "PENDING",
                "ADDRESS" to "PENDING",
                "PEP_SCREENING" to "PASSED",
                "SANCTIONS_SCREENING" to "PENDING",
            ),
        )
        // The open-time screen really ran, with the legal name from the event payload.
        assertThat(stub.calls).anySatisfy { assertThat(it.first).startsWith("E2E Individual") }

        assertThat(outboxEventTypes(caseId)).containsExactly("KYC_CASE_OPENED")
        assertThat(outboxPayload(caseId, "KYC_CASE_OPENED"))
            .contains("\"partyId\":\"$partyId\"")
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `PARTY_CREATED for a company opens a KYB case whose adverse-media check is honestly unresolved`() {
        val partyId = UUID.randomUUID()
        publishPartyCreated(partyId, partyType = "COMPANY", legalName = "E2E Synthetic s.r.o.")

        awaitCaseOpened(partyId)

        val caseId = caseIdForParty(partyId)
        assertThat(caseRow(caseId).subjectType).isEqualTo("BUSINESS")
        assertThat(checksViaApi(caseId)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "REGISTRY_MATCH" to "PENDING",
                "REPRESENTATIVE_AUTHORITY" to "PENDING",
                "UBO_IDENTIFICATION" to "PENDING",
                "SANCTIONS_SCREENING" to "PENDING",
                // No adverse-media source is configured on the platform (ADR-0256 D5): the check
                // is MANUAL_REVIEW with SOURCE_NOT_CONFIGURED evidence, never a decorative PASSED.
                "ADVERSE_MEDIA" to "MANUAL_REVIEW",
            ),
        )

        // The by-party lookup serves the auto-opened case to a viewer over HTTP.
        Given { this } When {
            get("/api/v1/kyc/cases/party/$partyId")
        } Then {
            statusCode(200)
        }
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `replaying PARTY_CREATED reuses the open case instead of creating a duplicate`() {
        val partyId = UUID.randomUUID()
        publishPartyCreated(partyId, partyType = "SOLE_TRADER", legalName = "E2E Replay Trader")

        awaitCaseOpened(partyId)
        val caseId = caseIdForParty(partyId)

        // Same event again — the consumer's idempotent open must not create a second case.
        publishPartyCreated(partyId, partyType = "SOLE_TRADER", legalName = "E2E Replay Trader")
        Thread.sleep(2_000)

        assertThat(caseIdsForParty(partyId)).containsExactly(caseId)
        assertThat(outboxEventTypes(caseId)).containsExactly("KYC_CASE_OPENED")
    }

    private fun publishPartyCreated(partyId: UUID, partyType: String?, legalName: String) {
        val payload = objectMapper.writeValueAsString(
            buildMap<String, Any?> {
                put("eventType", "PARTY_CREATED")
                put("partyId", partyId.toString())
                put("legalName", legalName)
                if (partyType != null) put("partyType", partyType)
            },
        )
        val bootstrap = ConfigProvider.getConfig()
            .getValue("kafka.bootstrap.servers", String::class.java)
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val record = producer.send(
                    ProducerRecord(KycKafkaTestResource.TOPIC, partyId.toString(), payload),
                ).get(10, TimeUnit.SECONDS)
                awaitCommitted(admin, TopicPartition(record.topic(), record.partition()), record.offset() + 1)
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
        error("KYC consumer did not acknowledge the PARTY_CREATED event")
    }

    /** The case row appears asynchronously once the consumer processes the event. */
    private fun awaitCaseOpened(partyId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (caseExistsForParty(partyId)) return
            Thread.sleep(150)
        }
        error("no KYC case appeared for party $partyId")
    }

    /** The open-time PEP screen lands in its own write after the case row appears — poll for it. */
    private fun awaitPepScreened(caseId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (checksViaApi(caseId)["PEP_SCREENING"] == "PASSED") return
            Thread.sleep(150)
        }
        error("PEP screen was not applied to case $caseId")
    }
}
