// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.ConversionCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The conversion catalogue (ADR-0245).
 *
 * These assert the two properties that make the difference between measuring an outcome and
 * inventing one: every rule points at a topic something actually publishes, and a shared topic
 * cannot be mistaken for a conversion just because an event arrived on it.
 */
class ConversionCatalogTest {

    @Test
    fun `a shared topic only converts on the event types the rule names`() {
        val cards = ConversionCatalog["CARD_ISSUED"]!!
        assertTrue(cards.matches("card.issued.v1"))
        // openbank.cards.events also carries status, limit and control changes. Without the check a
        // customer lowering their contactless limit would be recorded as a conversion.
        assertFalse(cards.matches("card.limits.changed.v1"))
        assertFalse(cards.matches(null))
    }

    @Test
    fun `both places a producer can put the event type are accepted`() {
        // AccountCreatedEvent serialises its own eventType into the payload; CardIssued has no such
        // field and travels only as the outbox `ce-type` header. Matching one alone silently
        // matches nothing on the other producer.
        assertTrue(ConversionCatalog["ACCOUNT_OPENED"]!!.matches("AccountCreated"))
        assertTrue(ConversionCatalog["ACCOUNT_OPENED"]!!.matches("account.created.v1"))
    }

    @Test
    fun `every rule names a distinct, non-empty topic and a bounded window`() {
        assertTrue(ConversionCatalog.ALL.isNotEmpty())
        ConversionCatalog.ALL.forEach { (key, rule) ->
            assertTrue(rule.topic.startsWith("openbank."), "$key topic looks wrong: ${rule.topic}")
            assertTrue(rule.eventTypes.isNotEmpty(), "$key names no event type")
            assertTrue(rule.attributionWindow.isPositive, "$key has a non-positive window")
            // An unbounded window would attribute a decision made a year later to a campaign that
            // sent one email — the point of stating the window per rule is that it is finite.
            assertTrue(rule.attributionWindow.toDays() <= 90, "$key window is implausibly long")
        }
    }

    @Test
    fun `forTopic returns only the rules watching that topic`() {
        assertEquals(setOf("ACCOUNT_OPENED"), ConversionCatalog.forTopic("openbank.accounts.account.created").keys)
        assertEquals(setOf("CARD_ISSUED"), ConversionCatalog.forTopic("openbank.cards.events").keys)
        assertTrue(ConversionCatalog.forTopic("openbank.nothing.here").isEmpty())
    }

    @Test
    fun `no rule names an engagement signal`() {
        // ADR-0245 D5. If someone adds a message-engagement rule, this fails and they argue with
        // the ADR rather than with a reviewer's memory of it.
        //
        // The tokens are precise on purpose. A first draft forbade the bare word "open" and
        // promptly failed on ACCOUNT_OPENED — a bank product event, not a message open. A guard
        // that cannot tell the thing from the word for the thing costs more than it catches, so
        // this names the engagement senses and nothing else.
        val forbidden = listOf("clicked", "click.", "impression", "pixel", "beacon", "message.open", "email.open")
        ConversionCatalog.ALL.forEach { (key, rule) ->
            val text = (key + rule.eventTypes.joinToString()).lowercase()
            forbidden.forEach { word ->
                assertFalse(text.contains(word), "$key names '$word' — ADR-0245 D5 forbids engagement telemetry")
            }
        }
    }
}
