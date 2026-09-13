// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.entity

import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "documents")
class DocumentEntity {
    @Id
    var id: UUID = Ids.newId()

    @field:Column(name = "template_code")
    var templateCode: String = ""

    @field:Column(name = "template_version")
    var templateVersion: String = ""

    var sha256: String = ""

    @field:Column(name = "storage_key")
    var storageKey: String = ""

    @field:Column(name = "content_type")
    var contentType: String = "application/pdf"

    @field:Column(name = "size_bytes")
    var sizeBytes: Long = 0

    @Enumerated(EnumType.STRING)
    var status: DocumentStatus = DocumentStatus.GENERATED

    @field:Column(name = "metadata_json", columnDefinition = "TEXT")
    var metadataJson: String = "{}"

    @field:Column(name = "party_ref")
    var partyRef: String? = null

    @field:Column(name = "case_ref")
    var caseRef: String? = null

    @field:Column(name = "product_ref")
    var productRef: String? = null

    @field:Column(name = "idempotency_key")
    var idempotencyKey: String? = null

    @field:Column(name = "retain_until")
    var retainUntil: LocalDate? = null

    // Never Instant.EPOCH (#3882): a defaulted EPOCH survives every isNotNull() check and
    // reads as 1970 in audit/sort paths. Entities are populated via `.also {}` right after
    // construction, so now() is only the never-silent pre-population value.
    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()
}
