// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.domain.event

import com.openbank.libs.domain.event.DomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Emitted (via the transactional outbox, same transaction as the DRAFT→ATTESTED flip) when a
 * fiscal-year close is attested (ADR-0078 D5, increment 1). A NEW event type on the existing
 * ledger event stream — additive, therefore backward compatible. [aggregateId] is the
 * YearCloseRecord id; [contentHash] anchors the attested trial balance for audit evidence.
 */
data class YearCloseAttestedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val fiscalYear: Int,
    val contentHash: String,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val accountCount: Int,
    val attestedBy: String,
    val attestedAt: Instant,
) : DomainEvent() {
    override val aggregateType = "YearClose"
    override val eventType = "YearCloseAttested"
}
