// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.port.out

import java.util.UUID

/** One party named in the agreement. [partyRef] is null for a representative who is not a signer. */
data class AgreementParty(val partyRef: UUID?, val name: String, val role: String?)

data class AgreementEntity(val name: String, val ico: String, val seat: String, val legalForm: String)

data class AgreementProduct(val code: String, val name: String, val currency: String)

/** document-service `POST /api/v1/business-agreements` body (shared business-contract spec, W1). */
data class BusinessAgreementRequest(
    val caseId: UUID,
    val entityPartyId: UUID,
    val lang: String,
    val entity: AgreementEntity,
    val representatives: List<AgreementParty>,
    val signers: List<AgreementParty>,
    val signingRule: String,
    val product: AgreementProduct,
)

/** document-service's per-signer ceremony state — its vocabulary, not kyb's [com.openbank.kyb.domain.model.SignerStatus]. */
enum class CeremonySignerStatus {
    PENDING,
    SIGNED,
    DECLINED,
    ;

    companion object {
        /** An unknown value is never read as SIGNED. */
        fun parse(value: String?): CeremonySignerStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

/** document-service's ceremony state. */
enum class CeremonyStatus {
    PENDING,
    PARTIALLY_SIGNED,
    COMPLETED,
    DECLINED,
    EXPIRED,
    ;

    companion object {
        /** An unknown value is never read as COMPLETED. */
        fun parse(value: String?): CeremonyStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

data class CeremonySigner(val partyRef: UUID, val status: CeremonySignerStatus, val signedAt: String? = null)

data class AgreementDisclosure(
    val code: String,
    val version: String,
    val title: String? = null,
    val sha256: String,
    val documentId: UUID? = null,
)

/** document-service `BusinessAgreement` response. */
data class BusinessAgreementView(
    val caseId: UUID,
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val sealedSha256: String? = null,
    val ceremonyId: UUID,
    val ceremonyStatus: CeremonyStatus,
    val signers: List<CeremonySigner>,
    val disclosures: List<AgreementDisclosure>,
)

/**
 * document-service: renders the business framework agreement with its annexes and owns the SCA
 * signature ceremony over it. kyb never takes a client's word that a ceremony was signed — it asks.
 */
interface DocumentGateway {
    /** Idempotent per (caseId, lang). A SIGNED agreement is never replaced (document-service answers 409). */
    suspend fun ensureBusinessAgreement(request: BusinessAgreementRequest): BusinessAgreementView

    /** Null when document-service holds no agreement for the case in [lang]. */
    suspend fun businessAgreement(caseId: UUID, lang: String): BusinessAgreementView?
}

/** Onboarding settings the use case needs from configuration; a port so the use case stays framework-free. */
interface BusinessOnboardingSettings {
    /** ISO-3166 alpha-2 codes whose appearance in the questionnaire routes the case to review. */
    val highRiskCountries: Set<String>
    val businessProduct: AgreementProduct

    /** The register's legal-form label in [lang], or null when the country pack has none. */
    fun legalFormLabel(country: String?, legalFormCode: String?, lang: String): String?
}
