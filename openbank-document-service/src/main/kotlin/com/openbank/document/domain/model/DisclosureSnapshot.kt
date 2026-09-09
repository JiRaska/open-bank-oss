// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Immutable copy of one completed PDF, issued for a single external-disclosure workflow.
 *
 * The source id is evidence, never a retrieval pointer for the recipient: redemption reads only
 * [storageKey]. [sourceSha256] and [sha256] make both the source observed at issuance and the exact
 * emitted bytes independently auditable.
 */
data class DisclosureSnapshot(
    val id: UUID,
    val requestId: UUID,
    val sourceDocumentId: UUID,
    val partyRef: String,
    val sourceSha256: String,
    val sha256: String,
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)
