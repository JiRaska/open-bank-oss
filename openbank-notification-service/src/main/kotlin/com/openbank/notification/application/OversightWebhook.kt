// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationStatus
import com.openbank.notification.domain.model.NotificationTemplate
import java.time.Instant

// ── Outbound oversight webhook — the anonymization core (ADR-0059) ───────────
//
// The ONLY data that may leave the cluster to Slack/Teams. This is a positive
// ALLOW-LIST (ADR-0059 D2): the payload is built from this fixed, safe schema
// and nothing else — never the PII-bearing `variables` map, `recipient`, raw
// `partyId`, names, IBANs or amounts. A field leaks only if someone adds it
// here on purpose, which OversightWebhookTest forbids.

/** The complete, safe outbound schema. No field may carry personal data. */
data class OversightSignal(
    val template: NotificationTemplate, // enum name only, e.g. TRANSACTION_FAILED
    val primaryChannel: NotificationChannel,
    val status: NotificationStatus,
    val occurredAt: Instant,
)

object OversightWebhook {

    // Only operational / risk templates egress (ADR-0059 D2). Success, marketing
    // and secret-bearing templates (WELCOME, OTP_CODE, TRANSACTION_COMPLETED, …)
    // are deliberately ABSENT and never leave the cluster.
    val OVERSIGHT_TEMPLATES: Set<NotificationTemplate> = setOf(
        NotificationTemplate.TRANSACTION_FAILED,
        NotificationTemplate.KYC_REJECTED,
        NotificationTemplate.ACCOUNT_FROZEN,
        NotificationTemplate.CONSENT_REVOKED,
        // ADR-0232 delegated-access grant pulled back — same risk shape as CONSENT_REVOKED
        // (an access grant ending), not a routine lifecycle step like OFFERED/ACCEPTED.
        NotificationTemplate.DELEGATION_REVOKED,
    )

    fun isOversight(template: NotificationTemplate): Boolean = template in OVERSIGHT_TEMPLATES

    private fun emoji(template: NotificationTemplate): String = when (template) {
        NotificationTemplate.TRANSACTION_FAILED -> "⚠️" // ⚠️
        NotificationTemplate.KYC_REJECTED -> "🚫" // 🚫
        NotificationTemplate.ACCOUNT_FROZEN -> "❄️" // ❄️
        NotificationTemplate.CONSENT_REVOKED -> "🔓" // 🔓
        NotificationTemplate.DELEGATION_REVOKED -> "🔒" // 🔒
        else -> "ℹ️" // ℹ️
    }

    /**
     * Human-readable, PII-free oversight line. Built ONLY from the enum names +
     * status + timestamp in [signal]. No customer data is reachable from here.
     */
    fun renderText(signal: OversightSignal): String {
        val label = signal.template.name.replace('_', ' ')
        val line = "${emoji(signal.template)} OpenBank oversight: $label (status ${signal.status.name}) " +
            "at ${signal.occurredAt} UTC. No customer data — anonymized oversight signal (ADR-0059)."
        // Defense in depth (ADR-0059 D3): scrub any PII-looking token that a future
        // schema change might introduce, so two independent controls must fail to leak.
        return scrubPii(line)
    }

    /** The Slack incoming-webhook JSON body for [signal]. */
    fun renderSlackPayload(signal: OversightSignal): String {
        val text = renderText(signal)
        // Minimal Slack contract: {"text": "..."}. JSON-encode the text safely.
        return "{\"text\":${jsonString(text)}}"
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Defense-in-depth scrubber: replace IBAN-like, PAN-like (13–19 digit runs),
    // and email tokens with a masked marker. The v1 schema carries none of these;
    // this guards against future drift (ADR-0059 D3).
    private val IBAN_RE = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b")
    private val PAN_RE = Regex("\\b\\d{13,19}\\b")
    private val EMAIL_RE = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")

    fun scrubPii(input: String): String = input
        .replace(IBAN_RE, "[REDACTED-IBAN]")
        .replace(PAN_RE, "[REDACTED-PAN]")
        .replace(EMAIL_RE, "[REDACTED-EMAIL]")

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    /** Mask a webhook URL for audit logs — keep only scheme/host prefix + a short tail. */
    fun maskUrl(url: String): String {
        if (url.isBlank()) return "(unset)"
        val head = url.take(30)
        val tail = url.takeLast(4)
        return "$head…$tail"
    }
}
