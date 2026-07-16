// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import java.time.Instant
import java.util.UUID

enum class NotificationChannel { EMAIL, SMS, PUSH, IN_APP }
enum class NotificationStatus { PENDING, SENT, FAILED, BOUNCED }

/**
 * A message template and — inseparably — the complete set of variables it accepts.
 *
 * The schema is a constructor argument rather than a lookup table beside the enum, and that is
 * the whole point: a new constant cannot be added without declaring its variables, because the
 * compiler demands the argument. A side table can silently gain a constant it has no entry for;
 * this cannot (ADR-0176 D1).
 *
 * [variables] is a CLOSED set. `NotificationConsumer` rejects a request carrying any key not
 * listed here, which is what stops a secret-shaped variable riding an ordinary template into
 * storage — `ACCOUNT_FROZEN` with a `code` variable used to render and persist that code in
 * cleartext (issue #1325). Secrecy is a property of the variables, not of the template, so the
 * defence has to live here and not only in [TemplateSensitivity].
 *
 * Keep this in step with `NotificationConsumer.renderTemplate`: a variable declared but never
 * rendered is dead, and a variable rendered but not declared cannot arrive.
 */
enum class NotificationTemplate(val variables: Set<String>) {
    ACCOUNT_OPENED(setOf("accountNumber")),
    ACCOUNT_CLOSED(setOf("accountNumber")),
    ACCOUNT_FROZEN(setOf("accountNumber", "reason")),
    TRANSACTION_COMPLETED(setOf("amount", "currency")),
    TRANSACTION_FAILED(setOf("amount", "currency", "reason")),
    KYC_APPROVED(emptySet()),
    KYC_REJECTED(setOf("reason")),
    KYC_DOCUMENT_REQUIRED(setOf("documentType")),
    CONSENT_GRANTED(setOf("scope")),
    CONSENT_REVOKED(setOf("scope")),
    OTP_CODE(setOf("code")),
    PASSWORD_RESET(setOf("resetLink")),
    WELCOME(setOf("name")),
    ;

    /** Keys in [vars] that this template does not accept. Empty = the request is well-formed. */
    fun unknownVariables(vars: Map<String, String>): Set<String> = vars.keys - variables
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
