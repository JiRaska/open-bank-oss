// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class NotificationModelTest {

    @Test
    fun `NotificationChannel has all expected values`() {
        assertThat(NotificationChannel.values()).containsExactlyInAnyOrder(
            NotificationChannel.EMAIL,
            NotificationChannel.PUSH,
        )
    }

    @Test
    fun `NotificationStatus has all expected values`() {
        assertThat(NotificationStatus.values()).containsExactlyInAnyOrder(
            NotificationStatus.PENDING,
            NotificationStatus.SENT,
            NotificationStatus.FAILED,
            NotificationStatus.BOUNCED,
        )
    }

    @Test
    fun `NotificationTemplate has all expected templates`() {
        assertThat(NotificationTemplate.values()).hasSize(14)
        assertThat(NotificationTemplate.values()).contains(
            NotificationTemplate.ACCOUNT_OPENED,
            NotificationTemplate.OTP_CODE,
            NotificationTemplate.WELCOME,
            NotificationTemplate.SCA_APPROVAL,
        )
        // SCA_APPROVAL is SECURITY so the #2 push-preference gate never suppresses it.
        assertThat(NotificationTemplate.SCA_APPROVAL.category).isEqualTo(NotificationCategory.SECURITY)
    }

    @Test
    fun `Notification data class round trip`() {
        val id = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val now = Instant.now()
        val notification = Notification(
            id = id, partyId = partyId,
            channel = NotificationChannel.EMAIL,
            template = NotificationTemplate.WELCOME,
            recipient = "test@example.com",
            subject = "Welcome",
            body = "<h2>Welcome!</h2>",
            status = NotificationStatus.PENDING,
            metadata = mapOf("name" to "Jan"),
            sentAt = null,
            createdAt = now,
        )
        assertThat(notification.id).isEqualTo(id)
        assertThat(notification.channel).isEqualTo(NotificationChannel.EMAIL)
        assertThat(notification.status).isEqualTo(NotificationStatus.PENDING)
        assertThat(notification.metadata).containsEntry("name", "Jan")
    }

    @Test
    fun `NotificationRequest data class construction`() {
        val req = NotificationRequest(
            partyId = UUID.randomUUID(),
            channel = NotificationChannel.PUSH,
            template = NotificationTemplate.OTP_CODE,
            recipient = "+420123456789",
            variables = mapOf("code" to "123456"),
        )
        assertThat(req.channel).isEqualTo(NotificationChannel.PUSH)
        assertThat(req.variables).containsEntry("code", "123456")
    }
}
