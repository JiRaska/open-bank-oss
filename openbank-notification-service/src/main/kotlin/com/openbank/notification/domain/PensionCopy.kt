// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate

/** Customer-facing Czech and English copy for pension lifecycle events (#12392). */
object PensionCopy {
    val TEMPLATES: Set<NotificationTemplate> = setOf(
        NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED,
        NotificationTemplate.PENSION_PAYOUT_EXECUTED,
        NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE,
        NotificationTemplate.PENSION_TRANSFER_STATUS,
        NotificationTemplate.PENSION_INCENTIVE_RECEIVED,
        NotificationTemplate.PENSION_INCENTIVE_RETURNED,
    )

    fun renderOrNull(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String>? = if (template in TEMPLATES) render(template, vars, language) else null

    /** Titles are constants: no contract, account or amount appears on a lock screen. */
    fun render(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String> {
        require(template in TEMPLATES) { "PensionCopy does not render $template" }
        val cs = language == NotificationLanguage.CS
        return when (template) {
            NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED -> if (cs) {
                "Změna účtu pro výplatu penze" to
                    "<p>U smlouvy ${vars.v("contractId")} se od ${vars.v("effectiveFrom")} změnil " +
                    "účet pro výplatu " +
                    "(končí číslicemi ${vars.v("accountLast4")}). Pokud jsi změnu nezadal(a), kontaktuj banku.</p>"
            } else {
                "Pension payout account changed" to
                    "<p>The payout account for contract ${vars.v("contractId")} changed effective " +
                    "${vars.v("effectiveFrom")} (ending ${vars.v("accountLast4")}). " +
                    "If you did not request this, contact the bank.</p>"
            }

            NotificationTemplate.PENSION_PAYOUT_EXECUTED -> if (cs) {
                "Výplata z penzijní smlouvy" to
                    "<p>Ze smlouvy ${vars.v("contractId")} byla provedena výplata ${vars.v("amount")} " +
                    "${vars.v("currency")} (${vars.v("purpose")}) na účet končící číslicemi " +
                    "${vars.v("accountLast4")}.</p>"
            } else {
                "Pension payout executed" to
                    "<p>A payout of ${vars.v("amount")} ${vars.v("currency")} (${vars.v("purpose")}) " +
                    "from contract ${vars.v("contractId")} was sent to an account ending " +
                    "${vars.v("accountLast4")}.</p>"
            }

            NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE -> if (cs) {
                "Změna penzijní strategie" to
                    "<p>U smlouvy ${vars.v("contractId")} je od ${vars.v("effectiveFrom")} účinná " +
                    "investiční strategie ${vars.v("strategyCode")}.</p>"
            } else {
                "Pension strategy changed" to
                    "<p>Investment strategy ${vars.v("strategyCode")} for contract " +
                    "${vars.v("contractId")} took effect on ${vars.v("effectiveFrom")}.</p>"
            }

            NotificationTemplate.PENSION_TRANSFER_STATUS -> if (cs) {
                "Stav převodu penzijní smlouvy" to
                    "<p>Převod smlouvy ${vars.v("contractId")} (${vars.v("direction")}) má stav " +
                    "${vars.v("status")}.</p>"
            } else {
                "Pension transfer status" to
                    "<p>The transfer for contract ${vars.v("contractId")} (${vars.v("direction")}) " +
                    "has status ${vars.v("status")}.</p>"
            }

            NotificationTemplate.PENSION_INCENTIVE_RECEIVED -> incentive(vars, cs, received = true)

            NotificationTemplate.PENSION_INCENTIVE_RETURNED -> incentive(vars, cs, received = false)

            else -> error("unreachable: guarded by TEMPLATES")
        }
    }

    private fun incentive(vars: Map<String, String>, cs: Boolean, received: Boolean): Pair<String, String> = when {
        cs && received ->
            "Připsán státní příspěvek" to
                "<p>Ke smlouvě ${vars.v("contractId")} byl za období ${vars.v("period")} připsán " +
                "státní příspěvek ${vars.v("amount")} ${vars.v("currency")}.</p>"

        cs ->
            "Vrácen státní příspěvek" to
                "<p>U smlouvy ${vars.v("contractId")} byl za období ${vars.v("period")} vrácen " +
                "státní příspěvek ${vars.v("amount")} ${vars.v("currency")}.</p>"

        received ->
            "State contribution received" to
                "<p>A state contribution of ${vars.v("amount")} ${vars.v("currency")} for period " +
                "${vars.v("period")} was credited to contract ${vars.v("contractId")}.</p>"

        else ->
            "State contribution returned" to
                "<p>A state contribution of ${vars.v("amount")} ${vars.v("currency")} for period " +
                "${vars.v("period")} was returned for contract ${vars.v("contractId")}.</p>"
    }

    private fun Map<String, String>.v(key: String): String = HtmlEscape.escape(this[key].orEmpty())
}
