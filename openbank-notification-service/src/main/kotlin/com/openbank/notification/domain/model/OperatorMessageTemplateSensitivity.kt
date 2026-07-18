// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

/**
 * [TemplateSensitivity]'s counterpart for [OperatorMessageTemplate] (issue #1386).
 *
 * `OperatorMessageTemplate` and `NotificationTemplate` are deliberately disjoint enums (see
 * `OperatorMessageTemplate`'s own KDoc, ADR-0176 D1/D2) — but that split reintroduced, for
 * `OperatorMessageTemplate`, the exact secret-leak gap that [TemplateSensitivity] closed for
 * `NotificationTemplate` after issue #1325: `NotificationResource.bodyForRead` looked up the
 * stored `template` column string against `NotificationTemplate.entries` only, so any row
 * written by [com.openbank.notification.application.OperatorMessageService] (whose `template`
 * column holds an `OperatorMessageTemplate` name) failed the `firstOrNull` lookup and fell
 * through to the unconditional fail-open branch — serving the raw stored body to any
 * `ROLE_OPERATOR` reader with **no** redaction check performed at all, secret or not.
 *
 * Both current constants (`GENERIC_NOTICE`, `SUPPORT_FOLLOWUP`) are non-secret today, so this is
 * currently latent, not exploitable — but the guard now exists, mirroring
 * [TemplateSensitivity.SECRET_TEMPLATES]'s allow-list shape, so the moment a future
 * `OperatorMessageTemplate` constant embeds something sensitive (a support PIN, a magic link, a
 * one-time code an operator relays), classifying it here is the only change needed to redact it
 * on read — the same one-question test as [TemplateSensitivity]: *if a bank operator read this
 * body, could they use it to act as the customer?*
 */
object OperatorMessageTemplateSensitivity {

    /**
     * Templates whose rendered body would contain a secret that authenticates the customer.
     * Empty today — neither current constant carries one — but kept as a real allow-list
     * (not a placeholder) so [NotificationResource.bodyForRead][com.openbank.notification
     * .infrastructure.rest.NotificationResource.bodyForRead] has something to check instead of
     * failing open for this enum, exactly as it already does for [NotificationTemplate].
     */
    val SECRET_TEMPLATES: Set<OperatorMessageTemplate> = emptySet()

    /** True when [template]'s rendered body would embed an authentication secret. */
    fun isSecret(template: OperatorMessageTemplate): Boolean = template in SECRET_TEMPLATES
}
