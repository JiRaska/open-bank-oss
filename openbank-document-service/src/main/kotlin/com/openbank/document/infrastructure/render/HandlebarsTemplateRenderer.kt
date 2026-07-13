// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.github.jknack.handlebars.Handlebars
import com.openbank.document.application.port.out.TemplateRenderPort
import com.openbank.document.domain.model.DocumentTemplate
import jakarta.enterprise.context.ApplicationScoped

/**
 * Logic-less `{{token}}` templating via Handlebars.java (ADR-0162 D2).
 *
 * This [handlebars] instance never has a custom helper registered
 * (`registerHelper`/`registerHelpers` are never called anywhere in this class) — that, not the
 * library choice itself, is what keeps rendering free of arbitrary code execution: Handlebars'
 * built-in expression set is data-substitution and iteration/conditionals only (`{{#each}}`,
 * `{{#if}}`), with no way to reach the JVM, the filesystem, or reflection from template markup.
 * Non-engineers author template bodies via the admin-ui graphical editor (ADR-0162 D6), so this
 * is the server-side-template-injection boundary: a template body is trusted *content*, but the
 * *data* merged into it is not, and default `{{var}}` output is HTML-entity-escaped (Handlebars'
 * default [com.github.jknack.handlebars.EscapingStrategy.HTML_ENTITY]) unless a template author
 * deliberately opts out with the triple-stash `{{{var}}}`.
 *
 * Templates are compiled inline per render rather than cached/precompiled: template bodies are
 * versioned and immutable once `PUBLISHED` (ADR-0162 D2), so a cache would only save Handlebars'
 * parse cost, not correctness — a follow-up if render volume ever makes that parse cost matter.
 */
@ApplicationScoped
class HandlebarsTemplateRenderer : TemplateRenderPort {

    private val handlebars = Handlebars()

    override fun renderHtml(template: DocumentTemplate, data: Map<String, Any?>): String =
        handlebars.compileInline(template.bodyHtml).apply(data)
}
