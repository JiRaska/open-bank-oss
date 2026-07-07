// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.push

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Optional

class PushSenderTest {

    private val mapper = ObjectMapper()

    private fun fcm() = FcmPushSender().also { it.objectMapper = mapper }
    private fun apns() = ApnsPushSender().also { it.objectMapper = mapper }

    private fun msg(platform: PushPlatform) =
        PushMessage(platform, "tok", "Title", "Body", mapOf("template" to "WELCOME"))

    /** A syntactically valid (but freshly generated, throwaway) PKCS#8 RSA private key, base64. */
    private fun fakeRsaPkcs8Base64(): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Base64.getEncoder().encodeToString(pair.private.encoded)
    }

    /**
     * A syntactically valid (but freshly generated, throwaway) PKCS#8 EC (P-256) private key, as
     * PEM text — [ApnsPushSender.parseKey] only skips its (buggy for bare base64) double-decode
     * path when the value contains a "BEGIN" marker, matching how a real .p8 file is supplied.
     */
    private fun fakeEcPkcs8Pem(): String {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val b64 = Base64.getEncoder().encodeToString(gen.generateKeyPair().private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"
    }

    // --- FCM response mapping ---

    @Test
    fun `fcm 200 maps to success with message name`() {
        val result = fcm().mapResponse(200, """{"name":"projects/p/messages/123"}""")
        assertThat(result.success).isTrue()
        assertThat(result.messageId).isEqualTo("projects/p/messages/123")
        assertThat(result.invalidToken).isFalse()
    }

    @Test
    fun `fcm 404 NOT_FOUND retires the token`() {
        val result = fcm().mapResponse(404, """{"error":{"status":"NOT_FOUND","message":"not found"}}""")
        assertThat(result.success).isFalse()
        assertThat(result.invalidToken).isTrue()
        assertThat(result.errorCode).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `fcm 400 INVALID_ARGUMENT retires the token`() {
        val result = fcm().mapResponse(400, """{"error":{"status":"INVALID_ARGUMENT"}}""")
        assertThat(result.invalidToken).isTrue()
    }

    @Test
    fun `fcm 503 is a retryable failure, not an invalid token`() {
        val result = fcm().mapResponse(503, """{"error":{"status":"UNAVAILABLE"}}""")
        assertThat(result.success).isFalse()
        assertThat(result.invalidToken).isFalse()
    }

    @Test
    fun `fcm disabled adapter returns a skipped no-op`() {
        val result = fcm().also { it.enabled = false }.send(msg(PushPlatform.FCM)).await().indefinitely()
        assertThat(result.success).isTrue()
        assertThat(result.skipped).isTrue()
    }

    @Test
    fun `fcm enabled but no service account configured fails closed with a CONFIG error`() {
        val result = fcm().also { it.enabled = true }.send(msg(PushPlatform.FCM)).await().indefinitely()
        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("CONFIG")
    }

    @Test
    fun `fcm enabled with an unparsable service account JSON fails closed instead of throwing`() {
        val result = fcm().also {
            it.enabled = true
            it.serviceAccountJson = Optional.of("not valid json")
        }.send(msg(PushPlatform.FCM)).await().indefinitely()

        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("CONFIG")
    }

    @Test
    fun `fcm enabled with a service account that has no projectId and no override fails closed`() {
        val result = fcm().also {
            it.enabled = true
            it.serviceAccountJson = Optional.of(
                """{"client_email":"sa@x.iam.gserviceaccount.com","private_key":"${fakeRsaPkcs8Base64()}"}""",
            )
        }.send(msg(PushPlatform.FCM)).await().indefinitely()

        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("CONFIG")
        assertThat(result.errorMessage).contains("projectId")
    }

    // --- APNs response mapping ---

    @Test
    fun `apns 200 maps to success with apns-id`() {
        val result = apns().mapResponse(200, "apns-id-xyz", null)
        assertThat(result.success).isTrue()
        assertThat(result.messageId).isEqualTo("apns-id-xyz")
    }

    @Test
    fun `apns 410 Unregistered retires the token`() {
        val result = apns().mapResponse(410, null, """{"reason":"Unregistered"}""")
        assertThat(result.success).isFalse()
        assertThat(result.invalidToken).isTrue()
    }

    @Test
    fun `apns BadDeviceToken retires the token`() {
        val result = apns().mapResponse(400, null, """{"reason":"BadDeviceToken"}""")
        assertThat(result.invalidToken).isTrue()
    }

    @Test
    fun `apns 429 TooManyRequests is retryable, not invalid`() {
        val result = apns().mapResponse(429, null, """{"reason":"TooManyRequests"}""")
        assertThat(result.success).isFalse()
        assertThat(result.invalidToken).isFalse()
    }

    @Test
    fun `apns disabled adapter returns a skipped no-op`() {
        val result = apns().also { it.enabled = false }.send(msg(PushPlatform.APNS)).await().indefinitely()
        assertThat(result.success).isTrue()
        assertThat(result.skipped).isTrue()
    }

    @Test
    fun `apns enabled but no signing key configured fails closed with a CONFIG error`() {
        val result = apns().also { it.enabled = true }.send(msg(PushPlatform.APNS)).await().indefinitely()
        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("CONFIG")
    }

    @Test
    fun `apns enabled with an unparsable signing key fails closed instead of throwing`() {
        val result = apns().also {
            it.enabled = true
            it.privateKeyPem = Optional.of("not a valid key")
        }.send(msg(PushPlatform.APNS)).await().indefinitely()

        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("CONFIG")
    }

    @Test
    fun `apns enabled with a valid key but missing keyId and teamId fails closed`() {
        val result = apns().also {
            it.enabled = true
            it.privateKeyPem = Optional.of(fakeEcPkcs8Pem())
        }.send(msg(PushPlatform.APNS)).await().indefinitely()

        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("CONFIG")
        assertThat(result.errorMessage).contains("keyId")
    }

    // --- Router ---

    @Test
    fun `router dispatches by platform`() {
        val fcm = mockk<FcmPushSender>()
        val apns = mockk<ApnsPushSender>()
        every { fcm.send(any()) } returns Uni.createFrom().item(PushResult.ok("fcm"))
        every { apns.send(any()) } returns Uni.createFrom().item(PushResult.ok("apns"))
        val router = PushSenderRouter(fcm, apns)

        assertThat(router.send(msg(PushPlatform.FCM)).await().indefinitely().messageId).isEqualTo("fcm")
        assertThat(router.send(msg(PushPlatform.APNS)).await().indefinitely().messageId).isEqualTo("apns")
        verify(exactly = 1) { fcm.send(any()) }
        verify(exactly = 1) { apns.send(any()) }
    }
}
