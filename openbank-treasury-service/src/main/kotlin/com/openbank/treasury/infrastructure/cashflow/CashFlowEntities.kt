// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.cashflow

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/* Every column named explicitly (entity-column-names gate): this service sets no naming strategy. */

@Entity
@Table(name = "deal_cash_flows")
class CashFlowEntity : PanacheEntity() {
    @Column(name = "flow_id", nullable = false, unique = true)
    lateinit var flowId: UUID

    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "schedule_version", nullable = false)
    var scheduleVersion: Int = 0

    @Column(name = "leg_index", nullable = false)
    var legIndex: Int = 0

    @Column(name = "period_index", nullable = false)
    var periodIndex: Int = 0

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "direction", nullable = false)
    lateinit var direction: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "accrual_start")
    var accrualStart: LocalDate? = null

    @Column(name = "accrual_end")
    var accrualEnd: LocalDate? = null

    @Column(name = "fixing_date")
    var fixingDate: LocalDate? = null

    @Column(name = "payment_date", nullable = false)
    lateinit var paymentDate: LocalDate

    @Column(name = "notional")
    var notional: BigDecimal? = null

    @Column(name = "rate")
    var rate: BigDecimal? = null

    @Column(name = "rate_source", nullable = false)
    lateinit var rateSource: String

    @Column(name = "amount")
    var amount: BigDecimal? = null

    @Column(name = "state", nullable = false)
    lateinit var state: String

    @Column(name = "leg_hash", nullable = false)
    lateinit var legHash: String

    @Column(name = "manual", nullable = false)
    var manual: Boolean = false

    @Column(name = "original_payment_date")
    var originalPaymentDate: LocalDate? = null

    @Column(name = "original_amount")
    var originalAmount: BigDecimal? = null

    @Column(name = "override_id")
    var overrideId: UUID? = null

    @Column(name = "payment_id")
    var paymentId: String? = null

    /** Optimistic lock (V19): a stale copy of the row fails at flush instead of overwriting it. */
    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0
}

@Entity
@Table(name = "cash_flow_overrides")
class CashFlowOverrideEntity : PanacheEntity() {
    @Column(name = "override_id", nullable = false, unique = true)
    lateinit var overrideId: UUID

    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "flow_id", nullable = false)
    lateinit var flowId: UUID

    @Column(name = "leg_index", nullable = false)
    var legIndex: Int = 0

    @Column(name = "period_index", nullable = false)
    var periodIndex: Int = 0

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "leg_hash", nullable = false)
    lateinit var legHash: String

    @Column(name = "payment_date")
    var paymentDate: LocalDate? = null

    @Column(name = "amount")
    var amount: BigDecimal? = null

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    lateinit var reason: String

    @Column(name = "proposed_by", nullable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_by_type", nullable = false)
    lateinit var proposedByType: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_by_type")
    var decidedByType: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    var decisionReason: String? = null

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0
}

@Entity
@Table(name = "cash_flow_conflicts")
class CashFlowConflictEntity : PanacheEntity() {
    @Column(name = "conflict_id", nullable = false, unique = true)
    lateinit var conflictId: UUID

    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "override_id", nullable = false)
    lateinit var overrideId: UUID

    @Column(name = "leg_index", nullable = false)
    var legIndex: Int = 0

    @Column(name = "period_index", nullable = false)
    var periodIndex: Int = 0

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "from_schedule_version", nullable = false)
    var fromScheduleVersion: Int = 0

    @Column(name = "to_schedule_version", nullable = false)
    var toScheduleVersion: Int = 0

    @Column(name = "detected_at", nullable = false)
    lateinit var detectedAt: Instant

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    lateinit var reason: String
}

@Entity
@Table(name = "deal_versions")
class DealVersionEntity : PanacheEntity() {
    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "version", nullable = false)
    var version: Int = 0

    @Column(name = "terms_json", nullable = false, columnDefinition = "TEXT")
    lateinit var termsJson: String

    @Column(name = "schedule_version")
    var scheduleVersion: Int? = null

    @Column(name = "schedule_hash")
    var scheduleHash: String? = null

    @Column(name = "created_by", nullable = false)
    lateinit var createdBy: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "changes", columnDefinition = "TEXT")
    var changes: String? = null
}
