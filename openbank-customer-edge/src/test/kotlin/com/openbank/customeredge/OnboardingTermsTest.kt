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
    fun `returns published agreement and terms in stable order`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns templates(
            Triple("VOP_CS", "PUBLISHED", "Všeobecné obchodní podmínky"),
            Triple("RAMCOVA_SMLOUVA_CS", "RETIRED", "stará"),
            Triple("RAMCOVA_SMLOUVA_CS", "PUBLISHED", "Rámcová smlouva"),
            Triple("UCET_SMLOUVA_CS", "PUBLISHED", "jiný dokument"),
        )

        val resp = resource(upstream).onboardingTerms("cs")

        assertThat(resp.status).isEqualTo(200)
        val docs = mapper.readTree(resp.entity as String).get("documents")
        assertThat(docs.size()).isEqualTo(2)
        assertThat(docs[0].get("code").asText()).isEqualTo("RAMCOVA_SMLOUVA_CS")
        assertThat(docs[1].get("code").asText()).isEqualTo("VOP_CS")
        assertThat(docs[0].get("html").asText()).contains("text RAMCOVA_SMLOUVA_CS")
        assertThat(docs[0].get("version").asText()).isEqualTo("1.1.0")
    }

    @Test
    fun `lang en selects the english templates and anything else falls back to cs`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns templates(
            Triple("RAMCOVA_SMLOUVA_EN", "PUBLISHED", "Framework Agreement"),
            Triple("VOP_EN", "PUBLISHED", "General Terms"),
        )

        val resp = resource(upstream).onboardingTerms("en")

        assertThat(resp.status).isEqualTo(200)
        val docs = mapper.readTree(resp.entity as String).get("documents")
        assertThat(docs.size()).isEqualTo(2)
        assertThat(docs[0].get("code").asText()).isEqualTo("RAMCOVA_SMLOUVA_EN")
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
