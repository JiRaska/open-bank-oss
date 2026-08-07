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

/**
 * The channel/template agreement (ADR-0200 D7 as it now stands: EMAIL + PUSH).
 *
 * Both directions matter and both fail silently in production. An EMAIL step naming a push template
 * renders a one-line title as an entire email; a PUSH step naming an email template puts offer body
 * copy into an APNs payload, which is the leak #1182 closed by making push bodies generic.
 */
class CampaignStepChannelTest {

    @Test
    fun `a push step may use a push template`() {
        val step = CampaignStep(
            order = 1,
            template = "MARKETING_PRODUCT_OFFER_PUSH",
            channel = Channel.PUSH,
            variables = mapOf("offerTitle" to "Savings at 4%"),
            delaySeconds = 0,
        )
        assertEquals(Channel.PUSH, step.channel)
    }

    @Test
    fun `an email template on a push step is refused`() {
        val e = assertThrows<IllegalArgumentException> {
            CampaignStep(
                order = 1,
                template = "MARKETING_PRODUCT_OFFER",
                channel = Channel.PUSH,
                variables = emptyMap(),
                delaySeconds = 0,
            )
        }
        assertTrue(e.message!!.contains("renders on EMAIL"), e.message)
    }

    @Test
    fun `a push template on an email step is refused`() {
        assertThrows<IllegalArgumentException> {
            CampaignStep(
                order = 1,
                template = "MARKETING_PRODUCT_OFFER_PUSH",
                channel = Channel.EMAIL,
                variables = emptyMap(),
                delaySeconds = 0,
            )
        }
    }

    @Test
    fun `a push template declares only its title — body copy cannot be smuggled in`() {
        // notification-service renders a fixed generic body for every push (GENERIC_PUSH_BODY), so a
        // template that declared body text would promise something the channel refuses to deliver.
        assertEquals(setOf("offerTitle"), TemplateCatalog.ALL["MARKETING_PRODUCT_OFFER_PUSH"])
        assertThrows<IllegalArgumentException> {
            CampaignStep(
                order = 1,
                template = "MARKETING_PRODUCT_OFFER_PUSH",
                channel = Channel.PUSH,
                variables = mapOf("offerTitle" to "t", "offerText" to "body copy"),
                delaySeconds = 0,
            )
        }
    }

    @Test
    fun `the catalogue offers exactly the templates a channel can render`() {
        assertEquals(setOf("MARKETING_PRODUCT_OFFER"), TemplateCatalog.forChannel(Channel.EMAIL))
        assertEquals(setOf("MARKETING_PRODUCT_OFFER_PUSH"), TemplateCatalog.forChannel(Channel.PUSH))
    }
}
