// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.DelegationCandidate
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

/** Byte-stable v1 evidence: the signed hash is over these exact UTF-8 JSON bytes, not a DTO re-render. */
internal class StatutoryOperationEvidence(private val mapper: ObjectMapper) {
    fun payload(candidate: DelegationCandidate): String = mapper.writeValueAsString(
        linkedMapOf(
            "version" to 1,
            "grantorPartyId" to candidate.grantorPartyId.toString(),
            "granteePartyId" to candidate.granteePartyId.toString(),
            "resourceType" to candidate.resourceType.name,
            "resourceId" to candidate.resourceId.toString(),
            "capabilities" to candidate.capabilities.map { it.name }.sorted(),
            "approvalPolicy" to candidate.approvalPolicy.name,
            "requiredApprovals" to candidate.requiredApprovals,
            "perTransactionLimit" to money(candidate.perTransactionLimit),
            "dailyLimit" to money(candidate.dailyLimit),
            "monthlyLimit" to money(candidate.monthlyLimit),
            "recertificationAudience" to candidate.recertificationAudience?.name,
            "validTo" to candidate.validTo?.toString(),
        ),
    )

    fun rule(rule: StatutoryRepresentationRule): String = mapper.writeValueAsString(
        linkedMapOf(
            "version" to 1,
            "policyId" to rule.policyId.toString(),
            "principalPartyId" to rule.principalPartyId.toString(),
            "revision" to rule.revision,
            "sourceCaseId" to rule.sourceCaseId.toString(),
            "attestationId" to rule.attestationId.toString(),
            "ruleTextHash" to rule.ruleTextHash,
            "mode" to rule.mode.name,
            "requiredSignatures" to rule.requiredSignatures,
            "requiredOffices" to rule.requiredOffices.sorted(),
            "registryRepresentativeCount" to rule.registryRepresentativeCount,
            "eligibleRepresentatives" to rule.eligibleRepresentatives.sortedBy { it.partyId.toString() }.map {
                linkedMapOf(
                    "partyId" to it.partyId.toString(),
                    "registryRepresentativeIndices" to it.registryRepresentativeIndices.sorted(),
                    "officeTags" to it.officeTags.sorted(),
                )
            },
        ),
    )

    fun hash(json: String): String = MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Rehydrate only the versioned, immutable offer fields; live gates run again before execution. */
    fun decode(payload: String, principal: UUID, actor: UUID): PreviewDelegationCommand {
        val node = mapper.readTree(payload)
        require(node.path("version").asInt() == 1) { "unsupported statutory proposal evidence version" }
        fun uuid(field: String): UUID = UUID.fromString(node.path(field).asText())
        fun money(field: String): Money? = node.path(field).takeUnless { it.isMissingNode || it.isNull }?.let {
            Money.of(BigDecimal(it.path("amount").asText()), it.path("currency").asText())
        }
        val grantor = uuid("grantorPartyId")
        require(grantor == principal) { "statutory proposal principal changed" }
        return PreviewDelegationCommand(
            callerPartyId = principal,
            actorPartyId = actor,
            grantorPartyId = grantor,
            granteePartyId = uuid("granteePartyId"),
            resourceType = DelegationResourceType.valueOf(node.path("resourceType").asText()),
            resourceId = uuid("resourceId"),
            capabilities = node.path("capabilities").map { DelegationCapability.valueOf(it.asText()) }.toSet(),
            approvalPolicy = ApprovalPolicy.valueOf(node.path("approvalPolicy").asText()),
            requiredApprovals = node.path("requiredApprovals").takeUnless { it.isMissingNode || it.isNull }?.asInt(),
            perTransactionLimit = money("perTransactionLimit"),
            dailyLimit = money("dailyLimit"),
            monthlyLimit = money("monthlyLimit"),
            recertificationAudience = node.path("recertificationAudience")
                .takeUnless { it.isMissingNode || it.isNull }
                ?.asText()?.let(DelegationRecertificationAudience::valueOf),
            validTo = node.path("validTo").takeUnless { it.isMissingNode || it.isNull }
                ?.asText()?.let(OffsetDateTime::parse),
        )
    }

    private fun money(value: Money?): Map<String, String>? = value?.let {
        linkedMapOf("amount" to it.amount.toPlainString(), "currency" to it.currency.code)
    }
}
