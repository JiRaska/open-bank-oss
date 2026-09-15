// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The security-relevant property of this renderer is that merged DATA is HTML-entity-escaped by
 * default (`{{var}}`), because template bodies are trusted content but the data merged into them
 * is not. That is the server-side-template-injection boundary described in the class KDoc — and it
 * is only a property of the default escaping strategy, which nothing else in the module asserts.
 */
class HandlebarsTemplateRendererTest {

    private val renderer = HandlebarsTemplateRenderer()

    private fun template(body: String) = DocumentTemplate(
        id = UUID.randomUUID(),
        code = "T",
        version = "1.0.0",
        name = "T",
        engine = TemplateEngine.HANDLEBARS,
        bodyHtml = body,
        locale = "en",
        status = TemplateStatus.PUBLISHED,
        productRef = null,
        classification = "restricted",
        createdAt = Instant.now(),
        createdBy = "test",
    )

    @Test
    fun `a double-stash token HTML-escapes the merged value`() {
        val html = renderer.renderHtml(template("<p>{{name}}</p>"), mapOf("name" to "<script>alert(1)</script>"))

        assertThat(html).doesNotContain("<script>")
        assertThat(html).contains("&lt;script&gt;")
    }

    @Test
    fun `a triple-stash is a deliberate opt-out and emits raw markup`() {
        val html = renderer.renderHtml(template("<p>{{{name}}}</p>"), mapOf("name" to "<b>bold</b>"))

        assertThat(html).isEqualTo("<p><b>bold</b></p>")
    }

    @Test
    fun `a nested path resolves through a map`() {
        val data = mapOf("party" to mapOf("legalName" to "Jan Novak"))

        assertThat(renderer.renderHtml(template("{{party.legalName}}"), data)).isEqualTo("Jan Novak")
    }

    @Test
    fun `a missing token renders as empty, not as the literal placeholder`() {
        assertThat(renderer.renderHtml(template("[{{absent}}]"), emptyMap())).isEqualTo("[]")
    }

    @Test
    fun `each and if are available for list and conditional sections`() {
        val data = mapOf(
            "lines" to listOf(mapOf("name" to "Fee A"), mapOf("name" to "Fee B")),
            "shown" to true,
        )

        val html = renderer.renderHtml(template("{{#if shown}}{{#each lines}}<li>{{name}}</li>{{/each}}{{/if}}"), data)

        assertThat(html).isEqualTo("<li>Fee A</li><li>Fee B</li>")
    }

    @Test
    fun `an if section over a false flag renders nothing`() {
        assertThat(renderer.renderHtml(template("{{#if shown}}X{{/if}}"), mapOf("shown" to false))).isEmpty()
    }

    @Test
    fun `a malformed template body fails loudly rather than rendering a half-document`() {
        assertThatThrownBy { renderer.renderHtml(template("{{#if x}}unclosed"), emptyMap()) }
            .isInstanceOf(Exception::class.java)
    }
}
