// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.out.NotificationDispatch
import com.openbank.pension.application.port.out.ParticipantNotification
import com.openbank.pension.application.port.out.ParticipantNotificationKind
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * The informational participant notices (#12379) and how they are sent: AFTER the business step is
 * stored, and never able to undo it. A notice that is not ENQUEUED is logged with its outcome (the
 * notifier also counts it); it is never reported as sent. The payout-account-change notice is NOT
 * here: that one is a precondition of the change and is enforced by its own port.
 */
object ParticipantNotices {
    private val log = Logger.getLogger(ParticipantNotices::class.java)

    /** Transfer statuses the participant hears about: the outcome, never every internal step. */
    val NOTIFIED_TRANSFER_STATUSES = setOf(
        TransferStatus.ACCEPTED,
        TransferStatus.COMPLETED,
        TransferStatus.REJECTED,
        TransferStatus.TIMED_OUT,
        TransferStatus.CANCELLED,
        TransferStatus.FAILED,
    )

    suspend fun send(notifier: ParticipantNotifier, notification: ParticipantNotification): NotificationDispatch {
        val outcome = notifier.send(notification)
        if (outcome != NotificationDispatch.ENQUEUED) {
            log.warnf("participant notice %s for party %s: %s", notification.kind, notification.partyId, outcome)
        }
        return outcome
    }

    fun payoutExecuted(
        partyId: UUID,
        contractId: UUID,
        purpose: String,
        amount: BigDecimal,
        currency: String,
        iban: String,
    ) = ParticipantNotification(
        partyId,
        ParticipantNotificationKind.PAYOUT_EXECUTED,
        mapOf(
            "contractId" to contractId.toString(),
            "purpose" to purpose,
            "amount" to amount.toPlainString(),
            "currency" to currency,
            "accountLast4" to iban.takeLast(LAST4),
        ),
    )

    fun strategyChange(partyId: UUID, contractId: UUID, strategyCode: String, effectiveFrom: LocalDate) =
        ParticipantNotification(
            partyId,
            ParticipantNotificationKind.STRATEGY_CHANGE_EFFECTIVE,
            mapOf(
                "contractId" to contractId.toString(),
                "strategyCode" to strategyCode,
                "effectiveFrom" to effectiveFrom.toString(),
            ),
        )

    fun transferStatus(transfer: TransferRequest) = ParticipantNotification(
        transfer.partyId,
        ParticipantNotificationKind.TRANSFER_STATUS,
        mapOf(
            "contractId" to transfer.contractId.toString(),
            "direction" to transfer.direction.name,
            "status" to transfer.status.name,
        ),
    )

    fun incentive(
        kind: ParticipantNotificationKind,
        partyId: UUID,
        contractId: UUID,
        period: YearMonth,
        amount: BigDecimal,
        currency: String,
    ): ParticipantNotification {
        require(
            kind == ParticipantNotificationKind.INCENTIVE_RECEIVED ||
                kind == ParticipantNotificationKind.INCENTIVE_RETURNED,
        )
        return ParticipantNotification(
            partyId,
            kind,
            mapOf(
                "contractId" to contractId.toString(),
                "period" to period.toString(),
                "amount" to amount.toPlainString(),
                "currency" to currency,
            ),
        )
    }

    private const val LAST4 = 4
}
