// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import java.util.UUID

/**
 * What a participant is told about their contract (ADR-0334, #12379). Each kind declares its
 * CLOSED variable set, the same discipline notification-service applies to its own templates
 * (ADR-0176 D1): a kind cannot be sent with a variable it does not declare, so no IBAN, name or
 * free text can ride a notice by accident. Only the last four IBAN characters ever appear.
 */
enum class ParticipantNotificationKind(val template: String, val variables: Set<String>) {
    /** Security notice: a payout account change was signed and is held until [effectiveFrom]. */
    PAYOUT_ACCOUNT_CHANGED("PENSION_PAYOUT_ACCOUNT_CHANGED", setOf("contractId", "accountLast4", "effectiveFrom")),

    /** A payout (lump sum, installment, early termination) was handed to the payment rail. */
    PAYOUT_EXECUTED("PENSION_PAYOUT_EXECUTED", setOf("contractId", "purpose", "amount", "currency", "accountLast4")),

    /** An investment-strategy election was accepted and takes effect on [effectiveFrom]. */
    STRATEGY_CHANGE_EFFECTIVE(
        "PENSION_STRATEGY_CHANGE_EFFECTIVE",
        setOf("contractId", "strategyCode", "effectiveFrom"),
    ),

    /** A provider transfer reached a status the participant must know about. */
    TRANSFER_STATUS("PENSION_TRANSFER_STATUS", setOf("contractId", "direction", "status")),

    /** A state incentive was received and credited. */
    INCENTIVE_RECEIVED("PENSION_INCENTIVE_RECEIVED", setOf("contractId", "period", "amount", "currency")),

    /** A received state incentive was returned to the agency. */
    INCENTIVE_RETURNED("PENSION_INCENTIVE_RETURNED", setOf("contractId", "period", "amount", "currency")),
}

data class ParticipantNotification(
    val partyId: UUID,
    val kind: ParticipantNotificationKind,
    val variables: Map<String, String>,
) {
    init {
        val unknown = variables.keys - kind.variables
        require(unknown.isEmpty()) { "${kind.name} does not declare variables $unknown" }
        val missing = kind.variables - variables.keys
        require(missing.isEmpty()) { "${kind.name} is missing variables $missing" }
    }
}

/**
 * The outcome of handing a notice to notification-service. Three DISTINCT values, never a boolean:
 * [SKIPPED] (the channel is switched off here) must not read as [ENQUEUED] — the
 * `PushResult.skipped()` defect (#4348) — and neither means DELIVERED: notification-service decides
 * delivery, and this service cannot observe it. Metrics are named for what is established.
 */
enum class NotificationDispatch { ENQUEUED, SKIPPED, FAILED }

/**
 * notification-service, by its request topic. Never throws: an informational notice must not undo
 * the business step that caused it, so a failure is returned as [NotificationDispatch.FAILED] and
 * counted. A caller whose step REQUIRES the notice (a payout-account change) must check for
 * [NotificationDispatch.ENQUEUED] itself.
 */
fun interface ParticipantNotifier {
    suspend fun send(notification: ParticipantNotification): NotificationDispatch
}
