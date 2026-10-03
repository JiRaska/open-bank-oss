// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.egress

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.openbank.libs.security.EgressResolver
import com.openbank.notification.application.OversightSignal
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.infrastructure.push.FcmPushSender
import com.openbank.notification.infrastructure.webhook.SlackOversightWebhookPublisher
import com.openbank.notification.infrastructure.webhook.TeamsOversightWebhookPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.Optional

/**
 * ADR-0320 P1: every configurable notification egress URL goes through SafeHttpClient. Each adapter
 * reaches an allow-listed host and is refused — with NO request sent — for one that is not listed.
 * `stub.test` is pinned to loopback by the injected resolver, so no real DNS is involved.
 */
class NotificationEgressAllowListTest {

    private lateinit var stub: WireMockServer
    private val loopback: EgressResolver = EgressResolver { listOf(InetAddress.getLoopbackAddress()) }

    @BeforeEach
    fun start() {
        stub = WireMockServer(WireMockConfiguration.options().dynamicPort())
        stub.start()
        stub.stubFor(WireMock.post(WireMock.anyUrl()).willReturn(WireMock.ok("{}")))
    }

    @AfterEach
    fun stop() = stub.stop()

    private fun allowStub() = listOf("stub.test:${stub.port()};http;private")

    private fun signal() = OversightSignal(
        template = NotificationTemplate.ACCOUNT_FROZEN,
        primaryChannel = NotificationChannel.EMAIL,
        status = NotificationStatus.PENDING,
        occurredAt = Instant.parse("2026-06-29T05:05:00Z"),
    )

    private fun slack(url: String) = SlackOversightWebhookPublisher().also {
        it.enabled = true
        it.url = Optional.of(url)
        it.allowedHosts = allowStub()
        it.resolver = loopback
    }

    private fun teams(url: String) = TeamsOversightWebhookPublisher().also {
        it.enabled = true
        it.url = Optional.of(url)
        it.allowedHosts = allowStub()
        it.resolver = loopback
    }

    @Test
    fun `slack posts to an allow-listed host`() {
        assertThat(slack("http://stub.test:${stub.port()}/slack").publish(signal()).await().indefinitely()).isTrue()
        stub.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/slack")))
    }

    @Test
    fun `slack refuses a host that is not allow-listed and sends nothing`() {
        assertThat(slack("http://evil.test:${stub.port()}/slack").publish(signal()).await().indefinitely()).isFalse()
        stub.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))
    }

    @Test
    fun `invalid slack allow-list remains a best-effort failure`() {
        val publisher = slack("http://stub.test:${stub.port()}/slack").also {
            it.allowedHosts = listOf("bad host")
        }
        assertThat(publisher.publish(signal()).await().indefinitely()).isFalse()
        stub.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))
    }

    @Test
    fun `teams posts to an allow-listed host`() {
        assertThat(teams("http://stub.test:${stub.port()}/teams").publish(signal()).await().indefinitely()).isTrue()
        stub.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/teams")))
    }

    @Test
    fun `teams refuses a host that is not allow-listed and sends nothing`() {
        assertThat(teams("http://evil.test:${stub.port()}/teams").publish(signal()).await().indefinitely()).isFalse()
        stub.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))
    }

    @Test
    fun `invalid teams allow-list remains a best-effort failure`() {
        val publisher = teams("http://stub.test:${stub.port()}/teams").also {
            it.allowedHosts = listOf("bad host")
        }
        assertThat(publisher.publish(signal()).await().indefinitely()).isFalse()
        stub.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))
    }

    @Test
    fun `the default allow-list does not admit a Teams tenant host until configured`() {
        val pub = TeamsOversightWebhookPublisher().also {
            it.enabled = true
            it.url = Optional.of("https://tenant.webhook.office.com/webhookb2/x")
            it.allowedHosts = NotificationEgress.DEFAULT_ALLOWED_HOSTS.split(',')
            it.resolver = loopback
        }
        assertThat(pub.publish(signal()).await().indefinitely()).isFalse()
    }

    private fun fcm(tokenUri: String) = FcmPushSender().also {
        it.objectMapper = ObjectMapper()
        it.enabled = true
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val b64 = Base64.getEncoder().encodeToString(key.encoded)
        it.serviceAccountJson = Optional.of(
            """{"client_email":"sa@x.iam.gserviceaccount.com","private_key":"$b64",""" +
                """"token_uri":"$tokenUri","project_id":"p"}""",
        )
        it.allowedHosts = allowStub()
        it.resolver = loopback
    }

    private fun push() = PushMessage(PushPlatform.FCM, "tok", "Title", "Body", emptyMap())

    @Test
    fun `fcm refuses a service-account token_uri that is not allow-listed and sends nothing`() {
        val result = fcm("http://evil.test:${stub.port()}/token").send(push()).await().indefinitely()

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("egress denied")
        stub.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()))
    }

    @Test
    fun `fcm reaches an allow-listed token endpoint and is refused at an unlisted send host`() {
        stub.stubFor(
            WireMock.post(WireMock.urlPathEqualTo("/token"))
                .willReturn(WireMock.okJson("""{"access_token":"t","expires_in":3600}""")),
        )
        // Only the stub is listed, so the hard-coded fcm.googleapis.com send is refused.
        val result = fcm("http://stub.test:${stub.port()}/token").send(push()).await().indefinitely()

        stub.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/token")))
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("egress denied").contains("fcm.googleapis.com")
    }
}
