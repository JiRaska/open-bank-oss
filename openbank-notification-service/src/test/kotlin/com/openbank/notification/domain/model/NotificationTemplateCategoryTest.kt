// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the MARKETING push gate's precondition (ADR-0198 D4, issue #2369).
 *
 * `NotificationConsumer.maybeSendPush` gates MARKETING on `marketing_push`, a LOCAL preference
 * toggle — NOT the GDPR Art. 6(1)(a) consent record, which consent-service owns under grantee
 * `party-service:marketing-comms` (ADR-0205 D3). That is acceptable today only because no template
 * maps to MARKETING, so the branch is unreachable and wiring a consent-service HTTP call into this
 * money-path consumer would be speculative — a dependency for a caller that does not exist.
 *
 * This test is what keeps that "acceptable today" honest. The moment a MARKETING template is added,
 * it goes RED, and whoever adds it must wire the real consent check (consent-service
 * `POST /api/v1/consents/{id}/validate`, scope `MARKETING_COMMS_PUSH`) instead of silently
 * inheriting a local-toggle decision for consent-gated traffic.
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
                "A template now maps to NotificationCategory.MARKETING. NotificationConsumer gates " +
                    "MARKETING on the local marketing_push toggle, which is NOT the GDPR consent " +
                    "record — wire consent-service validate (scope MARKETING_COMMS_PUSH, grantee " +
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
