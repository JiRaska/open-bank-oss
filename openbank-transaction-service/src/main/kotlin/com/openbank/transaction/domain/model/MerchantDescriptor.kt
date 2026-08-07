// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.model

/**
 * Turns a card-acquirer merchant descriptor into a key the merchant catalogue can be looked up by.
 *
 * Acquirer descriptors are a 25-odd character free-text field written by whoever configured the
 * terminal, so the same shop reaches us as `ALZA.CZ A.S. PRAGUE CZ`, `ALZA.CZ  PRAHA 4` or
 * `alza.cz a.s.`. Normalising strips the parts that vary — case, punctuation, legal form, the
 * trailing town and country — and keeps the trading name, which is the part that identifies the
 * merchant.
 *
 * Matching is **exact on the normalised key**, deliberately. Fuzzy matching would let
 * `ALZA VETERINARY` collect Alza.cz's logo and coordinates, and a wrong location presented as a
 * fact is worse than no location at all — the caller renders nothing when there is no match.
 */
object MerchantDescriptor {
    /**
     * Legal-form tokens dropped from the end of a descriptor. Czech and the few foreign forms that
     * turn up on Czech acquirer data; each is dropped only as a whole token, so a shop actually
     * called "As" survives.
     */
    private val LEGAL_FORMS: Set<String> = setOf(
        "AS", "SRO", "SPOL", "SPOLSRO", "ZS", "OS", "KS", "VOS", "SE", "OPS", "ZU",
        "LTD", "LLC", "INC", "GMBH", "AG", "BV", "NV", "SA", "SP", "SPZOO", "OY", "AB",
    )

    /**
     * Trailing country tokens dropped from a descriptor. Only ISO-3166 alpha-2 codes and the few
     * spellings acquirers use — never a word that could be part of a name.
     */
    private val COUNTRY_TOKENS: Set<String> = setOf(
        "CZ", "CZE", "SK", "SVK", "AT", "AUT", "DE", "DEU", "PL", "POL", "GB", "GBR", "US", "USA",
    )

    /**
     * Trailing town tokens dropped from a descriptor. Kept to the towns that actually appear on
     * Czech acquirer data, and dropped only from the END — `PRAHA COFFEE` keeps its Praha, because
     * there the word is part of the trading name rather than a location suffix.
     */
    private val CITY_TOKENS: Set<String> = setOf(
        "PRAHA", "PRAGUE", "PRAG", "BRNO", "OSTRAVA", "PLZEN", "OLOMOUC", "LIBEREC",
        "CESKEBUDEJOVICE", "HRADECKRALOVE", "PARDUBICE", "ZLIN", "USTINADLABEM", "KLADNO",
    )

    /** Digits that follow a town ("PRAHA 4") — dropped with it, never on their own. */
    private val DISTRICT = Regex("^[0-9]{1,2}$")

    /**
     * The lookup key for [descriptor], or null when nothing identifying is left.
     *
     * Null means "do not look this up" rather than "look up the empty string": a descriptor that
     * normalises away entirely (a bare `PRAHA 4`, an empty field) must not collide with every other
     * such descriptor in the catalogue.
     */
    fun normalise(descriptor: String?): String? {
        val raw = descriptor?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // Fold accents first so `PLZEŇ` and `PLZEN` reach the same token.
        val folded = raw.uppercase().map { ACCENTS[it] ?: it }.joinToString("")

        // Periods go BEFORE tokenising, and both reasons are load-bearing:
        //
        //  - `A.S.` must become the single token `AS`. Treating the period as a separator splits it
        //    into `A` and `S`, neither of which is a legal form, so the abbreviation survives into
        //    the key and `ALZA.CZ A.S.` keys differently from `ALZA.CZ`.
        //  - `ALZA.CZ` must fuse into `ALZACZ`. Split apart, its `CZ` is indistinguishable from a
        //    trailing country code and gets stripped as one, leaving `ALZA` — a different merchant.
        //
        // Every other punctuation mark stays a separator.
        val dotless = folded.replace(".", "")
        var tokens = dotless.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        // Strip location and legal form from the tail only, repeatedly — `… A.S. PRAHA 4 CZ` needs
        // four passes. Leading tokens are never touched: they carry the trading name.
        var changed = true
        while (changed && tokens.isNotEmpty()) {
            changed = false
            if (isStrippable(tokens)) {
                tokens = tokens.dropLast(1)
                changed = true
            }
        }

        val key = tokens.joinToString("")
        return key.ifEmpty { null }
    }

    /**
     * True when the LAST token is a location or legal-form suffix rather than part of the name.
     *
     * A district number counts only when the token before it is a town: `BILLA PRAHA 4` ends in a
     * district, `PENNY 24` ends in part of the shop's name.
     */
    private fun isStrippable(tokens: List<String>): Boolean {
        val last = tokens.lastOrNull() ?: return false
        if (last in LEGAL_FORMS || last in COUNTRY_TOKENS || last in CITY_TOKENS) return true
        return DISTRICT.matches(last) && tokens.size >= 2 && tokens[tokens.size - 2] in CITY_TOKENS
    }

    private val ACCENTS: Map<Char, Char> = mapOf(
        'Á' to 'A', 'Č' to 'C', 'Ď' to 'D', 'É' to 'E', 'Ě' to 'E', 'Í' to 'I', 'Ň' to 'N',
        'Ó' to 'O', 'Ř' to 'R', 'Š' to 'S', 'Ť' to 'T', 'Ú' to 'U', 'Ů' to 'U', 'Ý' to 'Y',
        'Ž' to 'Z', 'Ä' to 'A', 'Ö' to 'O', 'Ü' to 'U', 'ß' to 'S', 'Ł' to 'L', 'Ą' to 'A',
        'Ę' to 'E', 'Ś' to 'S', 'Ć' to 'C', 'Ź' to 'Z', 'Ż' to 'Z', 'Ń' to 'N',
    )
}
