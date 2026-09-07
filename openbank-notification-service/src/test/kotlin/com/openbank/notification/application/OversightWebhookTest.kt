// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Executable form of the ADR-0059 anonymization guarantee: only allow-listed,
 * PII-free oversight signals may ever leave the cluster. If someone widens the
 * outbound schema to carry customer data, these tests fail.
 */
class OversightWebhookTest {

    private fun signal(t: NotificationTemplate) = OversightSignal(
        template = t,
        primaryChannel = NotificationChannel.EMAIL,
        status = NotificationStatus.PENDING,
        occurredAt = Instant.parse("2026-06-02T12:00:00Z"),
    )

    @Test
    fun `only operational risk templates are oversight (allow-list, not block-list)`() {
        // On the allow-list → egress.
        assertThat(OversightWebhook.isOversight(NotificationTemplate.TRANSACTION_FAILED)).isTrue()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.KYC_REJECTED)).isTrue()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.ACCOUNT_FROZEN)).isTrue()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.CONSENT_REVOKED)).isTrue()

        // NOT on the allow-list → never egress (success / marketing / secret-bearing).
        assertThat(OversightWebhook.isOversight(NotificationTemplate.WELCOME)).isFalse()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.OTP_CODE)).isFalse()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.TRANSACTION_COMPLETED)).isFalse()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.ACCOUNT_OPENED)).isFalse()
        assertThat(OversightWebhook.isOversight(NotificationTemplate.KYC_APPROVED)).isFalse()
    }

    @Test
    fun `rendered payload carries the template, status and the anonymization disclaimer`() {
        val payload = OversightWebhook.renderSlackPayload(signal(NotificationTemplate.TRANSACTION_FAILED))
        assertThat(payload).startsWith("{\"text\":")
        assertThat(payload).contains("TRANSACTION FAILED")
        assertThat(payload).contains("No customer data")
        assertThat(payload).contains("ADR-0059")
    }

    @Test
    fun `rendered payload contains NO PII for any oversight template`() {
        // The schema cannot carry these, but assert it explicitly so a future
        // schema change that reintroduces them fails this test.
        val poison = listOf("@", "CZ65", "iban", "amount", "recipient", "partyId", "Jiří", "Raška")
        for (t in OversightWebhook.OVERSIGHT_TEMPLATES) {
            val payload = OversightWebhook.renderSlackPayload(signal(t))
            // No '@' (email), no IBAN prefix, no PII field names, no person names.
            for (token in poison) {
                assertThat(payload.lowercase()).doesNotContain(token.lowercase())
            }
            // No long digit runs (PAN / account numbers).
            assertThat(Regex("\\d{13,}").containsMatchIn(payload)).isFalse()
        }
    }

    @Test
    fun `scrubPii redacts IBAN, PAN and email tokens (defense in depth)`() {
        val dirty = "ref CZ6508000000192000145399 card 4532015112830366 mail john.doe@example.com"
        val clean = OversightWebhook.scrubPii(dirty)
        assertThat(clean).contains("[REDACTED-IBAN]")
        assertThat(clean).contains("[REDACTED-PAN]")
        assertThat(clean).contains("[REDACTED-EMAIL]")
        assertThat(clean).doesNotContain("CZ6508000000192000145399")
        assertThat(clean).doesNotContain("4532015112830366")
        assertThat(clean).doesNotContain("john.doe@example.com")
    }

    @Test
    fun `maskUrl never reveals the full webhook secret`() {
        val url = "https://hooks.slack.com/services/T00000000/B11111111/abcdefghijklmnopqrstuvwx"
        val masked = OversightWebhook.maskUrl(url)
        assertThat(masked).doesNotContain("abcdefghijklmnopqrstuvwx")
        assertThat(masked).contains("…")
        assertThat(OversightWebhook.maskUrl("")).isEqualTo("(unset)")
    }
}
