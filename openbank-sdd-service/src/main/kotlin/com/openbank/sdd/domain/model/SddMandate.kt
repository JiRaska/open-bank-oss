// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** SEPA Direct Debit scheme (EPC016-06 Core vs EPC222-07 B2B). */
enum class SddScheme { CORE, B2B }

/** Mandate/collection sequence type per the rulebooks. */
enum class SequenceType { OOFF, FRST, RCUR, FNAL }

/**
 * Debtor-mandate lifecycle (ADR-0036 §B). `PENDING_CONFIRMATION` exists for B2B, where the debtor
 * bank must confirm the mandate before it can authorise a collection; Core mandates may be born
 * `ACTIVE`. `CANCELLED`/`EXPIRED` are terminal.
 */
enum class MandateStatus { PENDING_CONFIRMATION, ACTIVE, SUSPENDED, CANCELLED, EXPIRED }

/** A recorded amendment, surfaced as the `AMDT` marker the next collection must carry. */
data class MandateAmendment(
    val field: String,
    val oldValue: String,
    val newValue: String,
    val at: Instant,
)

/**
 * The debtor-side SEPA Direct Debit mandate aggregate (ADR-0036 §A) — system of record for the
 * standing authorisation a customer (debtor) gives a creditor to collect from one EUR pocket.
 * Identity is the rulebook pair `(creditorIdentifier, umr)`.
 */
data class SddMandate(
    val id: UUID,
    val accountId: UUID,
    val debtorIban: String,
    val creditorIdentifier: String,
    val umr: String,
    val scheme: SddScheme,
    val sequenceType: SequenceType,
    val creditorName: String,
    val debtorName: String,
    val signatureDate: LocalDate,
    val status: MandateStatus,
    val b2bConfirmed: Boolean,
    val lastCollectionDate: LocalDate?,
    val lastPreNotificationDate: LocalDate?,
    val createdAt: Instant,
    val amendments: List<MandateAmendment> = emptyList(),
) {
    val isTerminal: Boolean get() = status == MandateStatus.CANCELLED || status == MandateStatus.EXPIRED
}
