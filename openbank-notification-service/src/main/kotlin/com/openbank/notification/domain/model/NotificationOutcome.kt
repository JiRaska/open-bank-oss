// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The terminal fate of one notification request (ADR-0239 D2).
 *
 * Deliberately its own enum rather than a reuse of [NotificationStatus]. The two vocabularies are
 * NOT the same set and never were: [NotificationStatus] carries `PENDING` (which is not an outcome,
 * it is the absence of one) and lacks `SUPPRESSED` — even though `SUPPRESSED` is a string the
 * consumer has always written to `notifications.status`. Reusing it would have published `PENDING`
 * as an outcome and made `SUPPRESSED` unrepresentable, so the published contract gets the vocabulary
 * that is true of it.
 *
 * `BOUNCED` has no producer today (nothing ingests bounces), and is declared anyway because ADR-0239
 * D4 gives it a defined meaning for consumers — a later refinement of an earlier `SENT`. A consumer
 * written now must already tolerate it; adding it later would be the breaking change.
 */
enum class NotificationOutcome { SENT, SUPPRESSED, FAILED, BOUNCED }

/**
 * The `openbank.notification.outcomes.v1` payload (ADR-0239 D2).
 *
 * Emitted through the transactional outbox in the SAME transaction as the status write it reports,
 * so a published outcome always corresponds to a committed row — the point of ADR-0003. It is
 * emitted for EVERY terminal transition, correlated or not: the topic is a shared contract, not a
 * private channel back to campaign-service.
 *
 * [reason] distinguishes causes that a status alone flattens — `no_active_consent` (a GDPR control
 * working) and `consent_check_unavailable` (an availability problem) must never merge into one
 * number, which is the same distinction ADR-0198 D4 already draws in the audit trail.
 *
 * Carries no rendered body, no subject and no recipient address: an outcome event says what happened
 * to a message, never what was in it. [template] is a catalogue constant, not customer data.
 */
data class NotificationOutcomeEvent(
    val notificationId: UUID,
    val correlationId: UUID?,
    val partyId: UUID,
    val channel: NotificationChannel,
    val template: NotificationTemplate,
    val outcome: NotificationOutcome,
    val reason: String?,
    val occurredAt: Instant,
) {
    companion object {
        /** `eventType` on the outbox row and the `ce-type`-style header the dispatcher stamps. */
        const val EVENT_TYPE: String = "NotificationOutcome"

        /** Reason codes this service can currently produce. Additive — a consumer must not switch exhaustively. */
        const val REASON_NO_CONSENT: String = "no_active_consent"
        const val REASON_CONSENT_UNAVAILABLE: String = "consent_check_unavailable"
        const val REASON_NO_RECIPIENT: String = "no_deliverable_recipient"
        const val REASON_MAILER_REFUSED: String = "mailer_refused"
        const val REASON_NO_DEVICE: String = "no_active_device"
        const val REASON_PUSH_REJECTED: String = "push_rejected_by_provider"
        const val REASON_PREFERENCE_MUTED: String = "channel_muted_by_preference"
    }
}
