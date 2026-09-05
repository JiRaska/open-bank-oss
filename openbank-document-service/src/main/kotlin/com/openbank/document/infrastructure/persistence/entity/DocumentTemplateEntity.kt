// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.entity

import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "document_templates")
class DocumentTemplateEntity {
    @Id
    var id: UUID = Ids.newId()

    var code: String = ""

    var version: String = ""

    var name: String = ""

    @Enumerated(EnumType.STRING)
    var engine: TemplateEngine = TemplateEngine.HANDLEBARS

    @field:Column(name = "body_html", columnDefinition = "TEXT")
    var bodyHtml: String = ""

    var locale: String = "en"

    @Enumerated(EnumType.STRING)
    var status: TemplateStatus = TemplateStatus.DRAFT

    @field:Column(name = "product_ref")
    var productRef: String? = null

    var classification: String = "restricted"

    // Never Instant.EPOCH (#3882): a defaulted EPOCH survives every isNotNull() check and
    // reads as 1970 in audit/sort paths. Entities are populated via `.also {}` right after
    // construction, so now() is only the never-silent pre-population value.
    @field:Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @field:Column(name = "created_by")
    var createdBy: String = "system"
}
