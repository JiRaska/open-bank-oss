// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import java.time.Instant
import java.util.UUID

/** ADR-0176 D6: fixes the lawful basis. MARKETING is refused at the compose endpoint, always. */
enum class OperatorMessagePurpose { SERVICE, LEGAL, MARKETING }

/**
 * No DRAFT state: [com.openbank.libs.authz.AuthorizeInterceptor] short-circuits before the
 * submit handler's method body ever runs on an un-approved first call, so a row is born
 * PENDING_APPROVAL and stays that way until the checker's decision or the maker's approved
 * retry resolves it to REJECTED or SENT.
 */
enum class OperatorMessageStatus { PENDING_APPROVAL, SENT, REJECTED }

/**
 * A draft operator-initiated customer message (ADR-0176), persisted BEFORE the four-eyes gate
 * runs. [ApprovalStore][com.openbank.libs.approval.ApprovalStore] only tracks
 * (action, resourceId, makerId, status) — this row is where the actual content lives, keyed by
 * [id] as the approval's `resourceId`.
 */
data class OperatorMessage(
    val id: UUID,
    val partyId: UUID,
    val template: NotificationTemplate,
    val referenceId: String,
    val purpose: OperatorMessagePurpose,
    val status: OperatorMessageStatus,
    val makerId: String,
    val createdAt: Instant,
)
