// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.DelegationCandidate
import com.openbank.delegation.domain.model.StatutoryRepresentationRule
import com.openbank.libs.domain.money.Money
import java.security.MessageDigest

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

    private fun money(value: Money?): Map<String, String>? = value?.let {
        linkedMapOf("amount" to it.amount.toPlainString(), "currency" to it.currency.code)
    }
}
