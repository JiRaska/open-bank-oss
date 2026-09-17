// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.rest.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.kyb.application.port.`in`.QuestionnairePrefill
import com.openbank.kyb.application.port.out.BusinessAgreementView
import com.openbank.kyb.domain.model.AcceptedDisclosure
import com.openbank.kyb.domain.model.AgreementRecord
import com.openbank.kyb.domain.model.CrsStatus
import com.openbank.kyb.domain.model.Declarations
import com.openbank.kyb.domain.model.ExpectedTurnover
import com.openbank.kyb.domain.model.FatcaStatus
import com.openbank.kyb.domain.model.PepEntry
import com.openbank.kyb.domain.model.Questionnaire
import com.openbank.kyb.domain.model.RelationshipPurpose
import com.openbank.kyb.domain.model.SourceOfFunds
import java.time.Instant
import java.util.UUID

private inline fun <reified E : Enum<E>> enumOf(field: String, value: String?): E {
    val v = requireNotNull(value?.takeIf { it.isNotBlank() }) { "$field is required" }
    return enumValues<E>().firstOrNull { it.name == v.uppercase() }
        ?: throw IllegalArgumentException("unknown $field '$v'")
}

/** Every field nullable: an absent one is a 400 from [toDomain], never a 500 from Jackson/Kotlin. */
data class QuestionnaireRequest(
    val purpose: String? = null,
    val purposeNote: String? = null,
    val expectedMonthlyTurnover: String? = null,
    val sourceOfFunds: List<String>? = null,
    val sourceOfFundsNote: String? = null,
    val cashIntensive: Boolean? = null,
    val countries: List<String>? = null,
    val taxResidencies: List<String>? = null,
    val fatcaStatus: String? = null,
    val crsStatus: String? = null,
) {
    fun toDomain() = Questionnaire(
        purpose = enumOf<RelationshipPurpose>("purpose", purpose),
        purposeNote = purposeNote?.trim()?.takeIf { it.isNotEmpty() },
        expectedMonthlyTurnover = enumOf<ExpectedTurnover>("expectedMonthlyTurnover", expectedMonthlyTurnover),
        sourceOfFunds = sourceOfFunds.orEmpty().map { enumOf<SourceOfFunds>("sourceOfFunds", it) }.toSet(),
        sourceOfFundsNote = sourceOfFundsNote?.trim()?.takeIf { it.isNotEmpty() },
        cashIntensive = requireNotNull(cashIntensive) { "cashIntensive is required" },
        countries = countries.orEmpty().map { it.trim() },
        taxResidencies = taxResidencies.orEmpty().map { it.trim() },
        fatcaStatus = enumOf<FatcaStatus>("fatcaStatus", fatcaStatus),
        crsStatus = enumOf<CrsStatus>("crsStatus", crsStatus),
    )
}

// `isPep` is spelled out for Jackson: an is-prefixed Kotlin property is otherwise at the mercy of
// the bean-naming rules, which would read and write it as `pep`.
data class PepEntryRequest(
    val name: String? = null,
    @field:JsonProperty("isPep") @param:JsonProperty("isPep") val isPep: Boolean? = null,
    val detail: String? = null,
)

data class PepEntryResponse(
    val name: String,
    @get:JsonProperty("isPep") val isPep: Boolean,
    val detail: String?,
    /** Set when the value came from the person's customer profile, not from this declaration. */
    val fromProfile: Boolean,
)

data class DeclarationsResponse(
    val uboConfirmed: Boolean,
    val uboDiscrepancyNote: String?,
    val peps: List<PepEntryResponse>,
    val truthful: Boolean,
    val profileConflicts: List<String>,
    val declaredAt: Instant?,
    val declaredBy: UUID?,
) {
    companion object {
        fun from(d: Declarations) = DeclarationsResponse(
            uboConfirmed = d.uboConfirmed,
            uboDiscrepancyNote = d.uboDiscrepancyNote,
            peps = d.peps.map { PepEntryResponse(it.name, it.isPep, it.detail, it.partyId != null) },
            truthful = d.truthful,
            profileConflicts = d.profileConflicts,
            declaredAt = d.declaredAt,
            declaredBy = d.declaredBy,
        )
    }
}

