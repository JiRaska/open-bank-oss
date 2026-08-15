// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import java.time.Instant
import java.util.UUID

/** Push transport. Selects which adapter delivers a registered device token. */
enum class PushPlatform { FCM, APNS }

/**
 * Lifecycle of a registered device token.
 * - ACTIVE   — eligible for fan-out delivery.
 * - INACTIVE — explicitly retired (logout / un-register) or swept by the nightly TTL job (lastUsedAt < 90d).
 * - INVALID  — the provider rejected the token (UNREGISTERED / INVALID_TOKEN); never retried.
 */
enum class DeviceTokenStatus { ACTIVE, INACTIVE, INVALID }

/**
 * A push-capable device belonging to a party. The token is provider-issued (FCM
 * registration token / APNs device token); it is PII-adjacent and is masked before
 * it ever reaches a log line (see PiiMask in openbank-libs).
 */
data class DeviceToken(
    val id: UUID,
    val partyId: UUID,
    val appInstance: String,
    val platform: PushPlatform,
    val token: String,
    val appVersion: String?,
    val osVersion: String?,
    val status: DeviceTokenStatus,
    val lastUsedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Inbound registration command (REST → registry). partyId is authoritative from the edge JWT, never the body. */
data class DeviceRegistration(
    val partyId: UUID,
    val appInstance: String,
    val platform: PushPlatform,
    val token: String,
    val appVersion: String? = null,
    val osVersion: String? = null,
)

/**
 * What actually happened to one push send — the three states [PushResult] can be in, named so
 * they cannot be confused with each other (ADR-0252 phase 0).
 *
 * The distinction that matters is [ACCEPTED] versus [SKIPPED]. Both are `success = true`, and
 * reading only that flag is how a *disabled* adapter became indistinguishable from a working one:
 * every push in an environment without APNs credentials was counted as delivered and the
 * notification stored as SENT, with no egress and no error anywhere.
 */
enum class PushSendOutcome {
    /**
     * The provider accepted the request. **Not** proof of delivery — APNs returns HTTP 200 to mean
     * accepted for delivery and issues no receipt. Device-side acknowledgement is ADR-0252 phase 3
     * (#4348).
     */
    ACCEPTED,

    /** The adapter is disabled, so nothing left this process. A no-op, not a delivery. */
    SKIPPED,

    /** The provider rejected the send, or the call failed. */
    FAILED,
}

/**
 * Outcome of a single push send. `skipped` means the adapter is disabled (off-by-default
 * sandbox mode) — that is a successful no-op, not a failure, and specifically not a delivery:
 * read [outcome] rather than [success] when the difference matters. `invalidToken` flags a
 * provider rejection that should retire the device token from future fan-out.
 */
data class PushResult(
    val success: Boolean,
    val skipped: Boolean = false,
    val invalidToken: Boolean = false,
    val messageId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    /** The three-state view of this result. Collapsing it to [success] merges ACCEPTED with SKIPPED. */
    val outcome: PushSendOutcome
        get() = when {
            !success -> PushSendOutcome.FAILED
            skipped -> PushSendOutcome.SKIPPED
            else -> PushSendOutcome.ACCEPTED
        }

    companion object {
        fun ok(messageId: String?): PushResult = PushResult(success = true, messageId = messageId)

        fun skipped(reason: String): PushResult = PushResult(success = true, skipped = true, errorMessage = reason)

        fun failed(code: String?, message: String?, invalidToken: Boolean = false): PushResult =
            PushResult(success = false, invalidToken = invalidToken, errorCode = code, errorMessage = message)
    }
}
