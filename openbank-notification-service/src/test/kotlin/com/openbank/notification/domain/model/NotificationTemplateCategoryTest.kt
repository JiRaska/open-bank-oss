// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the precondition both MARKETING channel gates rely on (ADR-0198 D4, issue #2369).
 *
 * Neither channel actually checks GDPR consent today:
 *  - `maybeSendPush` gates MARKETING on `marketing_push`, a LOCAL preference toggle (now
 *    fail-closed when the row is absent) — not the Art. 6(1)(a) consent record.
 *  - `maybeSendEmail` cannot even do that: `notification_preference`'s columns are push-specific,
 *    so it SUPPRESSES MARKETING email outright rather than pretend to check.
 *
 * The real consent record is consent-service's, under grantee `party-service:marketing-comms`
 * (ADR-0205 D3). Not wiring an HTTP call to it is acceptable ONLY because no template maps to
 * MARKETING, so both branches are unreachable — a consent client here would be a money-path
 * dependency for a caller that does not exist.
 *
 * This test is what keeps that "acceptable today" honest. The moment a MARKETING template is added,
 * it goes RED, and whoever adds it must wire the real check (consent-service
 * `POST /api/v1/consents/{id}/validate`, scope `MARKETING_COMMS_EMAIL` / `MARKETING_COMMS_PUSH`)
 * instead of inheriting a local-toggle decision (push) or a blanket suppression (email).
 *
 * Do NOT "fix" a failure here by adding the template to the expected set. The failure means a
 * consent gate is now required.
 */
class NotificationTemplateCategoryTest {

    @Test
    fun `no template maps to MARKETING - adding one requires wiring the consent-service gate first`() {
        val marketingTemplates = NotificationTemplate.entries.filter {
            it.category == NotificationCategory.MARKETING
        }

        assertThat(marketingTemplates)
            .describedAs(
                "A template now maps to NotificationCategory.MARKETING. Neither channel checks GDPR " +
                    "consent: PUSH reads the local marketing_push toggle (fail-closed, but not the " +
                    "consent record) and EMAIL suppresses outright. Wire consent-service validate " +
                    "(scopes MARKETING_COMMS_EMAIL / MARKETING_COMMS_PUSH, grantee " +
                    "party-service:marketing-comms) before shipping this. See ADR-0198 D4 / #2369.",
            )
            .isEmpty()
    }

    @Test
    fun `every template resolves a category`() {
        // Cheap exhaustiveness canary: `category` is a `when` over `this` with no else branch, so a
        // new enum constant fails to compile — but only if something actually evaluates it.
        assertThat(NotificationTemplate.entries).allSatisfy { assertThat(it.category).isNotNull() }
    }

    @Test
    fun `security templates can never be silenced`() {
        val security = NotificationTemplate.entries.filter { it.category == NotificationCategory.SECURITY }

        assertThat(security).contains(
            NotificationTemplate.OTP_CODE,
            NotificationTemplate.SCA_APPROVAL,
            NotificationTemplate.ACCOUNT_FROZEN,
        )
    }
}
