// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.docs

// ADR-0076 Phase 3 — Docs-as-Service schema guard.
//
// Verifies that DocsResource.IndexPayload has the required fields that the
// admin UI consumer depends on (ADR-0019 / ADR-0076). Any breaking change to
// the DocsResource wire contract (renaming a field, removing `schema`, changing
// item structure) causes a compile or assertion failure here, catching the
// breakage at the provider (openbank-libs CI) before it silently breaks the
// admin UI page.
//
// This is a plain unit test (no Quarkus bootstrap) — it tests the contract
// shape, not HTTP routing. The companion integration guard in the admin UI
// (docs-route.test.ts, Layer 1) tests the consumer side.

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocsResourceSchemaGuardTest {

    /** A minimal catalog with two docs (README + one section) in two languages. */
    private fun catalog(lang: String = "en") = DocsCatalog(
        mapOf(
            "README" to mapOf(
                "en" to "# Test Service\nOverview.",
                "cs" to "# Test Service\nPřehled.",
            ),
            "01-overview" to mapOf(
                "en" to "# Overview\nDetails.",
                "cs" to "# Přehled\nPodrobnosti.",
            ),
        ),
    )

    @Test
    fun `index() returns non-empty items when catalog has content`() {
        val resource = DocsResource(catalog(), serviceName = "test-service", serviceVersion = "1.0.0")
        val payload = resource.index(langParam = "en", acceptLanguage = null)

        // schema field must follow "openbank.docs.vN" convention —
        // if the field is renamed or its format changes, this catches it.
        assertThat(payload.schema)
            .startsWith("openbank.docs.")
            .isNotBlank()
        assertThat(payload.service).isEqualTo("test-service")
        assertThat(payload.version).isEqualTo("1.0.0")
        assertThat(payload.available).isTrue()
        assertThat(payload.items).isNotEmpty()
    }

    @Test
    fun `each item has the slug, lang, title and availableLanguages fields the admin UI expects`() {
        val resource = DocsResource(catalog(), serviceName = "test-service", serviceVersion = "1.0.0")
        val payload = resource.index(langParam = "en", acceptLanguage = null)

        for (item in payload.items) {
            assertThat(item.slug)
                .describedAs("item.slug must be non-blank")
                .isNotBlank()
            assertThat(item.title)
                .describedAs("item.title must be non-blank")
                .isNotBlank()
            // lang may be "" for language-agnostic files — both are valid
            assertThat(item.availableLanguages)
                .describedAs("item.availableLanguages must be non-null")
                .isNotNull()
        }
    }

    @Test
    fun `index() available=false and empty items when catalog is empty`() {
        val resource = DocsResource(DocsCatalog(emptyMap()), serviceName = "test-service", serviceVersion = "0.0.0")
        val payload = resource.index(langParam = "en", acceptLanguage = null)

        assertThat(payload.available).isFalse()
        assertThat(payload.items).isEmpty()
        assertThat(payload.schema).startsWith("openbank.docs.")
    }

    @Test
    fun `index() returns cs items when lang=cs is requested`() {
        val resource = DocsResource(catalog(), serviceName = "test-service", serviceVersion = "1.0.0")
        val payload = resource.index(langParam = "cs", acceptLanguage = null)

        assertThat(payload.requestedLang).isEqualTo("cs")
        assertThat(payload.availableLanguages).containsExactlyInAnyOrder("en", "cs")
    }

    @Test
    fun `links map contains expected well-known keys`() {
        val resource = DocsResource(catalog(), serviceName = "test-service", serviceVersion = "1.0.0")
        val payload = resource.index(langParam = "en", acceptLanguage = null)

        // These keys are read by the admin UI to render well-known endpoint chips.
        assertThat(payload.links.keys)
            .contains("openapi", "swagger", "health", "metrics", "info")
    }
}
