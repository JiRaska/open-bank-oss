// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A company's signing policy — and one approval request — in plain language (#10281), Czech or
 * English, informal register ("tykání") in Czech. Pure text from the policy JSON the owner
 * (delegation-service) returned: nothing here decides anything, so a wording change can never
 * change who may sign.
 */
object SigningPolicySummary {

    enum class Lang { CS, EN }

    fun lang(acceptLanguage: String?): Lang =
        if (acceptLanguage?.trim()?.lowercase()?.startsWith("en") == true) Lang.EN else Lang.CS

    /** One line per rule, in the owner's order, then the trusted-payee sentence. */
    fun policyLines(policy: JsonNode, lang: Lang): List<String> {
        val groups = policy.path("signerGroups").takeIf { it.isArray }
            ?.associate { it.path("id").asText() to it.path("name").asText() }
            .orEmpty()
        val rules = policy.path("rules").takeIf { it.isArray }?.toList().orEmpty()
        val lines = rules.map { ruleLine(it, groups, lang) }.toMutableList()
        val cap = money(policy.path("trustedPayeeCap"), null)
        lines += when (lang) {
            Lang.CS -> if (cap == null) {
                "Platby na důvěryhodné účty podepíše kdokoli z vás sám."
            } else {
                "Platby na důvěryhodné účty do ${cap.format(lang)} podepíše kdokoli z vás sám."
            }
            Lang.EN -> if (cap == null) {
                "Payments to trusted accounts need only one signature."
            } else {
                "Payments to trusted accounts up to ${cap.format(lang)} need only one signature."
            }
        }
        return lines
    }

    private fun ruleLine(rule: JsonNode, groups: Map<String, String>, lang: Lang): String {
        val currency = rule.path("currency").textOrNull()
        val range = rangeText(money(rule.path("minAmount"), currency), money(rule.path("maxAmount"), currency), lang)
        val who = signersText(rule.path("requiredSignatures").asInt(1), lang)
        val group = rule.path("groupId").textOrNull()?.let { groups[it] ?: it }
        val mustInclude = rule.path("mustIncludeGroupId").textOrNull()?.let { groups[it] ?: it }
        val from = group?.let { if (lang == Lang.CS) " (skupina $it)" else " (group $it)" }.orEmpty()
        val include = mustInclude?.let {
            if (lang == Lang.CS) ", jeden z nich ze skupiny $it" else ", one of them from group $it"
        }.orEmpty()
        return "$range$who$from$include."
    }

    private fun rangeText(min: Money?, max: Money?, lang: Lang): String {
        val cs = lang == Lang.CS
        return when {
            min != null && max != null ->
                if (cs) {
                    "Platby od ${min.format(
                        lang,
                    )} do ${max.format(lang)}"
                } else {
                    "Payments from ${min.format(lang)} to ${max.format(lang)}"
                }
            min != null -> if (cs) "Platby nad ${min.format(lang)}" else "Payments over ${min.format(lang)}"
            max != null -> if (cs) "Platby do ${max.format(lang)}" else "Payments up to ${max.format(lang)}"
            else -> if (cs) "Platby" else "Payments"
        }
    }

    private fun signersText(n: Int, lang: Lang): String = when {
        lang == Lang.CS && n <= 1 -> " podepíše kdokoli z vás sám"
        lang == Lang.CS -> " potřebují $n ${czechSignatures(n)}"
        n <= 1 -> " need one signature"
        else -> " need $n signatures"
    }

    /** "Označit účet … jako důvěryhodný" and friends: what exactly a signer is signing. */
    fun approvalSummary(approval: JsonNode, lang: Lang): String {
        val payload = approval.path("payload")
        val name = payload.path("name").textOrNull() ?: payload.path("creditorName").textOrNull().orEmpty()
        val iban = payload.path("iban").textOrNull() ?: payload.path("creditorIban").textOrNull().orEmpty()
        return when (approval.path("kind").asText()) {
            "PAYMENT" -> {
                val amount = money(payload.path("amount"), payload.path("currency").textOrNull())
                val amountText = amount?.format(lang).orEmpty()
                val text = if (lang == Lang.CS) "Platba $amountText na účet" else "Payment of $amountText to"
                "$text $iban $name".trim()
            }
            "PAYEE_ADD" -> if (lang == Lang.CS) {
                "Označit účet $iban $name jako důvěryhodný — platby na něj nebudou potřebovat druhý podpis"
            } else {
                "Mark account $iban $name as trusted — payments to it will not need a second signature"
            }
            "PAYEE_REMOVE" -> if (lang == Lang.CS) {
                "Zrušit důvěryhodnost účtu $iban $name — platby na něj budou znovu podle pravidel podepisování"
            } else {
                "Remove account $iban $name from trusted accounts — payments to it follow the signing rules again"
            }
            "POLICY_CHANGE" -> if (lang == Lang.CS) "Změna pravidel podepisování" else "Change of signing rules"
            "STANDING_ORDER", "SDD_MANDATE" -> recurringSummary(
                approval.path("kind").asText(),
                payload,
                iban,
                name,
                lang,
            )
            else -> approval.path("kind").asText()
        }
    }

    /** The recurring outflows held since #10281: a standing order, or a direct-debit mandate. */
    private fun recurringSummary(kind: String, payload: JsonNode, iban: String, name: String, lang: Lang): String =
        if (kind == "STANDING_ORDER") {
            val amount = money(payload.path("amount"), payload.path("currency").textOrNull())?.format(lang).orEmpty()
            val text = if (lang == Lang.CS) "Trvalý příkaz $amount na účet" else "Standing order of $amount to"
            "$text $iban $name".trim()
        } else {
            val creditor = payload.path("creditorIdentifier").textOrNull().orEmpty()
            val text = if (lang == Lang.CS) "Souhlas s inkasem pro" else "Direct-debit mandate for"
            "$text $name $creditor".trim()
        }

    data class Money(val amount: BigDecimal, val currency: String?) {
        fun format(lang: Lang): String {
            val symbols = DecimalFormatSymbols(if (lang == Lang.CS) Locale.forLanguageTag("cs-CZ") else Locale.US)
            if (lang == Lang.CS) symbols.groupingSeparator = ' '
            val hasFraction = amount.stripTrailingZeros().scale() > 0
            val number = DecimalFormat(if (hasFraction) "#,##0.00" else "#,##0", symbols).format(amount)
            val cur = currency ?: "CZK"
            return when {
                lang == Lang.CS && cur == "CZK" -> "$number Kč"
                lang == Lang.CS -> "$number $cur"
                else -> "$cur $number"
            }
        }
    }

    /** A money value as either `{amount, currency}` or a bare number/string (then [fallbackCurrency]). */
    fun money(node: JsonNode, fallbackCurrency: String?): Money? {
        if (node.isMissingNode || node.isNull) return null
        val raw = if (node.isObject) node.path("amount") else node
        val amount = raw.asText().toBigDecimalOrNull() ?: return null
        val currency = if (node.isObject) node.path("currency").textOrNull() ?: fallbackCurrency else fallbackCurrency
        return Money(amount, currency)
    }

    /** Czech plural: 2–4 take "podpisy", 5 and more "podpisů". */
    private fun czechSignatures(n: Int) = if (n in CZ_FEW_MIN..CZ_FEW_MAX) "podpisy" else "podpisů"

    private const val CZ_FEW_MIN = 2
    private const val CZ_FEW_MAX = 4

    private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
}
