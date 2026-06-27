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

class PushSenderTest {

    private val mapper = ObjectMapper()

    private fun fcm() = FcmPushSender().also { it.objectMapper = mapper }
    private fun apns() = ApnsPushSender().also { it.objectMapper = mapper }

    private fun msg(platform: PushPlatform) =
        PushMessage(platform, "tok", "Title", "Body", mapOf("template" to "WELCOME"))

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
