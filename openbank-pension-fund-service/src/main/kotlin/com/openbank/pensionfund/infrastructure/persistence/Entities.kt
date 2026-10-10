// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * EVERY column is named explicitly. This service sets no physical naming strategy, so Hibernate's
 * implicit name would be the property name verbatim (createdAt -> createdat), which no migration
 * creates (entity-column-names gate).
 *
 * Ids are application-assigned UUIDs, so the store writes with session.merge, never persist:
 * persist on an assigned id is INSERT-only and fails every update with a duplicate key.
 */

@Entity
@Table(name = "funds")
class FundEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "name", nullable = false)
    lateinit var name: String

    @Column(name = "isin", nullable = false)
    lateinit var isin: String

    @Column(name = "lei", nullable = false)
    lateinit var lei: String

    @Column(name = "depositary_reference", nullable = false)
    lateinit var depositaryReference: String

    @Column(name = "custody_account_reference", nullable = false)
    lateinit var custodyAccountReference: String

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "risk_class", nullable = false)
    var riskClass: Short = 0

    @Column(name = "mandatory_conservative", nullable = false)
    var mandatoryConservative: Boolean = false

    @Column(name = "management_fee_rate", nullable = false)
    lateinit var managementFeeRate: BigDecimal

    @Column(name = "launch_nav_per_unit", nullable = false)
    lateinit var launchNavPerUnit: BigDecimal

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "fund_strategies")
class FundStrategyEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "name", nullable = false)
    lateinit var name: String

    @Column(name = "allocations", nullable = false)
    lateinit var allocations: String

    @Column(name = "glide_path", nullable = false)
    lateinit var glidePath: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "version", nullable = false)
    var version: Int = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "strategy_changes")
class StrategyChangeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "strategy_id", nullable = false)
    lateinit var strategyId: UUID

    @Column(name = "proposed_allocations", nullable = false)
    lateinit var proposedAllocations: String

    @Column(name = "proposed_glide_path", nullable = false)
    lateinit var proposedGlidePath: String

    @Column(name = "reason", nullable = false)
    lateinit var reason: String

    @Column(name = "effective_date", nullable = false)
    lateinit var effectiveDate: LocalDate

    @Column(name = "submitted_by", nullable = false)
    lateinit var submittedBy: String

    @Column(name = "submitted_at", nullable = false)
    lateinit var submittedAt: Instant

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "participant_notification_date")
    var participantNotificationDate: LocalDate? = null

    @Column(name = "applied_at")
    var appliedAt: Instant? = null
}

@Entity
@Table(name = "fund_navs")
class FundNavEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "fund_id", nullable = false)
    lateinit var fundId: UUID

    @Column(name = "valuation_date", nullable = false)
    lateinit var valuationDate: LocalDate

    @Column(name = "gross_assets", nullable = false)
    lateinit var grossAssets: BigDecimal

    @Column(name = "accrued_management_fee", nullable = false)
    lateinit var accruedManagementFee: BigDecimal

    @Column(name = "other_liabilities", nullable = false)
    lateinit var otherLiabilities: BigDecimal

    @Column(name = "net_assets", nullable = false)
    lateinit var netAssets: BigDecimal

    @Column(name = "units_outstanding", nullable = false)
    lateinit var unitsOutstanding: BigDecimal

    @Column(name = "nav_per_unit", nullable = false)
    lateinit var navPerUnit: BigDecimal

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "calculated_by", nullable = false)
    lateinit var calculatedBy: String

    @Column(name = "calculated_at", nullable = false)
    lateinit var calculatedAt: Instant

    @Column(name = "corrects_nav_id")
    var correctsNavId: UUID? = null

    @Column(name = "approved_by")
    var approvedBy: String? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Column(name = "positions_recorded", nullable = false)
    var positionsRecorded: Boolean = false
}

@Entity
@Table(name = "fund_nav_positions")
class FundNavPositionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "nav_id", nullable = false)
    lateinit var navId: UUID

    @Column(name = "instrument_id", nullable = false)
    lateinit var instrumentId: String

    @Column(name = "quantity", nullable = false)
    lateinit var quantity: BigDecimal

    @Column(name = "price", nullable = false)
    lateinit var price: BigDecimal

    @Column(name = "instrument_class", nullable = false)
    lateinit var instrumentClass: String
}

@Entity
@Table(name = "position_classification_corrections")
class PositionClassificationCorrectionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "position_id", nullable = false)
    lateinit var positionId: UUID

    @Column(name = "nav_id", nullable = false)
    lateinit var navId: UUID

    @Column(name = "from_class", nullable = false)
    lateinit var fromClass: String

    @Column(name = "to_class", nullable = false)
    lateinit var toClass: String

    @Column(name = "reason", nullable = false)
    lateinit var reason: String

    @Column(name = "proposed_by", nullable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null
}

@Entity
@Table(name = "unit_orders")
class UnitOrderEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "fund_id", nullable = false)
    lateinit var fundId: UUID

    @Column(name = "order_type", nullable = false)
    lateinit var orderType: String

    @Column(name = "amount")
    var amount: BigDecimal? = null

    @Column(name = "units")
    var units: BigDecimal? = null

    @Column(name = "target_fund_id")
    var targetFundId: UUID? = null

    @Column(name = "parent_order_id")
    var parentOrderId: UUID? = null

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "placed_at", nullable = false)
    lateinit var placedAt: Instant

    @Column(name = "settled_at")
    var settledAt: Instant? = null

    @Column(name = "nav_id")
    var navId: UUID? = null

    @Column(name = "idempotency_key")
    var idempotencyKey: String? = null
}

@Entity
@Table(name = "unit_holdings")
class UnitHoldingEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "fund_id", nullable = false)
    lateinit var fundId: UUID

    @Column(name = "units", nullable = false)
    lateinit var units: BigDecimal

    /**
     * Optimistic lock. A merge carrying a version older than the row's fails the whole commit
     * (409), so two NAV publications or corrections cannot both apply a delta to one stale read.
     * Null on a holding never stored, which makes merge insert it.
     */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
}

@Entity
@Table(name = "unit_transactions")
class UnitTransactionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "order_id")
    var orderId: UUID? = null

    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "fund_id", nullable = false)
    lateinit var fundId: UUID

    @Column(name = "transaction_type", nullable = false)
    lateinit var transactionType: String

    @Column(name = "units", nullable = false)
    lateinit var units: BigDecimal

    @Column(name = "amount", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "nav_id", nullable = false)
    lateinit var navId: UUID

    @Column(name = "nav_per_unit", nullable = false)
    lateinit var navPerUnit: BigDecimal

    @Column(name = "priced_at", nullable = false)
    lateinit var pricedAt: Instant

    @Column(name = "corrected_from_nav_id")
    var correctedFromNavId: UUID? = null
}

@Entity
@Table(name = "classification_request_receipts")
class ClassificationReceiptEntity : PanacheEntityBase {
    @Id
    @Column(name = "receipt_key", nullable = false)
    lateinit var key: String

    @Column(name = "fingerprint", nullable = false)
    lateinit var fingerprint: String

    @Column(name = "response_snapshot", nullable = false, columnDefinition = "text")
    lateinit var responseSnapshot: String
}
