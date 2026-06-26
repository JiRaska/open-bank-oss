// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.dispute.domain.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Regulatory complaint taxonomy (ADR-0085 §1; EBA/ESMA JC 2018 35). */
enum class ComplaintCategory { PAYMENT_SERVICE, FEES, ACCOUNT_SERVICE, LENDING, CONDUCT, DATA_PROTECTION, OTHER }

/** Intake channel a complaint arrived through. */
enum class ComplaintChannel { APP, BRANCH, EMAIL, ARBITER }

/**
 * Complaint lifecycle. An interim reply does not change the status (the complaint is still
 * RECEIVED/open) — it only extends the statutory deadline to 35 business days (PSD2 Art. 101).
 */
enum class ComplaintStatus { RECEIVED, RESOLVED, CLOSED }

/**
 * The complaints aggregate (ADR-0085 §1). Carries a statutory deadline clock computed at intake
 * ([dueDate]) — see `ComplaintService` for the 15-/35-business-day rules.
 *
 * [breached] is derived (today > dueDate AND not RESOLVED/CLOSED) and is computed against an
 * injected clock at read time, never persisted — see `ComplaintService.withBreach`.
 */
data class Complaint(
    val id: UUID = UUID.randomUUID(),
    val reference: String,
    val category: ComplaintCategory,
    val channel: ComplaintChannel,
    val description: String,
    val status: ComplaintStatus = ComplaintStatus.RECEIVED,
    val accountId: UUID? = null,
    val transactionId: UUID? = null,
    val disputeId: UUID? = null,
    val receivedDate: LocalDate,
    val dueDate: LocalDate,
    val interimReplyAt: OffsetDateTime? = null,
    val interimReplyReason: String? = null,
    val resolvedAt: OffsetDateTime? = null,
    val outcome: String? = null,
    val redressGranted: Boolean? = null,
    val rootCauseCode: String? = null,
    val closedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    /** Derived field, populated on read (today > dueDate AND not RESOLVED/CLOSED). */
    val breached: Boolean = false,
)

data class FileComplaintRequest(
    val category: ComplaintCategory,
    val channel: ComplaintChannel,
    val description: String,
    val accountId: UUID? = null,
    val transactionId: UUID? = null,
    val disputeId: UUID? = null,
)

data class InterimReplyRequest(val reason: String)

data class ResolveComplaintRequest(val outcome: String, val redressGranted: Boolean? = null)

data class CloseComplaintRequest(val outcome: String, val rootCauseCode: String, val redressGranted: Boolean? = null)
