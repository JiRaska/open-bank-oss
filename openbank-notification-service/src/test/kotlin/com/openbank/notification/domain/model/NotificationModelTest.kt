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
        // 22 since #8535 removed KYC_DOCUMENT_REQUIRED: nothing could produce it, because
        // kyc-service had no transition into DOCUMENTS_REQUIRED and no concept of a document type.
        // 21 since #8568 removed PASSWORD_RESET: no password flow exists (passkeys/biometrics only;
        // Keycloak has resetPasswordAllowed=false and no SMTP), so nothing could produce it either.
        assertThat(NotificationTemplate.values()).hasSize(21)
        assertThat(NotificationTemplate.values()).contains(
            NotificationTemplate.ACCOUNT_OPENED,
            NotificationTemplate.OTP_CODE,
            NotificationTemplate.WELCOME,
            NotificationTemplate.SCA_APPROVAL,
            NotificationTemplate.MARKETING_PRODUCT_OFFER,
            NotificationTemplate.DELEGATION_OFFERED,
            NotificationTemplate.DELEGATION_ACCEPTED,
            NotificationTemplate.DELEGATION_DECLINED,
            NotificationTemplate.DELEGATION_REVOKED,
            NotificationTemplate.DELEGATION_SUSPENDED,
            NotificationTemplate.DELEGATION_REINSTATED,
            NotificationTemplate.DELEGATION_RENOUNCED,
            NotificationTemplate.DELEGATION_EXPIRED,
        )
        // SCA_APPROVAL is SECURITY so the #2 push-preference gate never suppresses it.
        assertThat(NotificationTemplate.SCA_APPROVAL.category).isEqualTo(NotificationCategory.SECURITY)
        // ADR-0200/0198: the campaign template is MARKETING so the consent gate always applies.
        assertThat(NotificationTemplate.MARKETING_PRODUCT_OFFER.category).isEqualTo(NotificationCategory.MARKETING)
        // ADR-0232: the whole delegated-access lifecycle is SECURITY — a customer can never mute
        // being told someone else can now act on their account, same as CONSENT_GRANTED/REVOKED.
        assertThat(
            listOf(
                NotificationTemplate.DELEGATION_OFFERED,
                NotificationTemplate.DELEGATION_ACCEPTED,
                NotificationTemplate.DELEGATION_DECLINED,
                NotificationTemplate.DELEGATION_REVOKED,
                NotificationTemplate.DELEGATION_SUSPENDED,
                NotificationTemplate.DELEGATION_REINSTATED,
                NotificationTemplate.DELEGATION_RENOUNCED,
                NotificationTemplate.DELEGATION_EXPIRED,
            ),
        ).allSatisfy { assertThat(it.category).isEqualTo(NotificationCategory.SECURITY) }
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

    @Test
    fun `mobile deep links are an allow-list, not arbitrary URLs`() {
        assertThat(MobileDeepLink.isAllowed("openbank://savings")).isTrue()
        assertThat(MobileDeepLink.isAllowed("https://example.invalid/redirect")).isFalse()
        assertThat(MobileDeepLink.isAllowed("openbank://savings?next=https://evil.invalid")).isFalse()
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/123e4567-e89b-42d3-a456-426614174000")).isTrue()
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/01995e74-19c7-7d79-9b22-63076d7fd321")).isTrue()
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/not-a-uuid")).isFalse()
        assertThat(
            MobileDeepLink.isAllowed(
                "openbank://delegations/123e4567-e89b-42d3-a456-426614174000?next=https://evil.invalid",
            ),
        ).isFalse()
    }

    @Test
    fun `no-device fallback is a closed reviewed template policy`() {
        assertThat(
            NotificationTemplate.entries.filter { it.noDeviceFallbackChannel == NotificationChannel.EMAIL },
        ).containsExactlyInAnyOrder(
            NotificationTemplate.ACCOUNT_FROZEN,
            NotificationTemplate.KYC_REJECTED,
            NotificationTemplate.TRANSACTION_FAILED,
        )
        assertThat(NotificationTemplate.SCA_APPROVAL.noDeviceFallbackChannel).isNull()
        assertThat(NotificationTemplate.OTP_CODE.noDeviceFallbackChannel).isNull()
        assertThat(NotificationTemplate.MARKETING_PRODUCT_OFFER.noDeviceFallbackChannel).isNull()
        assertThat(NotificationTemplate.TRANSACTION_COMPLETED.noDeviceFallbackChannel).isNull()
    }
}
