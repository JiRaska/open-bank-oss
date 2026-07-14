// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.mapper

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.Signer
import com.openbank.document.infrastructure.persistence.entity.DocumentEntity
import com.openbank.document.infrastructure.persistence.entity.DocumentTemplateEntity
import com.openbank.document.infrastructure.persistence.entity.SignatureCeremonyEntity

fun DocumentTemplateEntity.toDomain() = DocumentTemplate(
    id = id,
    code = code,
    version = version,
    name = name,
    engine = engine,
    bodyHtml = bodyHtml,
    locale = locale,
    status = status,
    productRef = productRef,
    classification = classification,
    createdAt = createdAt,
    createdBy = createdBy,
)

fun DocumentTemplate.toEntity() = DocumentTemplateEntity().also {
    it.id = id
    it.code = code
    it.version = version
    it.name = name
    it.engine = engine
    it.bodyHtml = bodyHtml
    it.locale = locale
    it.status = status
    it.productRef = productRef
    it.classification = classification
    it.createdAt = createdAt
    it.createdBy = createdBy
}

fun DocumentEntity.toDomain(mapper: ObjectMapper) = Document(
    id = id,
    templateCode = templateCode,
    templateVersion = templateVersion,
    sha256 = sha256,
    storageKey = storageKey,
    contentType = contentType,
    sizeBytes = sizeBytes,
    status = status,
    metadata = mapper.readValue(metadataJson, object : TypeReference<Map<String, String>>() {}),
    partyRef = partyRef,
    caseRef = caseRef,
    productRef = productRef,
    retainUntil = retainUntil,
    createdAt = createdAt,
)

fun Document.toEntity(mapper: ObjectMapper) = DocumentEntity().also {
    it.id = id
    it.templateCode = templateCode
    it.templateVersion = templateVersion
    it.sha256 = sha256
    it.storageKey = storageKey
    it.contentType = contentType
    it.sizeBytes = sizeBytes
    it.status = status
    it.metadataJson = mapper.writeValueAsString(metadata)
    it.partyRef = partyRef
    it.caseRef = caseRef
    it.productRef = productRef
    it.retainUntil = retainUntil
    it.createdAt = createdAt
}

fun SignatureCeremonyEntity.toDomain(mapper: ObjectMapper) = SignatureCeremony(
    id = id,
    documentId = documentId,
    signers = mapper.readValue(signersJson, object : TypeReference<List<Signer>>() {}),
    status = status,
    signatureLevel = signatureLevel,
    createdAt = createdAt,
    version = version,
)

fun SignatureCeremony.toEntity(mapper: ObjectMapper) = SignatureCeremonyEntity().also {
    it.id = id
    it.documentId = documentId
    it.signersJson = mapper.writeValueAsString(signers)
    it.status = status
    it.signatureLevel = signatureLevel
    it.createdAt = createdAt
    it.version = version
}
