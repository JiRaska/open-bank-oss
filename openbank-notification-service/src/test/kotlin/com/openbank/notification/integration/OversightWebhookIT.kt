// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationRequest
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * End-to-end integration test for the oversight webhook side-channel (ADR-0059).
 *
 * Proves that when an oversight-template notification (e.g. ACCOUNT_FROZEN) arrives on Kafka,
 * the Slack webhook adapter fires an HTTP POST to the configured URL. WireMock stands in for
 * the real Slack endpoint and records all requests so we can assert delivery without network egress.
 *
 * Context isolation: [SlackProfile] forces a fresh Quarkus CDI context via the `slack-it` profile
 * defined in application.yaml. That profile sets `openbank.notification.webhook.slack.enabled=true`
 * and reads `${SLACK_WEBHOOK_URL}` from the System property written by [SlackMockResource.start].
 * Because [SlackMockResource] runs BEFORE Quarkus CDI boot, the dynamic WireMock port is available
 * for property expansion when `@ConfigProperty` values are resolved.
 */
@QuarkusTest
@TestProfile(OversightWebhookIT.SlackProfile::class)
@QuarkusTestResource(NotificationConsumerIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(OversightWebhookIT.SlackMockResource::class, restrictToAnnotatedClass = true)
class OversightWebhookIT {

    /**
     * Custom qualifier for WireMock field injection from [SlackMockResource.inject].
     */
    @Target(AnnotationTarget.FIELD)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class InjectWireMock

    /**
     * Shared WireMock lifecycle holder (thread-safe, start-once semantics).
     *
     * WireMock is started by [SlackMockResource.start], which runs BEFORE Quarkus CDI boot.
     * That makes the port available for `${SLACK_WEBHOOK_URL}` expansion in application.yaml
     * before `@ConfigProperty` values are resolved.
     */
    object SlackHolder {
        @Volatile
        var instance: WireMockServer? = null

        fun server(): WireMockServer {
            val current = instance
            if (current != null && current.isRunning) return current
            synchronized(this) {
                val current2 = instance
                if (current2 != null && current2.isRunning) return current2
                val s = WireMockServer(WireMockConfiguration.options().dynamicPort())
                s.start()
                s.stubFor(
                    WireMock.post(WireMock.urlPathEqualTo("/slack"))
                        .willReturn(WireMock.ok()),
                )
                instance = s
                return s
            }
        }

        fun stop() {
            synchronized(this) {
                instance?.stop()
                instance = null
            }
        }
    }

    /**
     * Test resource that starts WireMock and injects the dynamic URL into Quarkus config via
     * System property (SmallRye ordinal 400 > YAML ordinal 250). Runs before Quarkus boots.
     */
    class SlackMockResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val port = SlackHolder.server().port()
            System.setProperty("SLACK_WEBHOOK_URL", "http://localhost:$port/slack")
            return mapOf(
                "openbank.notification.webhook.slack.enabled" to "true",
                "openbank.notification.webhook.slack.url" to "http://localhost:$port/slack",
                "SLACK_WEBHOOK_URL" to "http://localhost:$port/slack",
            )
        }

        override fun stop() {
            System.clearProperty("SLACK_WEBHOOK_URL")
            SlackHolder.stop()
        }

        override fun inject(testInjector: QuarkusTestResourceLifecycleManager.TestInjector) {
            testInjector.injectIntoFields(
                SlackHolder.server(),
                QuarkusTestResourceLifecycleManager.TestInjector.AnnotatedAndMatchesType(
                    InjectWireMock::class.java,
                    WireMockServer::class.java,
                ),
            )
        }
    }

    /**
     * Activates the `slack-it` Quarkus profile (defined in application.yaml) which enables Slack
     * oversight webhooks. [getConfigOverrides] adds the same overrides as [SlackMockResource.start]
     * as a belt-and-suspenders layer; these are the highest-priority config source in Quarkus.
     */
    class SlackProfile : QuarkusTestProfile {
        override fun getConfigProfile(): String = "slack-it"

        override fun getConfigOverrides(): Map<String, String> {
            val port = SlackHolder.server().port()
            return mapOf(
                "openbank.notification.webhook.slack.enabled" to "true",
                "openbank.notification.webhook.slack.url" to "http://localhost:$port/slack",
            )
        }
    }

    @InjectWireMock
    lateinit var wireMock: WireMockServer

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Test
    fun `ACCOUNT_FROZEN oversight notification triggers exactly one Slack POST`() {
        val partyId = UUID.randomUUID()
        val request = NotificationRequest(
            partyId = partyId,
            channel = NotificationChannel.EMAIL,
            template = NotificationTemplate.ACCOUNT_FROZEN,
            recipient = "ops@example.com",
            variables = mapOf("reason" to "suspected_fraud"),
        )
        val payload = objectMapper.writeValueAsString(request)

        val source: InMemorySource<Message<String>> = connector.source("notification-events-in")
        source.runOnVertxContext(true)

        val acked = CompletableFuture<Void>()
        val message = Message.of(
            payload,
            Supplier<java.util.concurrent.CompletionStage<Void>> {
                acked.complete(null)
                CompletableFuture.completedFuture(null)
            },
        )
        source.send(message)

        // Wait for full pipeline (email + oversight fan-out) to complete before asserting.
        acked.get(20, TimeUnit.SECONDS)

        // Verify exactly one POST reached the mocked Slack endpoint.
        wireMock.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/slack")))
    }
}
