// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.model

import java.time.Instant
import java.util.UUID

/** Rendering engine a template body is authored for. HANDLEBARS is the phase-1 placeholder engine. */
enum class TemplateEngine { HANDLEBARS, }

/** Publication lifecycle of a template. Only PUBLISHED templates may render live documents. */
enum class TemplateStatus { DRAFT, PUBLISHED, RETIRED }

/**
 * A versioned document template — the authored, engine-specific body plus its publication metadata.
 * Pure domain aggregate: no framework imports (ADR-0002).
 */
data class DocumentTemplate(
    val id: UUID,
    val code: String,
    val version: String,
    val name: String,
    val engine: TemplateEngine,
    val bodyHtml: String,
    val locale: String,
    val status: TemplateStatus,
    val productRef: String?,
    val classification: String,
    val createdAt: Instant,
    val createdBy: String,
) {
    fun publish(): DocumentTemplate {
        require(status == TemplateStatus.DRAFT) { "Only DRAFT templates can be published" }
        return copy(status = TemplateStatus.PUBLISHED)
    }

    fun retire(): DocumentTemplate {
        require(status == TemplateStatus.PUBLISHED) { "Only PUBLISHED templates can be retired" }
        return copy(status = TemplateStatus.RETIRED)
    }

    fun renderable(): Boolean = status == TemplateStatus.PUBLISHED
}
