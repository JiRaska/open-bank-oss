// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sca.domain.model

import java.time.OffsetDateTime
import java.util.UUID

enum class ScaMethod {
    PUSH_NOTIFICATION,
    SMS_OTP,
    TOTP,
    BIOMETRIC
}

enum class ScaPurpose {
    CONSENT_GRANT,
    PAYMENT_INITIATION,
    LOGIN,
    AGENT_ACTION,
    SENSITIVE_DATA_ACCESS
}

enum class ScaStatus {
    PENDING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED
}

data class ScaChallenge(
    val id: UUID = UUID.randomUUID(),
    val partyId: UUID,
    val purpose: ScaPurpose,
    val method: ScaMethod,
    val status: ScaStatus = ScaStatus.PENDING,
    val expiresAt: OffsetDateTime,
    val completedAt: OffsetDateTime? = null,
    val failedAt: OffsetDateTime? = null,
    val failureReason: String? = null,
    val attemptCount: Int = 0,
    val maxAttempts: Int = 3,
    val dynamicLinkingData: DynamicLinkingData? = null,
    val redirectUrl: String? = null,
    /**
     * Set once the approved challenge has been spent on the operation it authorised
     * (single-use, RTS Art. 5 replay protection). Written atomically by the repository's
     * compare-and-consume update — never twice.
     */
    val consumedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
) {
    fun isExpired(now: OffsetDateTime): Boolean = now.isAfter(expiresAt)
    fun isCompleted(): Boolean = status == ScaStatus.COMPLETED
    fun canAttempt(now: OffsetDateTime): Boolean =
        attemptCount < maxAttempts && status == ScaStatus.PENDING && !isExpired(now)

    fun complete(now: OffsetDateTime): ScaChallenge = copy(
        status = ScaStatus.COMPLETED,
        completedAt = now,
    )

    fun fail(reason: String, now: OffsetDateTime): ScaChallenge = copy(
        status = if (attemptCount + 1 >= maxAttempts) ScaStatus.FAILED else ScaStatus.PENDING,
        attemptCount = attemptCount + 1,
        failedAt = if (attemptCount + 1 >= maxAttempts) now else null,
        failureReason = if (attemptCount + 1 >= maxAttempts) reason else null,
    )
}

data class DynamicLinkingData(
    val amount: String?,
    val currency: String?,
    val creditorIban: String?,
    val creditorName: String?,
    val reference: String?,
) {
    /**
     * Does this signed linking data authorise exactly the operation the caller is about to
     * execute? Amount compares numerically ("250.0" == "250.00"), currency case-insensitively,
     * and the creditor account ignoring spaces/case — what the DEVICE signed must equal what
     * the EDGE forwards, or the consume is refused (RTS Art. 5 dynamic linking).
     */
    fun authorises(amount: String?, currency: String?, creditor: String?): Boolean {
        fun amountEq(a: String?, b: String?): Boolean = when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> runCatching { java.math.BigDecimal(a).compareTo(java.math.BigDecimal(b)) == 0 }.getOrDefault(false)
        }
        fun norm(s: String?) = s?.replace(" ", "")?.uppercase()
        if (!amountEq(this.amount, amount)) return false
        if (norm(this.currency) != norm(currency)) return false
        if (this.creditorIban != null && norm(this.creditorIban) != norm(creditor)) return false
        return true
    }
}
