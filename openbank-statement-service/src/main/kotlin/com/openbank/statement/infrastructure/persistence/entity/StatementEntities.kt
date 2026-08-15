// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import com.openbank.statement.domain.model.PeriodCloseStatus
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The retained period-close record (ADR-0035 §F.1) — the ONLY persisted statement artefact. No
 * rendered camt/MT/PDF bytes are stored anywhere; renders are produced on demand and discarded.
 */
@Entity
@Table(name = "statement_period")
class StatementPeriodEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "pocket_currency", nullable = false, length = 3)
    lateinit var pocketCurrency: String

    @Column(name = "period_from", nullable = false)
    lateinit var periodFrom: LocalDate

    @Column(name = "period_to", nullable = false)
    lateinit var periodTo: LocalDate

    @Column(name = "legal_sequence_number", nullable = false)
    var legalSequenceNumber: Long = 0

    @Column(name = "electronic_sequence_number", nullable = false)
    var electronicSequenceNumber: Long = 0

    @Column(name = "opening_balance", nullable = false, precision = 23, scale = 4)
    lateinit var openingBalance: BigDecimal

    @Column(name = "closing_balance", nullable = false, precision = 23, scale = 4)
    lateinit var closingBalance: BigDecimal

    @Column(name = "entry_count", nullable = false)
    var entryCount: Int = 0

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    lateinit var status: PeriodCloseStatus

    @Column(name = "supersedes_sequence")
    var supersedesSequence: Long? = null

    @Column(name = "closed_at", nullable = false)
    lateinit var closedAt: Instant

    /**
     * JSON of the frozen render inputs (`StatementSnapshot`: iban, holderName, entries) — #3986.
     * Serialised/parsed by `StatementMapper`, never by the domain.
     *
     * NULL for every period closed before V7, and it must stay nullable: those rows cannot be
     * backfilled from live data without freezing whatever drift has already occurred.
     */
    @Column(name = "model_snapshot")
    var modelSnapshot: String? = null
}

/**
 * Transactional outbox for `account.statement.period.closed` (ADR-0049 D3).
 *
 * `claimed_at` is statement-only — added straight on this entity, not the shared
 * [PanacheOutboxEntity] (mapped by every outbox-bearing service — a shared-entity migration
 * would need every service migrated in lockstep). Stamped by
 * `StatementOutboxRepositoryImpl.claimProcessable`'s atomic claim query on DISPATCHING; read
 * back by the same query to decide if a DISPATCHING row is stale enough to reclaim.
 */
@Entity
@Table(name = "statement_outbox")
class StatementOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
