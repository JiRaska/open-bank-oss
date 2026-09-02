// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.entity

import com.openbank.interest.domain.model.*
import com.openbank.interest.domain.tax.TaxResidency
import com.openbank.interest.domain.tax.TaxpayerType
import com.openbank.interest.domain.tax.WithholdingRemittanceStatus
import com.openbank.interest.domain.tax.WithholdingTaxStatus
import com.openbank.interest.domain.tax.WithholdingTreatment
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "catalog_interest_sync_state")
class CatalogInterestSyncStateEntity : PanacheEntityBase() {
    @Id
    @Column(name = "consumer", length = 64)
    var consumer: String = ""

    @Column(name = "cursor")
    var cursor: String? = null

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "catalog_interest_event_receipts")
class CatalogInterestEventReceiptEntity : PanacheEntityBase() {
    @Id
    @Column(name = "event_id", columnDefinition = "uuid")
    var eventId: UUID = Ids.newId()

    @Column(name = "event_type", length = 128)
    var eventType: String = ""

    @Column(name = "outcome", length = 32)
    var outcome: String = ""

    @Column(name = "reason", length = 512)
    var reason: String? = null

    @Column(name = "processed_at")
    var processedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "catalog_interest_rate_snapshots")
class CatalogInterestRateSnapshotEntity : PanacheEntityBase() {
    @Id
    @Column(name = "revision_id", columnDefinition = "uuid")
    var revisionId: UUID = Ids.newId()

    @Column(name = "offering_id", columnDefinition = "uuid")
    var offeringId: UUID = Ids.newId()

    @Column(name = "specification_id", columnDefinition = "uuid")
    var specificationId: UUID = Ids.newId()

    @Column(name = "config_id", columnDefinition = "uuid")
    var configId: UUID? = null

    @Column(name = "schema_id", length = 128)
    var schemaId: String = ""

    @Column(name = "schema_version")
    var schemaVersion: Int = 0

    @Column(name = "content_hash", length = 64)
    var contentHash: String = ""

    @Column(name = "currency", length = 3)
    var currency: String? = null

    @Column(name = "annual_rate", precision = 20, scale = 18)
    var annualRate: BigDecimal? = null

    @Column(name = "day_count", length = 16)
    var dayCount: String? = null

    @Column(name = "effective_from")
    var effectiveFrom: LocalDate? = null

    @Column(name = "effective_to")
    var effectiveTo: LocalDate? = null

    @Column(name = "outcome", length = 32)
    var outcome: String = ""

    @Column(name = "reason", length = 512)
    var reason: String? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "interest_rate_configs")
class InterestRateConfigEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "product_id")
    var productId: String = ""

    // Non-null => an account-specific override that wins over the product-level default (null).
    @Column(name = "account_id", columnDefinition = "uuid")
    var accountId: UUID? = null

    @Column(name = "currency", length = 3)
    var currency: String = ""

    @Column(name = "rate_type")
    @Enumerated(EnumType.STRING)
    var rateType: InterestRateType = InterestRateType.FIXED

    // Catalog v2 carries exact decimal strings with up to 18 fractional digits. Keep that
    // precision through the reference-data boundary; binary/scale truncation changes money.
    @Column(name = "annual_rate", precision = 20, scale = 18)
    var annualRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "min_balance", precision = 20, scale = 4)
    var minBalance: BigDecimal = BigDecimal.ZERO

    @Column(name = "max_balance", precision = 20, scale = 4)
    var maxBalance: BigDecimal? = null

    @Column(name = "day_count")
    @Enumerated(EnumType.STRING)
    var dayCount: DayCount = DayCount.ACT_365

    @Column(name = "effective_from")
    var effectiveFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "effective_to")
    var effectiveTo: LocalDate? = null

    @Column(name = "active")
    var active: Boolean = true

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "interest_accruals")
class InterestAccrualEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "account_id", columnDefinition = "uuid")
    var accountId: UUID = Ids.newId()

    @Column(name = "product_id")
    var productId: String = ""

    @Column(name = "config_id", columnDefinition = "uuid")
    var configId: UUID = Ids.newId()

    @Column(name = "accrual_date")
    var accrualDate: LocalDate = LocalDate.EPOCH

    @Column(name = "balance", precision = 20, scale = 4)
    var balance: BigDecimal = BigDecimal.ZERO

    @Column(name = "daily_rate", precision = 14, scale = 10)
    var dailyRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "accrued_amount", precision = 20, scale = 6)
    var accruedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: AccrualStatus = AccrualStatus.ACCRUING

    @Column(name = "claimed_period_to")
    var claimedPeriodTo: LocalDate? = null

    // Tax profile frozen at claim time (issue #1355) — replayed on a capitalize() retry so the
    // withholding row matches the ledger journal the interrupted attempt idempotently replays.
    @Column(name = "claimed_taxpayer_type")
    @Enumerated(EnumType.STRING)
    var claimedTaxpayerType: TaxpayerType? = null

    @Column(name = "claimed_residency")
    @Enumerated(EnumType.STRING)
    var claimedResidency: TaxResidency? = null

    @Column(name = "claimed_treaty_rate", precision = 6, scale = 4)
    var claimedTreatyRate: BigDecimal? = null

    @Column(name = "claimed_non_cooperating_state")
    var claimedNonCooperatingState: Boolean? = null

    @Column(name = "claimed_exempt_code")
    var claimedExemptCode: String? = null

    @Column(name = "capitalized_at")
    var capitalizedAt: OffsetDateTime? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "interest_capitalizations")
class InterestCapitalizationEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "account_id", columnDefinition = "uuid")
    var accountId: UUID = Ids.newId()

    @Column(name = "product_id")
    var productId: String = ""

    @Column(name = "period_from")
    var periodFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "period_to")
    var periodTo: LocalDate = LocalDate.EPOCH

    @Column(name = "total_accrued", precision = 20, scale = 6)
    var totalAccrued: BigDecimal = BigDecimal.ZERO

    @Column(name = "capitalized_amount", precision = 20, scale = 4)
    var capitalizedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "gross_amount", precision = 20, scale = 4)
    var grossAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "tax_amount", precision = 20, scale = 4)
    var taxAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "net_amount", precision = 20, scale = 4)
    var netAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "ledger_entry_id", columnDefinition = "uuid")
    var ledgerEntryId: UUID? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "withholding_tax")
class WithholdingTaxEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "capitalization_id", columnDefinition = "uuid")
    var capitalizationId: UUID = Ids.newId()

    @Column(name = "account_id", columnDefinition = "uuid")
    var accountId: UUID = Ids.newId()

    @Column(name = "party_ref")
    var partyRef: String? = null

    @Column(name = "period_from")
    var periodFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "period_to")
    var periodTo: LocalDate = LocalDate.EPOCH

    @Column(name = "taxable_base", precision = 20, scale = 4)
    var taxableBase: BigDecimal = BigDecimal.ZERO

    @Column(name = "rate", precision = 6, scale = 4)
    var rate: BigDecimal = BigDecimal.ZERO

    @Column(name = "tax_amount", precision = 20, scale = 4)
    var taxAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "CZK"

    @Column(name = "treatment")
    @Enumerated(EnumType.STRING)
    var treatment: WithholdingTreatment = WithholdingTreatment.WITHHELD

    @Column(name = "exempt_code")
    var exemptCode: String? = null

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: WithholdingTaxStatus = WithholdingTaxStatus.RECORDED

    @Column(name = "remittance_id", columnDefinition = "uuid")
    var remittanceId: UUID? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "withholding_remittance")
class WithholdingRemittanceEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "period_year")
    var periodYear: Int = 0

    @Column(name = "period_month")
    var periodMonth: Int = 0

    @Column(name = "authority")
    var authority: String = ""

    @Column(name = "currency", length = 3)
    var currency: String = "CZK"

    @Column(name = "total_tax_amount", precision = 20, scale = 4)
    var totalTaxAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "item_count")
    var itemCount: Int = 0

    @Column(name = "due_date")
    var dueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: WithholdingRemittanceStatus = WithholdingRemittanceStatus.PENDING

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}
