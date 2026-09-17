// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain

/**
 * Customer-facing labels for product-catalogue fees, per language, for the business fee schedule
 * (SAZEBNIK_PO). Data, not template text: the template only prints `{{label}}`.
 *
 * Keyed by the catalogue fee `name`. product-catalog's `Fee` carries no localized name and no
 * stable code — its `id` is a random UUID generated when the seed is built and `type` is only a
 * coarse bucket (MONTHLY / TRANSACTION / ...) shared by different fees — so the English `name`
 * the catalogue publishes is the only stable identifier. A fee with no label in the requested
 * language is an error ([labelFor] returns null and the caller refuses to issue the schedule):
 * a Czech fee schedule never falls back to English.
 */
object FeeLabels {

    private val LABELS: Map<String, Map<String, String>> = mapOf(
        "Account Maintenance" to labels("Vedení účtu", "Account maintenance"),
        "Annual Fee" to labels("Roční poplatek", "Annual fee"),
        "Arrangement Fee" to labels("Poplatek za sjednání", "Arrangement fee"),
        "ATM Withdrawal (abroad)" to labels("Výběr z bankomatu v zahraničí", "ATM withdrawal abroad"),
        "Card Fee (per card/month)" to labels("Vedení platební karty (za kartu)", "Payment card (per card)"),
        "Cash Advance Fee" to labels("Výběr hotovosti kreditní kartou", "Cash advance"),
        "Cash Deposit" to labels("Vklad hotovosti", "Cash deposit"),
        "Currency Pocket Open" to labels("Zřízení měnové kapsy", "Opening a currency pocket"),
        "Domestic CERTIS Transfer" to labels("Tuzemská platba (CERTIS)", "Domestic payment (CERTIS)"),
        "Early Repayment" to labels("Předčasné splacení", "Early repayment"),
        "Early Repayment Charge" to labels("Poplatek za předčasné splacení", "Early repayment charge"),
        "Early Withdrawal" to labels("Předčasný výběr", "Early withdrawal"),
        "Early Withdrawal Penalty" to labels("Sankce za předčasný výběr", "Early withdrawal penalty"),
        "Excess Withdrawal" to labels("Výběr nad sjednaný limit", "Withdrawal above the agreed limit"),
        "Foreign Transaction" to labels("Transakce v cizí měně", "Foreign currency transaction"),
        "FX Conversion" to labels("Směna měn", "Currency conversion"),
        "International Transfer" to labels("Zahraniční platba", "International payment"),
        "Late Payment" to labels("Pozdní splátka", "Late payment"),
        "Management Fee" to labels("Poplatek za správu", "Management fee"),
        "Monthly Fee" to labels("Vedení účtu", "Account maintenance"),
        "Origination Fee" to labels("Poplatek za poskytnutí úvěru", "Origination fee"),
        "Overdraft Interest" to labels("Úrok z povoleného přečerpání", "Overdraft interest"),
        "SEPA Transfer" to labels("Odchozí platba SEPA", "Outgoing SEPA payment"),
        "SWIFT Transfer" to labels("Odchozí zahraniční platba SWIFT", "Outgoing SWIFT payment"),
        "Transaction Fee" to labels("Poplatek za transakci", "Transaction fee"),
        "Unarranged Overdraft Daily Fee" to labels("Nepovolené přečerpání (denně)", "Unarranged overdraft (daily)"),
        "Valuation Fee" to labels("Odhad nemovitosti", "Property valuation"),
        "Withdrawal Fee (excess)" to labels("Výběr nad limit", "Withdrawal above the limit"),
    )

    /** The label for catalogue fee [feeName] in [locale] (`CS`/`EN`), or null if none exists. */
    fun labelFor(feeName: String, locale: String): String? = LABELS[feeName]?.get(locale.uppercase())

    /** The catalogue fee names this table covers. */
    val knownFees: Set<String> get() = LABELS.keys

    private fun labels(cs: String, en: String) = mapOf("CS" to cs, "EN" to en)
}
