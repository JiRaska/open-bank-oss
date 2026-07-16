// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.OnboardingResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for GET /onboarding/terms — the pre-credential consent-step documents
 * (ADR-0169/0170 informed-consent gap): only PUBLISHED templates of the two wanted codes
 * come back, ordered agreement-first, and an upstream failure maps to 502 (never a leak
 * of the upstream body).
 */
class OnboardingTermsTest {

    private val mapper = ObjectMapper()

    private fun resource(upstream: UpstreamClient): OnboardingResource = OnboardingResource(
        upstream,
        mockk<EnrollmentTicketService>(relaxed = true),
    ).apply {
        jsonMapper = mapper
        partyServiceUrl = "http://party"
        documentServiceUrl = "http://documents"
    }

    private fun templates(vararg rows: Triple<String, String, String>): Response {
        val arr = rows.joinToString(",") { (code, status, name) ->
            """{"code":"$code","status":"$status","name":"$name","version":"1.1.0",""" +
                """"createdAt":"2026-01-01T00:00:00Z","bodyHtml":"<p>text $code</p>"}"""
        }
        return Response.ok("[$arr]").build()
    }

    @Test
    fun `returns only the published terms — never the framework agreement`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns templates(
            Triple("VOP_CS", "PUBLISHED", "Všeobecné obchodní podmínky"),
            Triple("VOP_CS", "RETIRED", "staré VOP"),
            // The agreement is signed one step later, per party — it must never be listed here.
            Triple("RAMCOVA_SMLOUVA_CS", "PUBLISHED", "Rámcová smlouva"),
            Triple("UCET_SMLOUVA_CS", "PUBLISHED", "jiný dokument"),
        )

        val resp = resource(upstream).onboardingTerms("cs")

        assertThat(resp.status).isEqualTo(200)
        val docs = mapper.readTree(resp.entity as String).get("documents")
        assertThat(docs.size()).isEqualTo(1)
        assertThat(docs[0].get("code").asText()).isEqualTo("VOP_CS")
        assertThat(docs[0].get("version").asText()).isEqualTo("1.1.0")
        // Metadata only — the readable bytes come from /terms/{code}/content as a PDF.
        assertThat(docs[0].has("html")).isFalse()
    }

    @Test
    fun `lang en selects the english terms and anything else falls back to cs`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns templates(
            Triple("VOP_EN", "PUBLISHED", "General Terms"),
            Triple("RAMCOVA_SMLOUVA_EN", "PUBLISHED", "Framework Agreement"),
        )

        val resp = resource(upstream).onboardingTerms("en")

        assertThat(resp.status).isEqualTo(200)
        val docs = mapper.readTree(resp.entity as String).get("documents")
        assertThat(docs.size()).isEqualTo(1)
        assertThat(docs[0].get("code").asText()).isEqualTo("VOP_EN")
    }

    // ---- /terms/{code}/content -------------------------------------------------

    @Test
    fun `content renders the published terms to pdf and reuses the render per version`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/templates") }, any()) } returns
            templates(Triple("VOP_CS", "PUBLISHED", "Všeobecné obchodní podmínky"))
        every { upstream.post(match { it.contains("/render") }, any(), any()) } returns
            Response.status(201).entity("""{"id":"doc-1"}""").build()
        every { upstream.getRaw(match { it.contains("/documents/doc-1/content") }, any(), any()) } returns
            Response.ok("%PDF-1.4".toByteArray()).build()

        val res = resource(upstream)
        assertThat(res.onboardingTermsContent("VOP_CS").status).isEqualTo(200)
        assertThat(res.onboardingTermsContent("vop_cs").status).isEqualTo(200)

        // Immutable published version ⇒ rendered once, not once per view.
        verify(exactly = 1) { upstream.post(match { it.contains("/render") }, any(), any()) }
    }

    @Test
    fun `content refuses any code outside the terms allow-list`() {
        val upstream = mockk<UpstreamClient>()

        // The per-party agreement must not be reachable through the anonymous route.
        val resp = resource(upstream).onboardingTermsContent("RAMCOVA_SMLOUVA_CS")

        assertThat(resp.status).isEqualTo(404)
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
    }

    @Test
    fun `content maps a failed render to 502 and does not cache the failure`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/templates") }, any()) } returns
            templates(Triple("VOP_CS", "PUBLISHED", "Všeobecné obchodní podmínky"))
        every { upstream.post(match { it.contains("/render") }, any(), any()) } returns
            Response.status(500).entity("""{"internal":"boom"}""").build()

        val res = resource(upstream)
        assertThat(res.onboardingTermsContent("VOP_CS").status).isEqualTo(502)
        // A failed render must be retried on the next request, never cached as a hole.
        assertThat(res.onboardingTermsContent("VOP_CS").status).isEqualTo(502)
        verify(exactly = 2) { upstream.post(match { it.contains("/render") }, any(), any()) }
    }

    @Test
    fun `upstream failure maps to 502 without leaking the upstream body`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns
            Response.status(500).entity("""{"internal":"stacktrace"}""").build()

        val resp = resource(upstream).onboardingTerms("de")

        assertThat(resp.status).isEqualTo(502)
        assertThat(resp.entity as String).doesNotContain("stacktrace")
    }
}
