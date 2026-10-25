// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.eclipse.microprofile.config.ConfigProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Base for the onboarding E2E suite: publishes real upstream events onto Redpanda, lets the
 * projection land in Postgres, and reads the read model back over HTTP with RestAssured —
 * broker → consumer → DB → HTTP end to end. The producer asserts the consumer group's committed
 * offset after every publish so an acked-but-unprojected record cannot pass silently.
 * Concrete suites carry `@QuarkusTest` + `@TestProfile(OnboardingE2EProfile::class)` — the
 * annotations do not inherit, and Quarkus needs them on the concrete class to register the
 * test class as a CDI bean.
 */
abstract class OnboardingE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** One projected personal row as the assertions need it. */
    data class PersonalRow(
        val funnelStage: String,
        val partyStatus: String,
        val kycCaseId: UUID?,
        val kycStatus: String?,
        val scaEnrolled: Boolean,
        val deviceCount: Int,
        val blockedReason: String?,
    )

    /** One projected business case row as the assertions need it. */
    data class BusinessRow(
        val stage: String,
        val caseStatus: String,
        val signedCount: Int,
        val reviewReason: String?,
        val legalName: String?,
    )

    protected fun now(): String = Instant.now().toString()

    /** Publishes [body] as JSON on [topic] and waits until the consumer group committed it. */
    protected fun publish(topic: String, group: String, key: UUID, body: Map<String, Any?>) {
        publishAndAwaitCommit(topic, group, key.toString(), jsonBody(body))
    }

    /** Publishes [payload] on [topic] and waits until the given consumer group committed it. */
    protected fun publishAndAwaitCommit(topic: String, group: String, key: String, payload: String) {
        val configBootstrap = ConfigProvider.getConfig()
            .getValue("kafka.bootstrap.servers", String::class.java)
        // Prefer the live container over the application config: after a profile reboot the two
        // can disagree (BrokerNotAvailableException against a broker that is plainly up), and the
        // publisher must follow the resource it actually published the topic on.
        val bootstrap = OnboardingKafkaTestResource.liveBootstrap ?: configBootstrap
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val record = try {
                    producer.send(ProducerRecord(topic, key, payload))
                        .get(10, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw IllegalStateException(
                        "publish to $topic failed against bootstrap=$bootstrap " +
                            "(resource=${OnboardingKafkaTestResource.liveBootstrap}, " +
                            "config=$configBootstrap); broker not reachable",
                        e,
                    )
                }
                awaitCommitted(admin, group, TopicPartition(record.topic(), record.partition()), record.offset() + 1)
            }
        }
    }

    private fun awaitCommitted(admin: Admin, group: String, partition: TopicPartition, offset: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val committed = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)[partition]
            if (committed != null && committed.offset() >= offset) return
            Thread.sleep(100)
        }
        error("consumer group $group did not acknowledge the record on ${partition.topic()}")
    }

    /** The personal record row, or null when the projection has not landed (yet). */
    protected fun personalRow(partyId: UUID): PersonalRow? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement(
            "SELECT funnel_stage, party_status, kyc_case_id, kyc_status, sca_enrolled, " +
                "device_count, blocked_reason FROM onboarding_records WHERE party_id = ?",
        )
        ps.setObject(1, partyId)
        val rs = ps.executeQuery()
        if (!rs.next()) return@use null
        PersonalRow(
            funnelStage = rs.getString("funnel_stage"),
            partyStatus = rs.getString("party_status"),
            kycCaseId = rs.getObject("kyc_case_id", UUID::class.java),
            kycStatus = rs.getString("kyc_status"),
            scaEnrolled = rs.getBoolean("sca_enrolled"),
            deviceCount = rs.getInt("device_count"),
            blockedReason = rs.getString("blocked_reason"),
        )
    }

    /** The business case row, or null when the projection has not landed (yet). */
    protected fun businessRow(caseId: UUID): BusinessRow? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement(
            "SELECT stage, case_status, signed_count, review_reason, legal_name " +
                "FROM business_onboarding_records WHERE case_id = ?",
        )
        ps.setObject(1, caseId)
        val rs = ps.executeQuery()
        if (!rs.next()) return@use null
        BusinessRow(
            stage = rs.getString("stage"),
            caseStatus = rs.getString("case_status"),
            signedCount = rs.getInt("signed_count"),
            reviewReason = rs.getString("review_reason"),
            legalName = rs.getString("legal_name"),
        )
    }

    /** Polls until the personal record exists (the consumer projects asynchronously). */
    protected fun awaitPersonalRecord(partyId: UUID): PersonalRow {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            personalRow(partyId)?.let { return it }
            Thread.sleep(150)
        }
        error("no onboarding_records row appeared for party $partyId")
    }

    /** Polls until the business case row exists. */
    protected fun awaitBusinessCase(caseId: UUID): BusinessRow {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            businessRow(caseId)?.let { return it }
            Thread.sleep(150)
        }
        error("no business_onboarding_records row appeared for case $caseId")
    }

    /** GET /records/{partyId} parsed, asserting 200. */
    protected fun getRecordViaApi(partyId: UUID): JsonNode = getJson("/api/v1/onboarding/records/$partyId", 200)

    /** GET the given path expecting [expectedStatus], parsed as JSON. */
    protected fun getJson(path: String, expectedStatus: Int): JsonNode {
        val body = Given { this } When {
            get(path)
        } Then {
            statusCode(expectedStatus)
        } Extract {
            asString()
        }
        return objectMapper.readTree(body)
    }

    protected fun jsonBody(map: Map<String, Any?>): String = objectMapper.writeValueAsString(map)
}
