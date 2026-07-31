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
import java.net.URI
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
     * Shared WireMock lifecycle holder (start-once semantics, safe across classloaders).
     *
     * Since Quarkus 3.38 the [QuarkusTestResourceLifecycleManager], the [QuarkusTestProfile]
     * and the test class itself can each be loaded by a DIFFERENT classloader, so every
     * classloader would get its own copy of this `object` — and with dynamic ports each copy
     * would start its own WireMock server. The app then posts to the URL computed in one
     * classloader while the test asserts on a different, request-free server.
     *
     * The single source of truth is therefore the JVM-global `SLACK_WEBHOOK_URL` system
     * property: the first caller (any classloader) starts the server and publishes the URL;
     * every later caller — including the profile overrides the APP config is built from —
     * reuses exactly that URL. The property is read/written under a mutex on the shared
     * `System.getProperties()` instance so a cross-classloader first-call race cannot split
     * the URL either.
     */
    object SlackHolder {
        const val URL_PROPERTY = "SLACK_WEBHOOK_URL"

        @Volatile
        var instance: WireMockServer? = null

        /**
         * The one URL everyone (app config AND test assertion) must agree on.
         * Starts the WireMock server on first use and records its URL in the system property.
         */
        fun url(): String = synchronized(System.getProperties()) {
            System.getProperty(URL_PROPERTY) ?: run {
                val s = server()
                val u = "http://localhost:${s.port()}/slack"
                System.setProperty(URL_PROPERTY, u)
                u
            }
        }

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
     * Test resource that makes sure the WireMock server exists and injects its URL into
     * Quarkus config (returned map + System property, both > YAML ordinal 250). Runs before
     * Quarkus boots. The URL always comes from [SlackHolder.url] so it is identical to what
     * [SlackProfile.getConfigOverrides] and the test assertion see.
     */
    class SlackMockResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val url = SlackHolder.url()
            return mapOf(
                "openbank.notification.webhook.slack.enabled" to "true",
                "openbank.notification.webhook.slack.url" to url,
                "SLACK_WEBHOOK_URL" to url,
            )
        }

        override fun stop() {
            System.clearProperty(SlackHolder.URL_PROPERTY)
            SlackHolder.stop()
        }
    }

    /**
     * Activates the `slack-it` Quarkus profile (defined in application.yaml) which enables Slack
     * oversight webhooks. [getConfigOverrides] carries the same URL as [SlackMockResource.start]
     * (both funnel through [SlackHolder.url]); these are the highest-priority config source in
     * Quarkus, which is exactly why they must not be allowed to diverge.
     */
    class SlackProfile : QuarkusTestProfile {
        override fun getConfigProfile(): String = "slack-it"

        override fun getConfigOverrides(): Map<String, String> {
            val url = SlackHolder.url()
            return mapOf(
                "openbank.notification.webhook.slack.enabled" to "true",
                "openbank.notification.webhook.slack.url" to url,
            )
        }
    }

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

        // Verify exactly one POST reached the mocked Slack endpoint. Assert through the WireMock
        // ADMIN API of the server whose URL the app was configured with — never an injected
        // WireMockServer field: under split classloaders (Quarkus 3.38+) the injected instance
        // can be a second, request-free server while the real one lives in another classloader.
        val adminClient = WireMock("localhost", URI(SlackHolder.url()).port)
        adminClient.verifyThat(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/slack")))
    }
}
