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
    )

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
}
