// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.annotation.JsonIgnore
import com.openbank.delegation.application.port.`in`.DelegationCandidate
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.Exposure
import com.openbank.libs.domain.money.Money
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class StatutoryDelegationTerms(
    override val grantorPartyId: UUID,
    override val granteePartyId: UUID,
    override val resourceType: DelegationResourceType,
    override val resourceId: UUID,
    override val capabilities: Set<DelegationCapability>,
    override val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    override val requiredApprovals: Int? = null,
    override val approvalGroupId: UUID? = null,
    override val perTransactionLimit: Money? = null,
    override val dailyLimit: Money? = null,
    override val monthlyLimit: Money? = null,
    override val exposure: Exposure? = null,
    override val recertificationAudience: DelegationRecertificationAudience? = null,
    override val validTo: OffsetDateTime? = null,
    val note: String? = null,
    val approvalGroupRevision: Long? = null,
    val issuedByOperationId: UUID? = null,
    val validFrom: OffsetDateTime? = null,
) : DelegationCandidate {

    @get:JsonIgnore
    override val callerPartyId: UUID? get() = null

    @get:JsonIgnore
    override val actorPartyId: UUID? get() = null

    fun normalized(): StatutoryDelegationTerms = copy(
        capabilities = capabilities.sortedBy { it.name }.toCollection(linkedSetOf()),
        validTo = validTo?.withOffsetSameInstant(ZoneOffset.UTC),
        validFrom = validFrom?.withOffsetSameInstant(ZoneOffset.UTC),
    )

    companion object {
        fun from(grant: DelegationGrant) = StatutoryDelegationTerms(
            grantorPartyId = grant.grantorPartyId,
            granteePartyId = grant.granteePartyId,
            resourceType = grant.resourceType,
            resourceId = grant.resourceId,
            capabilities = grant.capabilities,
            approvalPolicy = grant.approvalPolicy,
            requiredApprovals = grant.requiredApprovals,
            approvalGroupId = grant.approvalGroupId,
            perTransactionLimit = grant.perTransactionLimit,
            dailyLimit = grant.dailyLimit,
            monthlyLimit = grant.monthlyLimit,
            exposure = grant.exposure,
            recertificationAudience = grant.recertificationAudience,
            validTo = grant.validTo,
            note = grant.note,
            approvalGroupRevision = grant.approvalGroupRevision,
            issuedByOperationId = grant.grantorStatutoryOperationId,
            validFrom = grant.validFrom,
        ).normalized()
    }
}
