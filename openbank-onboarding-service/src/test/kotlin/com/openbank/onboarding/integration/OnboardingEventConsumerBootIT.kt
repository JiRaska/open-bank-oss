// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.integration

import com.openbank.onboarding.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.UUID

/**
 * Regression test for the SmallRye Config dotted-YAML-key bug (issue #695, same root cause and fix
 * as PR #685 in openbank-fraud-service): `mp.messaging.incoming.<channel>.group.id` /
 * `auto.offset.reset` written as literal YAML keys under `application.yaml` never actually resolve
 * (SmallRye Config's YAML source unconditionally quotes any leaf map key containing a literal dot),
 * so `group.id` silently falls back to Quarkus's default (`quarkus.application.name` =
 * "openbank-onboarding-service"), which onboarding-service's KafkaUser ACL
 * (kafka-onboarding-mtls.yaml) never granted Read on — every consumer poll on all three channels
 * hits a silent `org.apache.kafka.common.errors.GroupAuthorizationException`.
 *
 * Boots the app with a REAL Redpanda broker and all three consumer channels genuinely enabled,
 * publishes a real message on each topic, and asserts each consumer actually joined its intended
 * group id (not the buggy fallback) — the deepest regression test practical without a live Kafka
 * ACL/mTLS setup in the test container (Redpanda here runs without ACL enforcement at all, so a
 * naive "did the app crash" check alone would NOT have caught the group-id bug: the fix is verified
 * by asserting the effective group ids directly).
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class OnboardingEventConsumerBootIT {

    @Test
    fun `all three consumers join their configured group ids, not the buggy default fallback`() {
        val bootstrap = PostgresRedpandaTestResource.lastBootstrapServers
            ?: error("PostgresRedpandaTestResource did not record its bootstrap servers")

        publishOneMessagePerChannel(bootstrap)
        awaitReadiness()

        AdminClient.create(
            Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap) },
        ).use { admin ->
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            var groupIds: Set<String> = emptySet()
            while (System.currentTimeMillis() < deadline) {
                groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions())
                    .all().get().map { it.groupId() }.toSet()
                if (groupIds.containsAll(EXPECTED_GROUP_IDS)) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            // The regression: without the fix this set contains "openbank-onboarding-service" (the
            // Quarkus default-name fallback) instead of the three intended, ACL'd group ids.
            assertThat(groupIds).containsAll(EXPECTED_GROUP_IDS)
            assertThat(groupIds).doesNotContain(PostgresRedpandaTestResource.BUGGY_FALLBACK_GROUP_ID)
        }
    }

    @Test
    fun `the app stays ready after consuming real messages on all three channels`() {
        val bootstrap = PostgresRedpandaTestResource.lastBootstrapServers
            ?: error("PostgresRedpandaTestResource did not record its bootstrap servers")

        awaitReadiness()
        publishOneMessagePerChannel(bootstrap)

        // The app must survive processing every message: readiness must stay UP well after each
        // message was almost certainly polled and processed.
        awaitReadiness()
    }

    private fun publishOneMessagePerChannel(bootstrap: String) {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
        KafkaProducer<String, String>(props).use { producer ->
            val partyId = UUID.randomUUID()
            producer.send(
                ProducerRecord(
                    "openbank.party.events",
                    """
                    {
                      "eventType": "PARTY_CREATED",
                      "partyId": "$partyId",
                      "legalName": "Boot IT Test Party",
                      "email": "boot-it@example.test",
                      "occurredAt": "2026-07-09T10:00:00Z"
                    }
                    """.trimIndent(),
                ),
            )
            producer.send(
                ProducerRecord(
                    "openbank.kyc.events",
                    """
                    {
                      "eventType": "KYC_CASE_OPENED",
                      "partyId": "$partyId",
                      "kycCaseId": "${UUID.randomUUID()}",
                      "occurredAt": "2026-07-09T10:00:00Z"
                    }
                    """.trimIndent(),
                ),
            )
            producer.send(
                ProducerRecord(
                    "openbank.sca.events",
                    """
                    {
                      "eventType": "DEVICE_ENROLLED",
                      "partyId": "$partyId",
                      "credentialId": "boot-it-credential",
                      "occurredAt": "2026-07-09T10:00:00Z"
                    }
                    """.trimIndent(),
                ),
            )
            producer.flush()
        }
    }

    private fun awaitReadiness() {
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
                lastError = null
                break
            } catch (ex: Throwable) {
                lastError = ex
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        lastError?.let { throw it }
    }

    private companion object {
        const val DEADLINE_MS = 20_000L
        const val POLL_INTERVAL_MS = 1_000L
        val EXPECTED_GROUP_IDS = setOf(
            PostgresRedpandaTestResource.EXPECTED_PARTY_GROUP_ID,
            PostgresRedpandaTestResource.EXPECTED_KYC_GROUP_ID,
            PostgresRedpandaTestResource.EXPECTED_SCA_GROUP_ID,
        )
    }
}
