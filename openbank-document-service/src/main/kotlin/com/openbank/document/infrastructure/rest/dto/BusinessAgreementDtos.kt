// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest.dto

import com.openbank.document.application.port.`in`.AgreementEntity
import com.openbank.document.application.port.`in`.AgreementProduct
import com.openbank.document.application.port.`in`.AgreementRepresentative
import com.openbank.document.application.port.`in`.AgreementSigner
import com.openbank.document.application.port.`in`.BusinessAgreement
import com.openbank.document.application.port.`in`.EnsureBusinessAgreementCommand
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.SignerStatus
import java.time.Instant
import java.util.UUID

// Every field is nullable because that is the truth on the wire (see OpenCeremonyRequest): the
// guards in toCommand() then answer 400 through libs-runtime's IllegalArgumentException mapper
// instead of a Jackson failure or an NPE deeper in.

data class BusinessEntityDto(val name: String?, val ico: String?, val seat: String?, val legalForm: String?)

data class BusinessRepresentativeDto(val partyRef: String?, val name: String?, val role: String?)

data class BusinessSignerDto(val partyRef: String?, val name: String?, val role: String?)

data class BusinessProductDto(val code: String?, val name: String?, val currency: String?)

data class EnsureBusinessAgreementRequest(
    val caseId: UUID?,
    val entityPartyId: UUID?,
    val lang: String?,
    val entity: BusinessEntityDto?,
    val representatives: List<BusinessRepresentativeDto?>? = emptyList(),
    val signers: List<BusinessSignerDto?>?,
    val signingRule: String?,
    val product: BusinessProductDto?,
) {
    fun toCommand(): EnsureBusinessAgreementCommand {
        val entity = requireNotNull(entity) { "entity is required" }
        val product = requireNotNull(product) { "product is required" }
        return EnsureBusinessAgreementCommand(
            caseId = requireNotNull(caseId) { "caseId is required" },
            entityPartyId = requireNotNull(entityPartyId) { "entityPartyId is required" },
            lang = requireNotNull(lang) { "lang is required" },
            entity = AgreementEntity(
                name = entity.name.orEmpty(),
                ico = entity.ico.orEmpty(),
                seat = entity.seat.orEmpty(),
                legalForm = entity.legalForm.orEmpty(),
            ),
            representatives = representatives.orEmpty().mapIndexed { i, r ->
                val rep = requireNotNull(r) { "representatives[$i] must not be null" }
                AgreementRepresentative(
                    rep.partyRef?.takeIf {
                        it.isNotBlank()
                    },
                    rep.name.orEmpty(),
                    rep.role.orEmpty(),
                )
            },
            signers = requireNotNull(signers) { "signers is required" }.mapIndexed { i, s ->
                val signer = requireNotNull(s) { "signers[$i] must not be null" }
                AgreementSigner(
                    partyRef = requireNotNull(signer.partyRef) { "signers[$i].partyRef is required" },
                    name = signer.name.orEmpty(),
                    role = signer.role.orEmpty(),
                )
            },
            signingRule = signingRule.orEmpty(),
            product = AgreementProduct(product.code.orEmpty(), product.name.orEmpty(), product.currency.orEmpty()),
        )
    }
}

data class BusinessAgreementSignerResponse(val partyRef: String, val status: SignerStatus, val signedAt: Instant?)

data class BusinessDisclosureResponse(
    val code: String,
    val version: String,
    val title: String,
    val sha256: String,
    val documentId: UUID,
)

data class BusinessAgreementResponse(
    val caseId: UUID,
    val documentId: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val sealedSha256: String?,
    val ceremonyId: UUID,
    val ceremonyStatus: CeremonyStatus,
    val signers: List<BusinessAgreementSignerResponse>,
    val disclosures: List<BusinessDisclosureResponse>,
)

fun BusinessAgreement.toResponse() = BusinessAgreementResponse(
    caseId = caseId,
    documentId = documentId,
    templateCode = templateCode,
    templateVersion = templateVersion,
    sha256 = sha256,
    sealedSha256 = sealedSha256,
    ceremonyId = ceremonyId,
    ceremonyStatus = ceremonyStatus,
    signers = signers.map { BusinessAgreementSignerResponse(it.partyRef, it.status, it.signedAt) },
    disclosures = disclosures.map {
        BusinessDisclosureResponse(it.code, it.version, it.title, it.sha256, it.documentId)
    },
)
