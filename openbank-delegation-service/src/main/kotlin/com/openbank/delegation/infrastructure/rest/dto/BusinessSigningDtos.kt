// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.delegation.application.usecase.PendingForHuman
import com.openbank.delegation.application.usecase.SigningPayloadCodec
import com.openbank.delegation.domain.model.ApprovalRequest
import com.openbank.delegation.domain.model.SignerGroup
import com.openbank.delegation.domain.model.SigningAmount
import com.openbank.delegation.domain.model.SigningEvaluation
import com.openbank.delegation.domain.model.SigningPolicy
import com.openbank.delegation.domain.model.SigningPolicyRule
import com.openbank.delegation.domain.model.TrustedPayee
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Contract pin 3: every list is `{"data": [...]}`. */
data class ListEnvelope<T>(val data: List<T>)

data class MoneyBody(val amount: BigDecimal?, val currency: String?) {
    fun toDomain(field: String): SigningAmount = SigningAmount(
        requireNotNull(amount) { "$field.amount is required" },
        requireNotNull(currency?.uppercase()) { "$field.currency is required" },
    )

    companion object {
        fun from(a: SigningAmount?): MoneyBody? = a?.let { MoneyBody(it.amount, it.currency) }
    }
}

data class EvaluateRequest(val amount: BigDecimal?, val currency: String?, val creditorIban: String?, val rail: String?)

data class EvaluationResponse(
    val required: Int,
    val eligibleSignerIds: List<UUID>,
    val mustIncludeGroupId: String?,
    val trusted: Boolean,
    val ruleIndex: Int,
    val policyVersion: Int,
    @get:JsonProperty("isDerivedFromRegister") val isDerivedFromRegister: Boolean,
) {
    companion object {
        fun from(e: SigningEvaluation) = EvaluationResponse(
            e.required, e.eligibleSignerIds.sorted(), e.mustIncludeGroupId, e.trusted, e.ruleIndex, e.policyVersion, e.derivedFromRegister,
        )
    }
}

data class RuleBody(
    val minAmount: MoneyBody? = null,
    val maxAmount: MoneyBody? = null,
    val currency: String? = null,
    val requiredSignatures: Int? = null,
    val groupId: String? = null,
    val mustIncludeGroupId: String? = null,
) {
    fun toDomain() = SigningPolicyRule(
        minAmount = minAmount?.toDomain("minAmount"),
        maxAmount = maxAmount?.toDomain("maxAmount"),
        currency = currency?.uppercase(),
        requiredSignatures = requireNotNull(requiredSignatures) { "requiredSignatures is required" },
        groupId = groupId,
        mustIncludeGroupId = mustIncludeGroupId,
    )

    companion object {
        fun from(r: SigningPolicyRule) = RuleBody(
            MoneyBody.from(r.minAmount), MoneyBody.from(r.maxAmount), r.currency, r.requiredSignatures, r.groupId, r.mustIncludeGroupId,
        )
    }
}

data class SigningPolicyResponse(
    val entityPartyId: UUID,
    val version: Int,
    val rules: List<RuleBody>,
    val trustedPayeeCap: MoneyBody?,
    val updatedAt: Instant?,
    val updatedByApprovalId: UUID?,
    @get:JsonProperty("isDerivedFromRegister") val isDerivedFromRegister: Boolean,
) {
    companion object {
        fun from(p: SigningPolicy) = SigningPolicyResponse(
            p.entityPartyId, p.version, p.rules.map(RuleBody::from), MoneyBody.from(p.trustedPayeeCap),
            p.updatedAt, p.updatedByApprovalId, p.derivedFromRegister,
        )
    }
}

data class ProposePolicyRequest(val initiatorPartyId: UUID?, val rules: List<RuleBody>?, val trustedPayeeCap: MoneyBody? = null)

data class SignerGroupBody(val id: String, val name: String, val memberPartyIds: List<UUID>) {
    companion object {
        fun from(g: SignerGroup) = SignerGroupBody(g.id, g.name, g.memberPartyIds.sorted())
    }
}

