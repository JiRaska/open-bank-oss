// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.TemplateCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ADR-0221 D1: a step is composed from the template catalogue with declared variables — "there is
 * no free-text field anywhere in this step". `CampaignStep.template` was a plain `String`, so a
 * step could name a template nobody renders, or pass a variable nobody declared, and the first
 * thing to notice was the notification being composed — after approval, in front of customers.
 */
class TemplateCatalogTest {

    private val offer = "MARKETING_PRODUCT_OFFER"
    private val goodVars = mapOf("offerTitle" to "T", "offerText" to "X", "ctaText" to "Go")

    private fun step(template: String, variables: Map<String, String>) =
        CampaignStep(order = 1, template = template, channel = Channel.EMAIL, variables = variables, delaySeconds = 0)

    @Test
    fun `a catalogue template with its declared variables is accepted`() {
        assertEquals(offer, step(offer, goodVars).template)
    }

    @Test
    fun `a template that is not in the catalogue is rejected at construction`() {
        val error = assertThrows<IllegalArgumentException> { step("FREESTYLE_BLAST", goodVars) }

        assertTrue(error.message!!.contains("unknown template"))
    }

    /**
     * The narrowing that matters: the notification catalogue also holds SECURITY templates, and a
     * customer cannot mute those. Letting a campaign address one would put an unmutable message
     * behind a marketing consent check — two opposite rules about whether it may be suppressed.
     */
    @Test
    fun `a real but non-marketing template is still rejected`() {
        assertThrows<IllegalArgumentException> { step("OTP_CODE", mapOf("code" to "123456")) }
    }

    @Test
    fun `a variable the template does not declare is rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            step(offer, goodVars + ("bodyHtml" to "<b>hi</b>"))
        }

        assertTrue(error.message!!.contains("bodyHtml"))
    }

    /**
     * The domain accepts an incomplete step on purpose: requiring every declared variable would be
     * stricter than the renderer (which only rejects *unknown* keys) and would make a
     * partially-filled draft unrepresentable. Completeness is an authoring rule — the wizard blocks
     * submit until every declared variable has a value.
     */
    @Test
    fun `a step missing a declared variable is still constructible`() {
        assertEquals(setOf("ctaText"), TemplateCatalog.missingVariables(offer, goodVars - "ctaText"))
        assertEquals(offer, step(offer, goodVars - "ctaText").template)
    }

    @Test
    fun `the catalogue reports both directions of variable mismatch`() {
        assertEquals(setOf("nope"), TemplateCatalog.unknownVariables(offer, mapOf("nope" to "1")))
        assertEquals(
            setOf("offerTitle", "offerText", "ctaText"),
            TemplateCatalog.missingVariables(offer, emptyMap()),
        )
    }
}