data class DeclarationsRequest(
    val uboConfirmed: Boolean? = null,
    val uboDiscrepancyNote: String? = null,
    val peps: List<PepEntryRequest>? = null,
    val truthful: Boolean? = null,
) {
    fun toDomain() = Declarations(
        uboConfirmed = requireNotNull(uboConfirmed) { "uboConfirmed is required" },
        uboDiscrepancyNote = uboDiscrepancyNote?.trim()?.takeIf { it.isNotEmpty() },
        peps = peps.orEmpty().map {
            PepEntry(
                name = it.name?.trim().orEmpty(),
                isPep = requireNotNull(it.isPep) { "peps[].isPep is required" },
                detail = it.detail?.trim()?.takeIf { d -> d.isNotEmpty() },
            )
        },
        truthful = requireNotNull(truthful) { "truthful is required" },
    )
}

data class DisclosureRef(val code: String? = null, val version: String? = null, val sha256: String? = null)

data class AcceptDisclosuresRequest(val disclosures: List<DisclosureRef>? = null) {
    fun toDomain(): List<AcceptedDisclosure> = requireNotNull(disclosures) { "disclosures is required" }.map {
        AcceptedDisclosure(
            code = requireNotNull(it.code?.takeIf { c -> c.isNotBlank() }) { "disclosures[].code is required" },
            version = requireNotNull(it.version?.takeIf { v -> v.isNotBlank() }) {
                "disclosures[].version is required"
            },
            sha256 = requireNotNull(it.sha256?.takeIf { s -> s.isNotBlank() }) { "disclosures[].sha256 is required" },
        )
    }
}

/** The agreement as the case records it; [acceptedByParties] lists every signer who accepted the annexes. */
data class AgreementSummary(
    val documentId: UUID,
    val ceremonyId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val lang: String,
    val acceptedDisclosures: List<AcceptedDisclosure>,
    val acceptedAt: Instant?,
    val acceptedBy: UUID?,
    val acceptedByParties: List<UUID>,
) {
    companion object {
        fun from(a: AgreementRecord) = AgreementSummary(
            documentId = a.documentId,
            ceremonyId = a.ceremonyId,
            templateCode = a.templateCode,
            templateVersion = a.templateVersion,
            sha256 = a.sha256,
            lang = a.lang,
            acceptedDisclosures = a.acceptedDisclosures,
            acceptedAt = a.acceptedAt,
            acceptedBy = a.acceptedBy,
            acceptedByParties = a.acceptances.map { it.partyId },
        )
    }
}

/** The document-service `BusinessAgreement`, passed through with the spec's field names. */
data class BusinessAgreementResponse(
    val caseId: UUID,
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val sealedSha256: String?,
    val ceremonyId: UUID,
    val ceremonyStatus: String,
    val signers: List<Map<String, Any?>>,
    val disclosures: List<Map<String, Any?>>,
) {
    companion object {
        fun from(v: BusinessAgreementView) = BusinessAgreementResponse(
            caseId = v.caseId,
            documentId = v.documentId,
            templateCode = v.templateCode,
            templateVersion = v.templateVersion,
            sha256 = v.sha256,
            sealedSha256 = v.sealedSha256,
            ceremonyId = v.ceremonyId,
            ceremonyStatus = v.ceremonyStatus,
            signers = v.signers.map {
                mapOf("partyRef" to it.partyRef, "status" to it.status, "signedAt" to it.signedAt)
            },
            disclosures = v.disclosures.map {
                mapOf(
                    "code" to it.code,
                    "version" to it.version,
                    "title" to it.title,
                    "sha256" to it.sha256,
                    "documentId" to it.documentId,
                )
            },
        )
    }
}

/**
 * A person already known as a customer. [pep] is rendered only to that person themself: another
 * signer's PEP status is their personal data. [pepOnFile] tells the app, for everyone, that the
 * bank already holds the fact and must not ask for it.
 */
data class KnownPersonResponse(val name: String, val partyId: UUID, val pep: Boolean?, val pepOnFile: Boolean)

data class QuestionnairePrefillResponse(
    val knownPersons: List<KnownPersonResponse>,
    val previousQuestionnaire: Questionnaire?,
) {
    companion object {
        fun from(p: QuestionnairePrefill, viewer: UUID) = QuestionnairePrefillResponse(
            knownPersons = p.knownPersons.map {
                KnownPersonResponse(
                    name = it.name,
                    partyId = it.partyId,
                    pep = if (it.partyId == viewer) it.pep else null,
                    pepOnFile = it.pep != null,
                )
            },
            previousQuestionnaire = p.previousQuestionnaire,
        )
    }
}
