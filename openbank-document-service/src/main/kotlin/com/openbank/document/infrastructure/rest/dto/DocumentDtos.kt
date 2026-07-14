// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest.dto

import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateTemplateRequest(
    val code: String,
    val version: String,
    val name: String,
    val engine: TemplateEngine = TemplateEngine.HANDLEBARS,
    val bodyHtml: String,
    val locale: String = "en",
    val productRef: String? = null,
    val classification: String = "restricted",
    val createdBy: String = "system",
)

data class PreviewTemplateRequest(
    val bodyHtml: String,
    val data: Map<String, Any?> = emptyMap(),
)

data class PreviewTemplateResponse(
    val renderedHtml: String,
)

data class TemplateResponse(
    val id: UUID,
    val code: String,
    val version: String,
    val name: String,
    val engine: TemplateEngine,
    val locale: String,
    val status: TemplateStatus,
    val productRef: String?,
    val classification: String,
    val createdAt: Instant,
)

data class RenderDocumentRequest(
    val templateCode: String,
    val templateVersion: String,
    val data: Map<String, Any?> = emptyMap(),
    val contentType: String = "application/pdf",
    val partyRef: String? = null,
    val caseRef: String? = null,
    val productRef: String? = null,
    val retainUntil: LocalDate? = null,
)

data class DocumentResponse(
    val id: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val status: DocumentStatus,
    val metadata: Map<String, String>,
    val partyRef: String?,
    val caseRef: String?,
    val productRef: String?,
    val retainUntil: LocalDate?,
    val createdAt: Instant,
)

fun DocumentTemplate.toResponse() = TemplateResponse(
    id = id,
    code = code,
    version = version,
    name = name,
    engine = engine,
    locale = locale,
    status = status,
    productRef = productRef,
    classification = classification,
    createdAt = createdAt,
)

fun Document.toResponse() = DocumentResponse(
    id = id,
    templateCode = templateCode,
    templateVersion = templateVersion,
    sha256 = sha256,
    storageKey = storageKey,
    contentType = contentType,
    sizeBytes = sizeBytes,
    status = status,
    metadata = metadata,
    partyRef = partyRef,
    caseRef = caseRef,
    productRef = productRef,
    retainUntil = retainUntil,
    createdAt = createdAt,
)
