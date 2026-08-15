// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

/**
 * What can start a party's journey, other than a human pressing enrol.
 *
 * A campaign had two ways in: `POST /{id}/enrol` and, since the scheduling slice, a cadence. Both
 * are *pulls* — they ask the segment who qualifies right now. A trigger is the push: the platform
 * says a party just did something, and any campaign waiting for that event enrols them within
 * seconds instead of at 09:00 tomorrow. "Welcome someone who just opened an account" is only
 * honest as a trigger; as a daily sweep it arrives a day late.
 *
 * **A trigger decides WHEN, never WHO.** The segment still decides who: an event only enrols a
 * party that is also in the campaign's segment at that moment. Skipping that check would let any
 * party who performed the action into a campaign whose audience was approved as something
 * narrower — the segment is the approved audience (ADR-0201 D1), and a trigger must not be a way
 * around it.
 *
 * **The same catalogue discipline as everywhere else** ([ConversionCatalog], [SegmentCatalog],
 * [TemplateCatalog], [ScheduleCatalog]): a closed set in reviewed code, extended by pull request.
 * A topic nobody publishes would look configured and fire never.
 *
 * **Why the entries mirror [ConversionCatalog]'s topics.** The same product events that prove a
 * campaign worked are the ones worth reacting to, so the two catalogues name the same streams
 * today. They are deliberately separate types rather than one shared enum: a conversion is an
 * outcome being *measured* and can only ever append a row, while a trigger *causes* a send. Fusing
 * them would make it a one-word edit to turn a measurement into an outbound message.
 */
object TriggerCatalog {

    /**
     * @param topic the Kafka topic the event arrives on.
     * @param eventTypes accepted identifiers, matched against the `ce-type` header OR the payload's
     *   own `eventType` field. Both, for the reason [ConversionCatalog] documents: `AccountCreated`
     *   carries its type in the payload, `CardIssued` only in the outbox header, and
     *   `openbank.cards.events` is a shared topic where matching the topic alone would enrol a
     *   party on a limit change.
     */
    data class Trigger(
        val topic: String,
        val eventTypes: Set<String>,
        /** The sentence an operator approves; topic and event type are integration detail. */
        val humanForm: String,
    )

    val ALL: Map<String, Trigger> = mapOf(
        "ACCOUNT_OPENED" to Trigger(
            topic = "openbank.accounts.account.created",
            eventTypes = setOf("AccountCreated", "account.created.v1"),
            humanForm = "when an account is opened",
        ),
        "CARD_ISSUED" to Trigger(
            topic = "openbank.cards.events",
            eventTypes = setOf("card.issued.v1", "CardIssued"),
            humanForm = "when a card is issued",
        ),
    )

    fun exists(trigger: String): Boolean = trigger in ALL

    operator fun get(trigger: String): Trigger? = ALL[trigger]

    /** Triggers watching [topic] whose accepted types include [eventType]. */
    fun matching(topic: String, eventType: String?): Set<String> =
        ALL.filterValues { it.topic == topic && eventType != null && eventType in it.eventTypes }.keys
}