data class ProposeGroupRequest(val initiatorPartyId: UUID?, val id: String?, val name: String?, val memberPartyIds: List<UUID>?)

data class TrustedPayeeResponse(
    val id: UUID,
    val entityPartyId: UUID,
    val iban: String,
    val name: String,
    val bic: String?,
    val addedAt: Instant,
    val addedByApprovalId: UUID,
    val status: String,
) {
    companion object {
        fun from(p: TrustedPayee) = TrustedPayeeResponse(p.id, p.entityPartyId, p.iban, p.name, p.bic, p.addedAt, p.addedByApprovalId, p.status.name)
    }
}

data class ProposePayeeRequest(val initiatorPartyId: UUID?, val iban: String?, val name: String?, val bic: String? = null)

data class SignatureBody(val partyId: UUID?, val scaChallengeId: UUID?)

data class PaymentFactsBody(
    val amount: BigDecimal?,
    val currency: String?,
    val creditorIban: String?,
    val creditorName: String? = null,
    val rail: String?,
)

data class CreatePaymentApprovalRequest(
    val initiator: SignatureBody?,
    val payment: PaymentFactsBody?,
    val payload: Map<String, Any?>?,
    val expiresInSeconds: Long? = null,
)

data class RejectionBody(val partyId: UUID?, val reason: String? = null)

data class ReleaseResultBody(val ok: Boolean?, val releaseRef: String? = null, val error: String? = null, val claimToken: UUID? = null)

data class ReleaseClaimResponse(val claimToken: UUID, val payload: Any?)

data class SignatureResponse(val partyId: UUID, val scaChallengeId: UUID, val at: Instant)

data class RejectionResponse(val partyId: UUID?, val reason: String?, val at: Instant)

data class ApprovalRequestResponse(
    val id: UUID,
    val entityPartyId: UUID,
    val kind: String,
    val status: String,
    val payload: Any?,
    val payloadSha256: String,
    val summary: Any?,
    val policyVersion: Int,
    val required: Int,
    val collected: Int,
    val eligibleSignerIds: List<UUID>,
    val mustIncludeGroupId: String?,
    val initiatorPartyId: UUID,
    val signatures: List<SignatureResponse>,
    val rejection: RejectionResponse?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val releasedAt: Instant?,
    val releaseRef: String?,
    val releaseError: String?,
) {
    companion object {
        fun from(r: ApprovalRequest, codec: SigningPayloadCodec) = ApprovalRequestResponse(
            id = r.id,
            entityPartyId = r.entityPartyId,
            kind = r.kind.name,
            status = r.status.name,
            payload = codec.parseObject(r.payload),
            payloadSha256 = r.payloadSha256,
            summary = r.summary?.let { codec.parseObject(it) },
            policyVersion = r.policyVersion,
            required = r.required,
            collected = r.collected,
            eligibleSignerIds = r.eligibleSignerIds.sorted(),
            mustIncludeGroupId = r.mustIncludeGroupId,
            initiatorPartyId = r.initiatorPartyId,
            signatures = r.signatures.map { SignatureResponse(it.partyId, it.scaChallengeId, it.at) },
            rejection = r.rejection?.let { RejectionResponse(it.partyId, it.reason, it.at) },
            expiresAt = r.expiresAt,
            createdAt = r.createdAt,
            releasedAt = r.releasedAt,
            releaseRef = r.releaseRef,
            releaseError = r.releaseError,
        )
    }
}

data class PendingEntityResponse(val entityPartyId: UUID, val entityName: String?, val count: Int, val oldestExpiresAt: Instant)

data class PendingForHumanResponse(
    val humanPartyId: UUID,
    val total: Int,
    val entities: List<PendingEntityResponse>,
    val items: List<ApprovalRequestResponse>,
) {
    companion object {
        fun from(human: UUID, p: PendingForHuman, codec: SigningPayloadCodec) = PendingForHumanResponse(
            humanPartyId = human,
            total = p.items.size,
            entities = p.entities.map { PendingEntityResponse(it.entityPartyId, it.entityName, it.count, it.oldestExpiresAt) },
            items = p.items.map { ApprovalRequestResponse.from(it, codec) },
        )
    }
}
