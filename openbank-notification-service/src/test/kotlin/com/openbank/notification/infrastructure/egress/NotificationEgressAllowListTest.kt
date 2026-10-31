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
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.Optional
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

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

    @Test
    fun `fcm exchanges a token then posts to the allowed send host`(@TempDir dir: Path) {
        val keystorePath = dir.resolve("fcm.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "fcm", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=fcm.googleapis.com", "-ext", "san=dns:fcm.googleapis.com", "-validity", "1",
            "-keystore", keystorePath.toString(), "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { process.inputStream.readAllBytes().decodeToString() }
        val keystore = KeyStore.getInstance("PKCS12").apply {
            keystorePath.toFile().inputStream().use { load(it, "changeit".toCharArray()) }
        }
        val anchors = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        anchors.setCertificateEntry("fcm", keystore.getCertificate("fcm"))
        val trustManagers = TrustManagerFactory.getInstance("PKIX").apply { init(anchors) }.trustManagers
        val originalContext = SSLContext.getDefault()
        SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) })
        val tlsStub = WireMockServer(
            WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(keystorePath.toString())
                .keystorePassword("changeit")
                .keyManagerPassword("changeit")
                .keystoreType("PKCS12"),
        )
        try {
            tlsStub.start()
            tlsStub.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/token"))
                    .willReturn(WireMock.okJson("""{"access_token":"t","expires_in":3600}""")),
            )
            tlsStub.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/v1/projects/p/messages:send"))
                    .willReturn(WireMock.okJson("""{"name":"projects/p/messages/123"}""")),
            )
            val port = tlsStub.httpsPort()
            val sender = fcm("https://fcm.googleapis.com:$port/token").also {
                it.allowedHosts = listOf("fcm.googleapis.com:$port;private")
                it.testSendUrl = "https://fcm.googleapis.com:$port/v1/projects/p/messages:send"
            }

            val result = sender.send(push()).await().indefinitely()

            assertThat(result.success).isTrue()
            tlsStub.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/token")))
            tlsStub.verify(
                1,
                WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/projects/p/messages:send"))
                    .withHeader("Authorization", WireMock.equalTo("Bearer t"))
                    .withRequestBody(WireMock.matchingJsonPath("$.message.token", WireMock.equalTo("tok"))),
            )
        } finally {
            try {
                tlsStub.stop()
            } finally {
                SSLContext.setDefault(originalContext)
            }
        }
    }
}
