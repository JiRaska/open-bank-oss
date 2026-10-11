// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate

/**
 * Czech and English copy for the pension participant notices (ADR-0334, #12392), produced by
 * `openbank-pension-service`. Same two rules as [ApprovalCopy]:
 *  - **The subject is a constant per template and language** — it is the push title a lock screen
 *    shows (ADR-0135 §3), so no contract id, amount or account digits reach it.
 *  - **Every interpolated value is HTML-escaped.**
 *
 * `direction`, `status`, `purpose` and `strategyCode` are closed producer vocabularies; they are
 * rendered escaped and verbatim (pension-service owns their wording), never interpreted here.
 */
object PensionCopy {

    val TEMPLATES: Set<NotificationTemplate> = setOf(
        NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED,
        NotificationTemplate.PENSION_PAYOUT_EXECUTED,
        NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE,
        NotificationTemplate.PENSION_TRANSFER_STATUS,
        NotificationTemplate.PENSION_INCENTIVE_RECEIVED,
        NotificationTemplate.PENSION_INCENTIVE_RETURNED,
    )

    /** [render] for a pension template, `null` for any other. */
    fun renderOrNull(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String>? = if (template in TEMPLATES) render(template, vars, language) else null

    /** Renders (subject, htmlBody). [language] `null` means English. */
    @Suppress("LongMethod")
    fun render(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String> {
        require(template in TEMPLATES) { "PensionCopy does not render $template" }
        val cs = language == NotificationLanguage.CS
        val contract = vars.v("contractId")
        return when (template) {
            NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED -> if (cs) {
                "Změna účtu pro výplatu penze" to
                    "<h2>Změna účtu pro výplatu</h2><p>U smlouvy <b>$contract</b> byl podepsán nový účet pro " +
                    "výplaty končící <b>${vars.v("accountLast4")}</b>. Platí od ${vars.v("effectiveFrom")}.</p>" +
                    "<p>Pokud jsi změnu neprováděl(a) ty, okamžitě kontaktuj OpenBank.</p>"
            } else {
                "Pension payout account changed" to
                    "<h2>Payout account changed</h2><p>A new payout account ending <b>${vars.v("accountLast4")}</b> " +
                    "was signed for contract <b>$contract</b>. It takes effect on ${vars.v("effectiveFrom")}.</p>" +
                    "<p>If you did not make this change, contact OpenBank immediately.</p>"
            }
            NotificationTemplate.PENSION_PAYOUT_EXECUTED -> if (cs) {
                "Výplata z penzijního účtu" to
                    "<h2>Výplata odeslána</h2><p>Ze smlouvy <b>$contract</b> jsme odeslali výplatu " +
                    "(${vars.v("purpose")}) <b>${money(vars)}</b> na účet končící ${vars.v("accountLast4")}.</p>"
            } else {
                "Pension payout sent" to
                    "<h2>Payout sent</h2><p>A payout (${vars.v("purpose")}) of <b>${money(vars)}</b> from contract " +
                    "<b>$contract</b> was sent to the account ending ${vars.v("accountLast4")}.</p>"
            }
            NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE -> if (cs) {
                "Změna investiční strategie" to
                    "<h2>Nová investiční strategie</h2><p>U smlouvy <b>$contract</b> platí od " +
                    "${vars.v("effectiveFrom")} strategie <b>${vars.v("strategyCode")}</b>.</p>"
            } else {
                "Investment strategy change" to
                    "<h2>New investment strategy</h2><p>Strategy <b>${vars.v("strategyCode")}</b> applies to " +
                    "contract <b>$contract</b> from ${vars.v("effectiveFrom")}.</p>"
            }
            NotificationTemplate.PENSION_TRANSFER_STATUS -> if (cs) {
                "Stav převodu penzijní smlouvy" to
                    "<h2>Převod smlouvy</h2><p>Převod smlouvy <b>$contract</b> (${vars.v("direction")}) " +
                    "je ve stavu <b>${vars.v("status")}</b>.</p>"
            } else {
                "Pension transfer update" to
                    "<h2>Contract transfer</h2><p>The transfer (${vars.v("direction")}) of contract " +
                    "<b>$contract</b> is now <b>${vars.v("status")}</b>.</p>"
            }
            NotificationTemplate.PENSION_INCENTIVE_RECEIVED -> if (cs) {
                "Připsán státní příspěvek" to
                    "<h2>Státní příspěvek připsán</h2><p>Ke smlouvě <b>$contract</b> jsme připsali státní " +
                    "příspěvek za období ${vars.v("period")} ve výši <b>${money(vars)}</b>.</p>"
            } else {
                "State contribution credited" to
                    "<h2>State contribution credited</h2><p>A state contribution of <b>${money(vars)}</b> for " +
                    "${vars.v("period")} was credited to contract <b>$contract</b>.</p>"
            }
            NotificationTemplate.PENSION_INCENTIVE_RETURNED -> if (cs) {
                "Vrácení státního příspěvku" to
                    "<h2>Státní příspěvek vrácen</h2><p>Státní příspěvek za období ${vars.v("period")} " +
                    "ve výši <b>${money(vars)}</b> byl ze smlouvy <b>$contract</b> vrácen státu.</p>"
            } else {
                "State contribution returned" to
                    "<h2>State contribution returned</h2><p>The state contribution of <b>${money(vars)}</b> " +
                    "for ${vars.v("period")} was returned from contract <b>$contract</b> to the state.</p>"
            }
            else -> error("unreachable: guarded by TEMPLATES")
        }
    }

    private fun money(vars: Map<String, String>): String = "${vars.v("amount")} ${vars.v("currency")}".trim()

    private fun Map<String, String>.v(key: String): String = HtmlEscape.escape(this[key] ?: "")
}
