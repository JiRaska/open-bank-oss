// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

import java.time.Duration

/**
 * What it means for a campaign to have worked (ADR-0245).
 *
 * A campaign's `goal` is a sentence for humans and stays that way. This catalogue is the machine
 * side: a closed set of rules over product events the platform **already publishes**, so conversion
 * is *observed* rather than declared. Adding a rule is a pull request against this file — the same
 * discipline [SegmentCatalog] applies to audiences and [TemplateCatalog] to copy, and for the same
 * reason: a definition that can be typed into a text box cannot be reviewed, versioned or diffed.
 *
 * **What is deliberately not here** (ADR-0245 D5): opens, clicks and impressions. No tracking pixel,
 * no link-wrapping redirector, no beacon. That telemetry measures attention rather than outcome, an
 * email "open" is unreliable by construction because image proxies fetch pixels nobody saw, and it
 * would acquire behavioural data the bank has no need for and no basis to hold. A bank does not need
 * to know that someone looked; it needs to know that someone opened the savings account.
 *
 * **Why each rule carries a set of identifiers rather than one string.** The two producers put the
 * event type in different places, and a consumer that knew about only one would silently match
 * nothing. `AccountCreatedEvent` serialises its own `eventType` into the payload (it is an
 * `override val` on `DomainEvent`), while `CardIssued` carries no such field and the type travels
 * only as the outbox's `ce-type` Kafka header (`OutboxKafkaHeaders.HEADER_EVENT_TYPE`). Matching
 * either is what makes both rules work; matching payload alone would break cards, and matching
 * header alone is untested for accounts.
 *
 * `openbank.cards.events` is a SHARED topic carrying several card event types, which is why the
 * identifier check is not optional: without it a limit change would count as a conversion.
 */
object ConversionCatalog {

    /**
     * @param topic the Kafka topic the event arrives on.
     * @param eventTypes accepted identifiers, matched against the `ce-type` header OR the payload's
     *   own `eventType` field — see the class KDoc for why both.
     * @param attributionWindow how long after a send an event still counts. Per rule rather than a
     *   global constant: opening an account is a considered decision that can take weeks, and
     *   pretending one number fits every product is how attribution silently becomes fiction.
     */
    data class Rule(val topic: String, val eventTypes: Set<String>, val attributionWindow: Duration) {
        fun matches(eventType: String?): Boolean = eventType != null && eventType in eventTypes
    }

    /**
     * The catalogue. Two entries, both naming topics that exist in the tree today — a rule pointing
     * at a topic nobody publishes would look configured and match nothing, which is the failure mode
     * ADR-0220 D5 calls an inauthentic placeholder.
     */
    val ALL: Map<String, Rule> = mapOf(
        "ACCOUNT_OPENED" to Rule(
            topic = "openbank.accounts.account.created",
            eventTypes = setOf("AccountCreated", "account.created.v1"),
            attributionWindow = Duration.ofDays(ACCOUNT_WINDOW_DAYS),
        ),
        "CARD_ISSUED" to Rule(
            topic = "openbank.cards.events",
            eventTypes = setOf("card.issued.v1", "CardIssued"),
            attributionWindow = Duration.ofDays(CARD_WINDOW_DAYS),
        ),
    )

    /** Opening an account is a considered decision; a month is the honest outer edge of "because". */
    private const val ACCOUNT_WINDOW_DAYS = 30L

    /** A card is a quicker decision, so a shorter window keeps the attribution defensible. */
    private const val CARD_WINDOW_DAYS = 14L

    fun exists(rule: String): Boolean = rule in ALL

    operator fun get(rule: String): Rule? = ALL[rule]

    /** Rules watching [topic] — what a consumer for that topic must evaluate. */
    fun forTopic(topic: String): Map<String, Rule> = ALL.filterValues { it.topic == topic }
}
