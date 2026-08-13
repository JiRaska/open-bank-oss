// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

/**
 * The marketing templates a campaign step may use, and the variables each one declares.
 *
 * ADR-0221 D1 says a step is "composition from the template catalogue with declared variables …
 * there is no free-text field anywhere in this step", and ADR-0176 D4 makes that a hard rule: a
 * campaign supplies *values*, never body text. `CampaignStep.template` was a plain `String`, so
 * neither held — a step could name a template that does not exist, or pass variables the template
 * has never declared, and nothing said no until the notification was already being composed.
 *
 * Mirrors `NotificationTemplate` in openbank-notification-service, which is where the templates are
 * rendered. The duplication is deliberate: the two services do not share a module (ADR-0002 keeps
 * domains independent), and the alternative is campaign-service accepting anything and finding out
 * downstream. [MARKETING_ONLY_REASON] records why the set is narrower than that enum.
 */
object TemplateCatalog {

    /**
     * A campaign may only ever send MARKETING-category templates. Letting one address, say,
     * `OTP_CODE` would put a security message a customer cannot mute behind a marketing consent
     * check — the two have opposite rules about whether they may be suppressed.
     */
    const val MARKETING_ONLY_REASON = "a campaign may only send MARKETING-category templates"

    /** Template id -> the variables it declares. Values are supplied per step; keys are not. */
    val ALL: Map<String, Set<String>> = mapOf(
        "MARKETING_PRODUCT_OFFER" to setOf("offerTitle", "offerText", "ctaText"),
        // A push carries its template's SUBJECT as the title and a fixed generic body; the offer
        // itself is read in the app after the tap. That is not a simplification of the email
        // template — it is notification-service's rule (NotificationConsumer.GENERIC_PUSH_BODY,
        // issue #1182): customer-specific content must never reach an APNs/FCM payload, which is
        // delivered through a third party and shown on a locked screen. So this template declares
        // exactly one variable, and a campaign cannot smuggle body copy into a push by filling in
        // more.
        "MARKETING_PRODUCT_OFFER_PUSH" to setOf("offerTitle"),
        // A banner renders only in the authenticated app, so its approved card may contain the
        // offer body and CTA. It remains a closed template, never marketer-authored markup.
        "MARKETING_PRODUCT_OFFER_BANNER" to setOf("offerTitle", "offerText", "ctaText"),
    )

    /**
     * Which channel a template is rendered for.
     *
     * Kept as data rather than a naming convention: `endsWith("_PUSH")` would silently accept a
     * template someone names badly, and the failure would appear as a push that renders an email
     * body — exactly the leak #1182 closed.
     */
    val CHANNEL_OF: Map<String, Channel> = mapOf(
        "MARKETING_PRODUCT_OFFER" to Channel.EMAIL,
        "MARKETING_PRODUCT_OFFER_PUSH" to Channel.PUSH,
        "MARKETING_PRODUCT_OFFER_BANNER" to Channel.BANNER,
    )

    /**
     * A deliberately small, explicit fallback catalogue. This is not a naming convention: a
     * fallback can only use a push template that is safe to render with the values from its email
     * counterpart. The push renderer deliberately accepts only the shared headline, never the
     * e-mail body or CTA (#1182).
     */
    val PUSH_FALLBACK_FOR_EMAIL: Map<String, String> = mapOf(
        "MARKETING_PRODUCT_OFFER" to "MARKETING_PRODUCT_OFFER_PUSH",
    )

    /** Templates renderable on [channel] — what an authoring screen may offer for a step. */
    fun forChannel(channel: Channel): Set<String> = CHANNEL_OF.filterValues { it == channel }.keys

    fun exists(template: String): Boolean = template in ALL

    /**
     * Variables supplied for [template] that it does not declare. Empty = well-formed.
     *
     * Reported rather than ignored: a misspelled key silently renders an empty placeholder in a
     * customer-facing email, which looks like a content bug long after the campaign is approved.
     */
    fun unknownVariables(template: String, variables: Map<String, String>): Set<String> =
        variables.keys - (ALL[template] ?: emptySet())

    /** Variables [template] declares that this step does not supply. */
    fun missingVariables(template: String, variables: Map<String, String>): Set<String> =
        (ALL[template] ?: emptySet()) - variables.keys

    /** Values safe for [template]; a push fallback receives only its declared shared values. */
    fun valuesFor(template: String, values: Map<String, String>): Map<String, String> =
        values.filterKeys { it in (ALL[template] ?: emptySet()) }
}
