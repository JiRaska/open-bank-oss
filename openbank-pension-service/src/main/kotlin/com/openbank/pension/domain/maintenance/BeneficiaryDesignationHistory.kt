// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.maintenance

import com.openbank.pension.domain.model.Beneficiary
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.Limits
import com.openbank.pension.domain.model.PensionContract
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A complete, ordered designation the participant signs (add, remove and reorder are all "the new
 * list"). [documentSha256] binds the exact list, in order, and the history version it replaces.
 */
data class PlannedBeneficiaryChange(val contractId: UUID, val baseSeq: Int, val beneficiaries: List<Beneficiary>) {
    val documentSha256: String
        get() = Sha256.hex(
            (
                listOf("pension-beneficiary-designation/v1", contractId.toString(), baseSeq.toString()) +
                    beneficiaries.mapIndexed { i, b ->
                        "$i\u001f${b.name}\u001f${b.partyId ?: ""}\u001f${b.sharePercent.stripTrailingZeros().toPlainString()}"
                    }
                ).joinToString("|"),
        )
}

data class BeneficiaryDesignationVersion(
    val seq: Int,
    /** In the participant's order: index 0 is listed first. */
    val beneficiaries: List<Beneficiary>,
    val documentSha256: String,
    val scaChallengeId: String,
    val idempotencyKey: String,
    val changedAt: Instant,
)

/**
 * Append-only beneficiary designations of one contract (ADR-0334, #12376). Invariants:
 *
 * - shares are positive, carry at most two decimals and total EXACTLY 100 (an empty list is
 *   allowed and means "no designation": the pack's estate rule then applies on death);
 * - identity is minimal (GDPR Art. 5(1)(c)): a name and, optionally, the party id of a person the
 *   bank already knows — no birth number, address or document number is accepted or stored;
 * - no person twice, and never the participant themself;
 * - NOTHING changes once a death claim exists: the claimants were fixed from the designation in
 *   force at notification, and a later edit would rewrite who inherits;
 * - nor on a terminal contract.
 */
data class BeneficiaryDesignationHistory(val contractId: UUID, val versions: List<BeneficiaryDesignationVersion>) {
    init {
        require(versions.map { it.seq } == (1..versions.size).toList()) { "designation versions must be 1..n in order" }
    }

    val latestSeq: Int get() = versions.size

    fun byIdempotencyKey(key: String): BeneficiaryDesignationVersion? = versions.firstOrNull {
        it.idempotencyKey == key
    }

    fun plan(
        contract: PensionContract,
        designations: List<Beneficiary>,
        deathClaimRegistered: Boolean,
    ): PlannedBeneficiaryChange {
        require(contract.id == contractId) { "history belongs to another contract" }
        check(!deathClaimRegistered) { "beneficiaries cannot change once a death claim is registered" }
        check(!contract.status.terminal) { "beneficiaries cannot change on a ${contract.status} contract" }
        check(contract.status != ContractStatus.DRAFT) { "a draft names its beneficiaries at creation" }
        require(designations.size <= Limits.MAX_BENEFICIARIES) { "at most ${Limits.MAX_BENEFICIARIES} beneficiaries" }
        designations.forEach {
            require(it.sharePercent.stripTrailingZeros().scale() <= SHARE_SCALE) {
                "beneficiary shares carry at most $SHARE_SCALE decimals"
            }
            require(it.name == it.name.trim()) { "beneficiary names must not start or end with whitespace" }
            require(it.partyId != contract.participantPartyId) { "the participant cannot be their own beneficiary" }
        }
        val total = designations.fold(BigDecimal.ZERO) { acc, b -> acc + b.sharePercent }
        require(designations.isEmpty() || total.compareTo(Beneficiary.HUNDRED) == 0) {
            "beneficiary shares must total exactly 100, were ${total.toPlainString()}"
        }
        val identities = designations.map { it.partyId?.toString() ?: "name:" + it.name.lowercase() }
        require(identities.toSet().size == identities.size) { "a beneficiary may be named only once" }
        require(designations != contract.beneficiaries) { "the requested designation is the one already in force" }
        return PlannedBeneficiaryChange(contractId, latestSeq, designations)
    }

    fun record(
        plan: PlannedBeneficiaryChange,
        deathClaimRegistered: Boolean,
        scaChallengeId: String,
        idempotencyKey: String,
        now: Instant,
    ): BeneficiaryDesignationHistory {
        require(plan.contractId == contractId) { "plan belongs to another contract" }
        check(!deathClaimRegistered) { "beneficiaries cannot change once a death claim is registered" }
        check(plan.baseSeq == latestSeq) { "the designation changed since it was previewed; preview again" }
        require(scaChallengeId.isNotBlank()) { "scaChallengeId is required" }
        val version = BeneficiaryDesignationVersion(
            seq = latestSeq + 1,
            beneficiaries = plan.beneficiaries,
            documentSha256 = plan.documentSha256,
            scaChallengeId = scaChallengeId,
            idempotencyKey = idempotencyKey,
            changedAt = now,
        )
        return copy(versions = versions + version)
    }

    companion object {
        private const val SHARE_SCALE = 2

        fun empty(contractId: UUID) = BeneficiaryDesignationHistory(contractId, emptyList())
    }
}
