// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.entity

import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.Exposure
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "delegation_grants")
class DelegationGrantEntity : PanacheEntityBase() {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "grantor_party_id", nullable = false)
    lateinit var grantorPartyId: UUID

    @Column(name = "grantee_party_id", nullable = false)
    lateinit var granteePartyId: UUID

    /** Nullable by design — see [DelegationGrant.grantorName] (issue #3604). */
    @Column(name = "grantor_name", length = NAME_MAX_LENGTH)
    var grantorName: String? = null

    @Column(name = "grantee_name", length = NAME_MAX_LENGTH)
    var granteeName: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    lateinit var resourceType: DelegationResourceType

    @Column(name = "resource_id", nullable = false)
    lateinit var resourceId: UUID

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delegation_capabilities", joinColumns = [JoinColumn(name = "grant_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "capability")
    var capabilities: MutableSet<DelegationCapability> = mutableSetOf()

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_policy", nullable = false)
    lateinit var approvalPolicy: ApprovalPolicy

    @Column(name = "required_approvals")
    var requiredApprovals: Int? = null

    @Column(name = "per_tx_limit_amount", precision = 20, scale = 6)
    var perTxLimitAmount: BigDecimal? = null

    @Column(name = "per_tx_limit_currency", length = 3)
    var perTxLimitCurrency: String? = null

    @Column(name = "daily_limit_amount", precision = 20, scale = 6)
    var dailyLimitAmount: BigDecimal? = null

    @Column(name = "daily_limit_currency", length = 3)
    var dailyLimitCurrency: String? = null

    @Column(name = "monthly_limit_amount", precision = 20, scale = 6)
    var monthlyLimitAmount: BigDecimal? = null

    @Column(name = "monthly_limit_currency", length = 3)
    var monthlyLimitCurrency: String? = null

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "delegation_redaction_rules", joinColumns = [JoinColumn(name = "grant_id")])
    @Column(name = "rule")
    var redactionRules: MutableList<String> = mutableListOf()

    @Column(name = "max_views")
    var maxViews: Int? = null

    @Column(name = "watermark")
    var watermark: Boolean? = null

    @Column(name = "allow_download")
    var allowDownload: Boolean? = null

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: OffsetDateTime

    @Column(name = "valid_to")
    var validTo: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    lateinit var status: DelegationStatus

    @Column(name = "lifecycle_revision", nullable = false)
    var lifecycleRevision: Long = 0

    @Column(name = "grant_sca_session_id")
    var grantScaSessionId: UUID? = null

    @Column(name = "accept_sca_session_id")
    var acceptScaSessionId: UUID? = null

    @Column(name = "note")
    var note: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: OffsetDateTime

    @Column(name = "closed_at")
    var closedAt: OffsetDateTime? = null

    @Column(name = "closed_by")
    var closedBy: UUID? = null

    @Column(name = "closed_reason")
    var closedReason: String? = null

    fun toDomain(): DelegationGrant = DelegationGrant(
        id = id,
        grantorPartyId = grantorPartyId,
        granteePartyId = granteePartyId,
        grantorName = grantorName,
        granteeName = granteeName,
        resourceType = resourceType,
        resourceId = resourceId,
        capabilities = capabilities.toSet(),
        approvalPolicy = approvalPolicy,
        requiredApprovals = requiredApprovals,
        perTransactionLimit = toMoney(perTxLimitAmount, perTxLimitCurrency),
        dailyLimit = toMoney(dailyLimitAmount, dailyLimitCurrency),
        monthlyLimit = toMoney(monthlyLimitAmount, monthlyLimitCurrency),
        exposure = toExposure(),
        validFrom = validFrom,
        validTo = validTo,
        status = status,
        lifecycleRevision = lifecycleRevision,
        grantScaSessionId = grantScaSessionId,
        acceptScaSessionId = acceptScaSessionId,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        closedAt = closedAt,
        closedBy = closedBy,
        closedReason = closedReason,
    )

    /**
     * Re-scales to the currency's minor unit on the way out, which is not cosmetic: the columns are
     * NUMERIC(20,6) and Postgres hands back a `BigDecimal` of scale 6, while [Money] refuses a scale
     * wider than the currency has — so a CZK ceiling read straight through threw
     * "Amount scale 6 exceeds currency CZK fraction digits 2" and took the whole GET with it.
     *
     * It never surfaced before ADR-0249 because #3613 refused `dailyLimit`/`monthlyLimit` at the
     * offer API, so no row in any environment carried one, and `perTransactionLimit` happened to be
     * written and read back inside one session's cache in every test that set it. Making the
     * cumulative ceilings acceptable again is what first put a scale-6 number in front of this
     * mapper. HALF_EVEN never rounds here: nothing writes more precision than the currency has.
     */
    private fun toMoney(amount: BigDecimal?, currency: String?): Money? {
        if (amount == null || currency == null) return null
        val code = CurrencyCode.of(currency.trim())
        return Money(amount.setScale(code.defaultFractionDigits, RoundingMode.HALF_EVEN), code)
    }

    private fun toExposure(): Exposure? {
        val noScalarExposure = maxViews == null && watermark == null && allowDownload == null
        if (noScalarExposure && redactionRules.isEmpty()) {
            return null
        }
        return Exposure(
            redactionRules = redactionRules.toList(),
            maxViews = maxViews,
            watermark = watermark ?: true,
            allowDownload = allowDownload ?: false,
        )
    }

    companion object {
        /** Matches the `varchar(200)` in V3__delegation_counterparty_names.sql. */
        const val NAME_MAX_LENGTH = 200

        fun fromDomain(g: DelegationGrant): DelegationGrantEntity = DelegationGrantEntity().apply {
            id = g.id
            grantorPartyId = g.grantorPartyId
            granteePartyId = g.granteePartyId
            grantorName = g.grantorName?.take(NAME_MAX_LENGTH)
            granteeName = g.granteeName?.take(NAME_MAX_LENGTH)
            resourceType = g.resourceType
            resourceId = g.resourceId
            capabilities = g.capabilities.toMutableSet()
            approvalPolicy = g.approvalPolicy
            requiredApprovals = g.requiredApprovals
            perTxLimitAmount = g.perTransactionLimit?.amount
            perTxLimitCurrency = g.perTransactionLimit?.currency?.code
            dailyLimitAmount = g.dailyLimit?.amount
            dailyLimitCurrency = g.dailyLimit?.currency?.code
            monthlyLimitAmount = g.monthlyLimit?.amount
            monthlyLimitCurrency = g.monthlyLimit?.currency?.code
            redactionRules = g.exposure?.redactionRules?.toMutableList() ?: mutableListOf()
            maxViews = g.exposure?.maxViews
            watermark = g.exposure?.watermark
            allowDownload = g.exposure?.allowDownload
            validFrom = g.validFrom
            validTo = g.validTo
            status = g.status
            lifecycleRevision = g.lifecycleRevision
            grantScaSessionId = g.grantScaSessionId
            acceptScaSessionId = g.acceptScaSessionId
            note = g.note
            createdAt = g.createdAt
            updatedAt = g.updatedAt
            closedAt = g.closedAt
            closedBy = g.closedBy
            closedReason = g.closedReason
        }
    }
}
