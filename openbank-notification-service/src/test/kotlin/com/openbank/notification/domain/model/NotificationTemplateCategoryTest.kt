// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import com.openbank.notification.application.NotificationConsumer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards that the ADR-0198 D4 consent gate stays wired (issue #2660).
 *
 * Before #2660 this test asserted *no template maps to MARKETING*, because the only honest states
 * were "no marketing sends" or "consent check wired" — and the check was not. Since #2660 the
 * check IS wired: `NotificationConsumer.gateMarketingOnConsent` calls consent-service
 * `GET /api/v1/consents/party/{partyId}/grantee/{granteeId}/active` per send, fails closed on
 * both a refusal (`no_active_consent`) and an outage (`consent_check_unavailable`), and never
 * caches (ADR-0198). A MARKETING template is now permissible BECAUSE that path exists.
 *
 * What must not happen is the reverse drift: someone removes the gate (or the client) while a
 * MARKETING template lives, and every send reverts to unchecked. This test fails in that
 * direction — the gate method and the consent-service client must both exist, and any MARKETING
 * template is fine only while they do.
 */
class NotificationTemplateCategoryTest {

    @Test
    fun `the consent gate stays wired - every MARKETING send goes through consent-service`() {
        val gate = NotificationConsumer::class.java.declaredMethods
            .firstOrNull { it.name == "gateMarketingOnConsent" }
        assertThat(gate)
            .describedAs(
                "NotificationConsumer.gateMarketingOnConsent is gone. The ADR-0198 D4 consent gate " +
                    "(#2660) must not be removed while MARKETING sends exist — without it every " +
                    "marketing send is an unchecked Art. 6(1)(a) violation. Restore the per-send " +
                    "consent-service check instead of deleting the gate.",
            )
            .isNotNull()

        val clientField = NotificationConsumer::class.java.declaredFields
            .firstOrNull { it.type.name.endsWith("ConsentServiceClient") }
        assertThat(clientField)
            .describedAs(
                "NotificationConsumer no longer holds a ConsentServiceClient. The ADR-0198 D4 gate " +
                    "(#2660) needs the consent-service client to answer hasActiveConsent per send.",
            )
            .isNotNull()
    }

    @Test
    fun `MARKETING templates are permissible only because the gate exists - documented per template`() {
        val marketingTemplates = NotificationTemplate.entries.filter {
            it.category == NotificationCategory.MARKETING
        }
        // Not an emptiness assertion anymore: a MARKETING template is legal post-#2660. This
        // exists so a template landing here forces a human to read the gate test above and
        // confirm the per-send check still covers the new template's channel scope
        // (MARKETING_COMMS_EMAIL / MARKETING_COMMS_PUSH).
        marketingTemplates.forEach { template ->
            assertThat(template.category)
                .describedAs(
                    "Template %s maps to MARKETING. Confirm gateMarketingOnConsent covers its " +
                        "channel's consent scope and that the audit reasons (no_active_consent / " +
                        "consent_check_unavailable) still distinguish refusal from outage (#2660 §3).",
                    template,
                )
                .isEqualTo(NotificationCategory.MARKETING)
        }
    }
}

class MarketingConsentScopeTest {

    @Test
    fun `each channel maps to its own consent scope - a shared scope would over-grant`() {
        assertThat(NotificationConsumer.marketingScopeFor(NotificationChannel.EMAIL))
            .isEqualTo("MARKETING_COMMS_EMAIL")
        assertThat(NotificationConsumer.marketingScopeFor(NotificationChannel.PUSH))
            .isEqualTo("MARKETING_COMMS_PUSH")
    }
}
