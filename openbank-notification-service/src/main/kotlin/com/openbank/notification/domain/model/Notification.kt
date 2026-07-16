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

    /**
     * ADR-0176 D2: the first operator-composable catalogue template. Deliberately has its
     * OWN [com.openbank.notification.application.NotificationConsumer.renderTemplate] case
     * (fixed HTML skeleton, one server-validated variable) rather than falling through to
     * the generic `else` branch, which does verbatim, unescaped interpolation of the caller
     * -supplied `variables` map — building the operator-compose path on that branch would
     * smuggle back exactly the free-text injection D2 exists to prevent (tracked
     * separately as issue #1325, not fixed by this template's addition).
     */
    OPERATOR_ACCOUNT_NOTICE,
}

/**
 * ADR-0176 D3: governs what [com.openbank.notification.application.NotificationConsumer.sendPush]
 * puts in the push payload. `FULL` is today's existing behaviour for every system-triggered
 * template (unchanged, so no existing caller needs to opt in). `WAKE_SIGNAL_ONLY` — used
 * exclusively by the operator-message compose path — carries no body text, only a generic
 * title and the notification id, so the customer app fetches detail on tap via an
 * authenticated `GET /api/v1/notifications/{id}` (ADR-0135 §3, violated today by the `FULL`
 * path for `TRANSACTION_COMPLETED`; retrofitting existing templates onto this shape is a
 * separate, deferred piece of work — this enum only closes the gap for the NEW path).
 */
enum class PushContentPolicy { FULL, WAKE_SIGNAL_ONLY }

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
    val pushContentPolicy: PushContentPolicy = PushContentPolicy.FULL,
)
