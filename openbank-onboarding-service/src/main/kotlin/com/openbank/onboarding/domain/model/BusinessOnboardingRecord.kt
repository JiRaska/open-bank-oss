// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Read-model projection of one BUSINESS onboarding case (ADR-0284 D6, issue #8866).
 *
 * The sibling of [OnboardingRecord], and deliberately a SEPARATE row rather than more columns on
 * it: the two are keyed differently and count differently. An individual funnel row is one per
 * PARTY; a business case is one per CASE, may exist before any entity party does, and carries
 * several humans. Folding them into one table would make every existing cockpit count ambiguous —
 * "how many people are stuck in KYC" would start including companies.
 *
 * Like its sibling this record is never the source of truth. kyb-service owns the case; this is a
 * denormalised view assembled from `openbank.kyb.events` for the operator board.
 */
data class BusinessOnboardingRecord(
    val caseId: UUID,
    val identifierScheme: String,
    val identifier: String,
    val country: String?,
    val legalName: String?,
    val legalFormClass: String?,
    val initiatorPartyId: UUID,
    val entityPartyId: UUID?,
    val caseStatus: BusinessCaseStage,
    val stage: BusinessFunnelStage,
    val requiredSignatures: Int?,
    val signedCount: Int,
    /** Why a human at the bank is involved. Carried verbatim — the cockpit renders the reason, never a paraphrase. */
    val reviewReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** The case status as kyb-service publishes it. Unknown values are not guessed — see [BusinessCaseStage.from]. */
enum class BusinessCaseStage {
    IDENTIFIER_ENTERED,
    REGISTRY_VERIFIED,
    INITIATOR_MATCHED,
    AWAITING_COSIGNERS,
    READY_TO_SIGN,
    SIGNED,
    ACTIVE,
    MANUAL_REVIEW,
    REJECTED,
    ABANDONED,
    ;

    companion object {
        /**
         * Null for a status this service does not know. A projection that mapped an unrecognised
         * status onto its nearest neighbour would put a case in a board column it does not belong
         * to, which is worse than not showing it: the operator would act on it.
         */
        fun from(raw: String?): BusinessCaseStage? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * The operator board's columns for business cases (ADR-0284 D6). Coarser than the case status on
 * purpose: the board answers "who is waiting on whom", and three of the ten statuses are the same
 * answer — the bank is waiting for the customer to pick signers or for those signers to act.
 *
 * `NEEDS_REVIEW` is the only column that is work for the BANK, which is why it is its own column
 * and why the cockpit sorts on it first.
 */
enum class BusinessFunnelStage {
    /** The identifier is in, the register has not answered yet. */
    STARTED,

    /** The register answered; waiting for the customer to say which listed person they are. */
    AWAITING_INITIATOR,

    /** Waiting for co-signers to be invited, to verify themselves, or to sign. */
    AWAITING_SIGNATURES,

    /** Every required signature is in; waiting for the entity party's KYC + AML gate (ADR-0267). */
    AWAITING_CHECKS,

    /** Live customer. */
    ACTIVE,

    /** A person at the bank has to decide: unparsed representation rule, unlisted initiator, dead register. */
    NEEDS_REVIEW,

    /** Terminal and not a customer: rejected by an operator, or abandoned by the initiator or the timer. */
    CLOSED,
    ;

    companion object {
        /** The board column for a case status. Total over the enum — a new status must be classified here. */
        fun of(status: BusinessCaseStage): BusinessFunnelStage = when (status) {
            BusinessCaseStage.IDENTIFIER_ENTERED -> STARTED
            BusinessCaseStage.REGISTRY_VERIFIED -> AWAITING_INITIATOR
            BusinessCaseStage.INITIATOR_MATCHED,
            BusinessCaseStage.AWAITING_COSIGNERS,
            BusinessCaseStage.READY_TO_SIGN,
            -> AWAITING_SIGNATURES
            BusinessCaseStage.SIGNED -> AWAITING_CHECKS
            BusinessCaseStage.ACTIVE -> ACTIVE
            BusinessCaseStage.MANUAL_REVIEW -> NEEDS_REVIEW
            BusinessCaseStage.REJECTED, BusinessCaseStage.ABANDONED -> CLOSED
        }
    }
}
