// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import java.time.Instant
import java.util.UUID

enum class NotificationChannel { EMAIL, SMS, PUSH, IN_APP }
enum class NotificationStatus { PENDING, SENT, FAILED, BOUNCED }
enum class NotificationTemplate {
    ACCOUNT_OPENED,
    ACCOUNT_CLOSED,
    ACCOUNT_FROZEN,
    TRANSACTION_COMPLETED,
    TRANSACTION_FAILED,
    KYC_APPROVED,
    KYC_REJECTED,
    KYC_DOCUMENT_REQUIRED,
    CONSENT_GRANTED,
    CONSENT_REVOKED,
    OTP_CODE,
    PASSWORD_RESET,
    WELCOME,
}

data class Notification(
    val id: UUID,
    val partyId: UUID,
    val channel: NotificationChannel,
    val template: NotificationTemplate,
    val recipient: String,
    val subject: String?,
    val body: String,
    val status: NotificationStatus,
    val metadata: Map<String, String>,
    val sentAt: Instant?,
    val createdAt: Instant,
)

data class NotificationRequest(
    val partyId: UUID,
    val channel: NotificationChannel,
    val template: NotificationTemplate,
    val recipient: String,
    val variables: Map<String, String>,
)
