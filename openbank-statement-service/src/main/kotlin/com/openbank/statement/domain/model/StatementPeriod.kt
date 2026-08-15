// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The **frozen render inputs** of a closed period — everything a [StatementModel] needs that is not
 * already a column on [StatementPeriod] (issue #3986).
 *
 * Captured once, at close, from the same reads the close already performed, and never refreshed.
 * It exists because those inputs are *live projections* the rest of the platform keeps writing to:
 * the booked-entry list can gain a late entry whose `bookingDate` falls inside the already-closed
 * window, and the account identity can be rewritten by a holder rename or an IBAN change. Replaying
 * them at render time made two renders of the same `legalSequenceNumber` return different documents,
 * which ADR-0035 §D/§F promise cannot happen.
 *
 * **This is not "storing the statement".** ADR-0035 §F stores no camt/MT/PDF bytes and this changes
 * nothing about that — it stores the canonical *model*, which the ADR's own "Alternatives
 * considered" already chose ("persist the canonical model, render on demand"). Every format is still
 * projected on demand, and a correction is still a new sequence with its own snapshot (§D).
 */
data class StatementSnapshot(val iban: String, val holderName: String, val entries: List<StatementEntry>)

/**
 * The retained period-close record (ADR-0035 §F.1). This is the ONLY thing persisted per statement
 * period — small metadata plus the legal/electronic sequence, balance anchors and the frozen
 * [snapshot] of the period's render inputs. **No camt/MT/PDF bytes are stored**: renders are
 * produced on demand from this record and discarded. Retention (10y, ČNB) is on this reproducible
 * record, not on any rendered artefact.
 *
 * [snapshot] is nullable for exactly one reason: periods closed before #3986 landed have none.
 * Those fall back to replaying the live projections, i.e. to the old, non-deterministic behaviour —
 * a snapshot cannot be manufactured for them retroactively, because the live data may already have
 * drifted away from what was issued and freezing today's answer would make the drift canonical.
 */
data class StatementPeriod(
    val id: UUID,
    val accountId: UUID,
    val pocketCurrency: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val legalSequenceNumber: Long,
    val electronicSequenceNumber: Long,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val entryCount: Int,
    val closedAt: Instant,
    val status: PeriodCloseStatus = PeriodCloseStatus.CLOSED,
    val supersedesSequence: Long? = null,
    val snapshot: StatementSnapshot? = null,
)
