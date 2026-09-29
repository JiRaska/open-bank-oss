// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate

/**
 * Czech and English copy for the multi-signature approval templates (#10281).
 *
 * Two rules the shape encodes:
 *  - **The subject is a constant per template and language.** It becomes the push title, the one
 *    part of a notification a lock screen shows (ADR-0135 §3), so it never interpolates a
 *    variable — no company name, amount or payee reaches a lock screen. The details live only in
 *    the body, which the app fetches after an authenticated tap.
 *  - **Every interpolated value is HTML-escaped here**, the same posture as
 *    `NotificationConsumer`'s shared accessor (#1382): `entityName`, `payeeName`, `initiatorName`
 *    and a rejection `reason` are all typed by people.
 *
 * Czech copy addresses the customer informally (tykání), as the app does.
 */
object ApprovalCopy {

    /** The templates this object renders; anything else is not its business. */
    val TEMPLATES: Set<NotificationTemplate> = setOf(
        NotificationTemplate.APPROVAL_REQUIRED,
        NotificationTemplate.APPROVAL_COMPLETED,
        NotificationTemplate.APPROVAL_REJECTED,
        NotificationTemplate.APPROVAL_EXPIRED,
        NotificationTemplate.PAYMENT_RELEASE_FAILED,
    )

    /** [render] for an approval template, `null` for any other (which keeps its English-only copy). */
    fun renderOrNull(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String>? = if (template in TEMPLATES) render(template, vars, language) else null

    /** Renders (subject, htmlBody). [language] `null` means English, as for every older template. */
    fun render(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String> {
        require(template in TEMPLATES) { "ApprovalCopy does not render $template" }
        val cs = language == NotificationLanguage.CS
        val what = describe(vars, cs, accusative = false)
        return when (template) {
            NotificationTemplate.APPROVAL_REQUIRED -> if (cs) {
                "Čeká na tvůj podpis" to
                    "<h2>Čeká na tvůj podpis</h2><p>${vars.v("initiatorName")} ve firmě " +
                    "<b>${vars.v("entityName")}</b> chce provést ${describe(vars, cs, accusative = true)} " +
                    "a potřebuje k tomu tvůj podpis." +
                    expiry(vars, cs) + "</p><p>Otevři aplikaci OpenBank a požadavek podepiš, nebo zamítni.</p>"
            } else {
                "Waiting for your signature" to
                    "<h2>Waiting for your signature</h2><p>${vars.v("initiatorName")} at " +
                    "<b>${vars.v("entityName")}</b> prepared $what and needs your signature." +
                    expiry(vars, cs) + "</p><p>Open the OpenBank app to sign or reject it.</p>"
            }
            NotificationTemplate.APPROVAL_COMPLETED -> if (cs) {
                "Požadavek je podepsaný" to
                    "<h2>Všechny podpisy máme</h2><p>Firma <b>${vars.v("entityName")}</b>: $what. " +
                    "Všechny potřebné podpisy jsou hotové.</p>"
            } else {
                "Request fully signed" to
                    "<h2>All signatures collected</h2><p>${capital(what)} at <b>${vars.v("entityName")}</b> " +
                    "has every signature it needs.</p>"
            }
            NotificationTemplate.APPROVAL_REJECTED -> if (cs) {
                "Požadavek byl zamítnut" to
                    "<h2>Zamítnuto</h2><p>Firma <b>${vars.v("entityName")}</b>: $what. " +
                    "Požadavek byl zamítnut a nic se neprovede." + reason(vars, cs) + "</p>"
            } else {
                "Request rejected" to
                    "<h2>Rejected</h2><p>${capital(what)} at <b>${vars.v("entityName")}</b> " +
                    "was rejected and nothing will be carried out." + reason(vars, cs) + "</p>"
            }
            NotificationTemplate.APPROVAL_EXPIRED -> if (cs) {
                "Požadavek vypršel" to
                    "<h2>Vypršelo</h2><p>Firma <b>${vars.v("entityName")}</b>: $what. " +
                    "Požadavek nezískal podpisy včas a vypršel. Nic se neprovede.</p>"
            } else {
                "Request expired" to
                    "<h2>Expired</h2><p>${capital(what)} at <b>${vars.v("entityName")}</b> " +
                    "did not collect its signatures in time and has expired. Nothing will be carried out.</p>"
            }
            NotificationTemplate.PAYMENT_RELEASE_FAILED -> if (cs) {
                "Schválená platba neodešla" to
                    "<h2>Platba neodešla</h2><p>Platba ${amountAndPayee(vars, cs)} ve firmě " +
                    "<b>${vars.v("entityName")}</b> byla podepsaná, ale banka ji neprovedla." +
                    reason(vars, cs) + " Peníze z účtu neodešly.</p>"
            } else {
                "Approved payment was not sent" to
                    "<h2>Payment not sent</h2><p>The payment ${amountAndPayee(vars, cs)} at " +
                    "<b>${vars.v("entityName")}</b> was fully signed, but the bank could not execute it." +
                    reason(vars, cs) + " No money has left the account.</p>"
            }
            else -> error("unreachable: guarded by TEMPLATES")
        }
    }

    /** "a payment of 1 234,50 CZK to Acme" / "a signing-policy change" — escaped. */
    private fun describe(vars: Map<String, String>, cs: Boolean, accusative: Boolean): String {
        val kind = vars["kind"].orEmpty()
        val table = when {
            !cs -> KIND_EN
            accusative -> KIND_CS_ACCUSATIVE
            else -> KIND_CS
        }
        val noun = table[kind] ?: if (cs) "požadavek" else "a request"
        return if (kind in WITH_AMOUNT) "$noun ${amountAndPayee(vars, cs)}".trimEnd() else noun
    }

    private fun amountAndPayee(vars: Map<String, String>, cs: Boolean): String = buildString {
        val amount = vars.v("amountFormatted")
        val payee = vars.v("payeeName")
        if (amount.isNotBlank()) append(if (cs) "na <b>$amount</b>" else "of <b>$amount</b>")
        if (payee.isNotBlank()) {
            if (isNotEmpty()) append(' ')
            append(if (cs) "pro $payee" else "to $payee")
        }
    }

    private fun expiry(vars: Map<String, String>, cs: Boolean): String {
        val at = vars.v("expiresAt")
        if (at.isBlank()) return ""
        return if (cs) " Podepsat je potřeba do $at." else " It must be signed by $at."
    }

    private fun reason(vars: Map<String, String>, cs: Boolean): String {
        val r = vars.v("reason")
        if (r.isBlank()) return ""
        return if (cs) " Důvod: $r." else " Reason: $r."
    }

    private fun capital(s: String): String = s.replaceFirstChar { it.uppercase() }

    private fun Map<String, String>.v(key: String): String = HtmlEscape.escape(this[key] ?: "")

    private val KIND_CS = mapOf(
        "PAYMENT" to "platba",
        "POLICY_CHANGE" to "změna pravidel podepisování",
        "PAYEE_ADD" to "přidání důvěryhodného účtu",
        "PAYEE_REMOVE" to "odebrání důvěryhodného účtu",
        "STANDING_ORDER" to "zřízení trvalého příkazu",
        "SDD_MANDATE" to "zřízení souhlasu s inkasem",
    )

    /** Kinds whose copy names the amount and payee (#10281): an SDD mandate has neither amount nor IBAN. */
    private val WITH_AMOUNT = setOf("PAYMENT", "STANDING_ORDER")

    /** Czech object case ("chce provést platbu"); only PAYMENT and POLICY_CHANGE decline. */
    private val KIND_CS_ACCUSATIVE = KIND_CS + mapOf(
        "PAYMENT" to "platbu",
        "POLICY_CHANGE" to "změnu pravidel podepisování",
    )
    private val KIND_EN = mapOf(
        "PAYMENT" to "a payment",
        "POLICY_CHANGE" to "a signing-policy change",
        "PAYEE_ADD" to "adding a trusted payee",
        "PAYEE_REMOVE" to "removing a trusted payee",
        "STANDING_ORDER" to "a standing order",
        "SDD_MANDATE" to "a direct-debit mandate",
    )
}
