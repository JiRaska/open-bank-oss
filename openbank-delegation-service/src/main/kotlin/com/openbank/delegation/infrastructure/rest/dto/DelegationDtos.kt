// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest.dto

import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.Exposure
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class MoneyDto(val amount: BigDecimal, val currency: String) {
    fun toDomain(): Money = Money.of(amount, currency)

    companion object {
        fun from(m: Money): MoneyDto = MoneyDto(m.amount, m.currency.code)
    }
}

data class ExposureDto(
    val redactionRules: List<String> = emptyList(),
    val maxViews: Int? = null,
    val watermark: Boolean = true,
    val allowDownload: Boolean = false,
) {
    fun toDomain(): Exposure = Exposure(redactionRules, maxViews, watermark, allowDownload)

    companion object {
        fun from(e: Exposure): ExposureDto = ExposureDto(e.redactionRules, e.maxViews, e.watermark, e.allowDownload)
    }
}

data class OfferDelegationRequest(
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    val requiredApprovals: Int? = null,
    val perTransactionLimit: MoneyDto? = null,
    val dailyLimit: MoneyDto? = null,
    val monthlyLimit: MoneyDto? = null,
    val exposure: ExposureDto? = null,
    val validTo: OffsetDateTime? = null,
    val grantScaSessionId: UUID,
    val note: String? = null,
)

data class PreviewDelegationRequest(
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    val requiredApprovals: Int? = null,
    val perTransactionLimit: MoneyDto? = null,
    val dailyLimit: MoneyDto? = null,
    val monthlyLimit: MoneyDto? = null,
    val exposure: ExposureDto? = null,
    val validTo: OffsetDateTime? = null,
)

data class DelegationPreviewResponse(val valid: Boolean = true)

data class RevokeDelegationRequest(val reason: String)

data class SuspendDelegationRequest(val reason: String)

data class CheckDelegationRequest(
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capability: DelegationCapability,
    val amount: MoneyDto? = null,
)

data class DelegationResponse(
    val id: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    /**
     * Counterparty labels as snapshotted on the grant (issue #3604). Null on grants offered
     * before the field existed; a client must fall back to the party id rather than render a
     * blank, which on a consent screen reads as a name that failed to load.
     *
     * Disclosure boundary: every read path is party-scoped — `getDelegation` 404s a caller who is
     * neither grantor nor grantee, and the list endpoints refuse a party id other than the
     * authenticated one — so these names reach only the two parties to the grant and role-gated
     * bank staff. They are NOT a party-name lookup: nothing here resolves an arbitrary id.
     */
    val grantorName: String?,
    val granteeName: String?,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val approvalPolicy: ApprovalPolicy,
    val requiredApprovals: Int?,
    val perTransactionLimit: MoneyDto?,
    val dailyLimit: MoneyDto?,
    val monthlyLimit: MoneyDto?,
    val exposure: ExposureDto?,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime?,
    val status: DelegationStatus,
    val note: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val closedAt: OffsetDateTime?,
    val closedReason: String?,
) {
    companion object {
        fun from(g: DelegationGrant): DelegationResponse = DelegationResponse(
            id = g.id,
            grantorPartyId = g.grantorPartyId,
            granteePartyId = g.granteePartyId,
            grantorName = g.grantorName,
            granteeName = g.granteeName,
            resourceType = g.resourceType,
            resourceId = g.resourceId,
            capabilities = g.capabilities,
            approvalPolicy = g.approvalPolicy,
            requiredApprovals = g.requiredApprovals,
            perTransactionLimit = g.perTransactionLimit?.let { MoneyDto.from(it) },
            dailyLimit = g.dailyLimit?.let { MoneyDto.from(it) },
            monthlyLimit = g.monthlyLimit?.let { MoneyDto.from(it) },
            exposure = g.exposure?.let { ExposureDto.from(it) },
            validFrom = g.validFrom,
            validTo = g.validTo,
            status = g.status,
            note = g.note,
            createdAt = g.createdAt,
            updatedAt = g.updatedAt,
            closedAt = g.closedAt,
            closedReason = g.closedReason,
        )
    }
}

data class DelegationCheckResponse(val granted: Boolean, val reason: String? = null, val code: String? = null) {
    companion object {
        fun from(result: DelegationCheckResult): DelegationCheckResponse = when (result) {
            is DelegationCheckResult.Allowed -> DelegationCheckResponse(granted = true)

            is DelegationCheckResult.Denied -> DelegationCheckResponse(
                granted = false,
                reason = result.reason,
                code = result.code,
            )
        }
    }
}
