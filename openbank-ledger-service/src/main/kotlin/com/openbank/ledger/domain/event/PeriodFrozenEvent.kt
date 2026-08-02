// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.event

import com.openbank.libs.domain.event.DomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Emitted when a statutory period is frozen (ADR-0096 D1), via the transactional outbox in the
 * SAME transaction as the DRAFT→FROZEN flip.
 *
 * Downstream consumers — statement rendering, regulatory reporting, the admin-ui regulatory page —
 * react to this rather than polling ledger-service, for the same reason accounting-day state is
 * published rather than served (ADR-0207 D4): a synchronous dependency on ledger-service from
 * every reporting path makes it a hard availability dependency of all of them.
 *
 * [contentHash] is the attestation anchor: the SHA-256 of the canonical trial-balance JSON. A
 * consumer that stores it can later prove the artefact it rendered from is the one that was sealed.
 *
 * A NEW event type on the existing ledger stream — additive, therefore backward compatible.
 */
data class PeriodFrozenEvent(
    override val aggregateId: UUID,
    override val version: Long,
    override val occurredAt: Instant,
    val periodLabel: String,
    val periodType: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val contentHash: String,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val accountCount: Int,
    val frozenBy: String,
    val frozenAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "ClosedPeriod"
    override val eventType = "PeriodFrozen"
}
