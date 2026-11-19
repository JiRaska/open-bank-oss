// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import com.openbank.libs.identity.RodneCislo

/**
 * The closed vocabulary of business identifiers the platform accepts as an onboarding ENTRY POINT
 * (ADR-0284 D1). A scheme names the ISSUER of the number, not the register it is verified against:
 * the CZ IČO is verified against ARES, the LEI against GLEIF, and a scheme with no free public
 * register (`DUNS`, `EUID` today) falls back to manual attestation.
 *
 * [country] is null for the cross-border schemes; [format] is the normalised form the validator
 * accepts; [checksum] says whether a wrong digit is detectable before any register is asked.
 */
enum class IdentifierScheme(val country: String?, val displayName: String, val pattern: Regex, val checksum: Boolean) {
    CZ_ICO("CZ", "IČO", Regex("^\\d{8}$"), checksum = true),
    SK_ICO("SK", "IČO", Regex("^\\d{8}$"), checksum = true),
    PL_NIP("PL", "NIP", Regex("^\\d{10}$"), checksum = true),
    PL_KRS("PL", "KRS", Regex("^\\d{10}$"), checksum = false),
    DE_HRB("DE", "Handelsregister", Regex("^(HRA|HRB|GNR|PR|VR)\\d{1,6}[A-Z]{0,3}$"), checksum = false),
    AT_FN("AT", "Firmenbuchnummer", Regex("^FN\\d{1,6}[A-Z]$"), checksum = false),
    GB_CRN("GB", "Company number", Regex("^[A-Z0-9]{2}\\d{6}$"), checksum = false),
    FR_SIREN("FR", "SIREN", Regex("^\\d{9}$"), checksum = true),
    NL_KVK("NL", "KVK-nummer", Regex("^\\d{8}$"), checksum = false),
    LEI(null, "LEI", Regex("^[A-Z0-9]{18}\\d{2}$"), checksum = true),
    EUID(null, "EUID", Regex("^[A-Z]{2}[A-Z0-9]{1,10}\\.[A-Z0-9.\\-]{1,30}$"), checksum = false),
    EU_VAT(null, "VAT", Regex("^[A-Z]{2}[A-Z0-9]{2,12}$"), checksum = true),
    DUNS(null, "DUNS", Regex("^\\d{9}$"), checksum = false),
    ;

    /** Strips the separators people type (spaces, dashes, dots inside numbers, a `CZ` VAT prefix on an IČO). */
    fun normalise(raw: String): String {
        val typed = raw.trim().uppercase().replace(Regex("[\\s\\-]"), "")
        // People paste the VAT form ("CZ12345678") into an IČO field; the national prefix is not
        // part of the number. Only stripped when what remains is purely numeric.
        val compact = if (stripsCountryPrefix(typed)) typed.removePrefix(country!!) else typed
        return when (this) {
            CZ_ICO, SK_ICO, NL_KVK -> if (compact.all(Char::isDigit) &&
                compact.length < ICO_LENGTH
            ) {
                compact.padStart(ICO_LENGTH, '0')
            } else {
                compact
            }
            DE_HRB, AT_FN, EUID -> compact
            else -> compact.replace(".", "")
        }
    }

    private fun stripsCountryPrefix(value: String): Boolean {
        val cc = country ?: return false
        if (this == EU_VAT) return false
        return value.startsWith(cc) && value.removePrefix(cc).all(Char::isDigit)
    }

    companion object {
        private const val ICO_LENGTH = 8

        /** Schemes an applicant from [country] may enter. Cross-border schemes are always offered. */
        fun forCountry(country: String): List<IdentifierScheme> {
            val cc = country.trim().uppercase()
            val national = entries.filter { it.country == cc }
            val crossBorder = entries.filter { it.country == null }
            return national + crossBorder
        }
    }
}

/**
 * A validated, normalised business identifier. Construct through [parse]; the constructor is
 * private so an instance always satisfies its scheme's format AND checksum.
 */
data class LegalEntityIdentifier private constructor(val scheme: IdentifierScheme, val value: String) {

    /** ISO 3166-1 alpha-2 of the issuing jurisdiction, or null for a cross-border scheme. */
    val country: String? get() = scheme.country

    override fun toString(): String = "${scheme.name}:$value"

    companion object {
        fun parse(scheme: IdentifierScheme, raw: String): Result<LegalEntityIdentifier> {
            val value = scheme.normalise(raw)
            if (!scheme.pattern.matches(value)) {
                return Result.failure(
                    InvalidIdentifierException(scheme, "does not match the ${scheme.displayName} format"),
                )
            }
            if (scheme.checksum && !IdentifierChecksums.valid(scheme, value)) {
                return Result.failure(InvalidIdentifierException(scheme, "checksum failed — a digit is wrong"))
            }
            return Result.success(LegalEntityIdentifier(scheme, value))
        }

        fun of(scheme: IdentifierScheme, raw: String): LegalEntityIdentifier = parse(scheme, raw).getOrThrow()

        /**
         * Rebuilds an identifier that was validated when it was STORED. Reads must not re-run the
         * checksum: a rule tightened later (EU VAT check digits, #16748) would otherwise make an
         * already-onboarded case unreadable. Only the shape is re-checked; input paths use [parse].
         */
        fun restore(scheme: IdentifierScheme, storedValue: String): LegalEntityIdentifier {
            require(scheme.pattern.matches(storedValue)) { "stored ${scheme.displayName} is not in normalised form" }
            return LegalEntityIdentifier(scheme, storedValue)
        }
    }
}

class InvalidIdentifierException(val scheme: IdentifierScheme, reason: String) :
    IllegalArgumentException("${scheme.displayName} $reason")

/**
 * Check-digit algorithms, one per scheme that has one. Pure functions so a wrong digit is rejected
 * at the input field, before a register round-trip that would answer "not found" for it.
 */
object IdentifierChecksums {

    fun valid(scheme: IdentifierScheme, value: String): Boolean = when (scheme) {
        IdentifierScheme.CZ_ICO, IdentifierScheme.SK_ICO -> ico(value)
        IdentifierScheme.PL_NIP -> nip(value)
        IdentifierScheme.FR_SIREN -> siren(value)
        IdentifierScheme.LEI -> mod97(value)
        IdentifierScheme.EU_VAT -> EuVatChecksums.valid(value)
        else -> true
    }

    /** IČO: weights 8..2 over the first seven digits, check digit = (11 - sum mod 11) mod 10. */
    fun ico(value: String): Boolean {
        if (value.length != ICO_LENGTH || !value.all(Char::isDigit)) return false
        val sum = (0 until ICO_LENGTH - 1).sumOf { (value[it] - '0') * (ICO_LENGTH - it) }
        val expected = (MOD_11 - sum % MOD_11) % MOD_10
        return expected == value.last() - '0'
    }

    /** Polish NIP: weights 6,5,7,2,3,4,5,6,7; sum mod 11 must equal the tenth digit (and never be 10). */
    fun nip(value: String): Boolean {
        if (value.length != NIP_LENGTH || !value.all(Char::isDigit)) return false
        val sum = NIP_WEIGHTS.indices.sumOf { (value[it] - '0') * NIP_WEIGHTS[it] }
        val check = sum % MOD_11
        return check != MOD_10 && check == value.last() - '0'
    }

    /**
     * French SIREN: 9 digits, Luhn-valid, and not `000000000`. The all-zero number passes Luhn but
     * is never assigned; that is LOCAL POLICY of this module, not a published rule (python-stdnum
     * `fr.siren` accepts it, and no INSEE document states the exclusion that this module found).
     * Monaco-registered entities inside a French VAT number are a different path
     * ([EuVatChecksums]) and are unaffected.
     */
    fun siren(value: String): Boolean = value != SIREN_UNASSIGNED && luhn(value)

    /** Luhn (ISO/IEC 7812-1), used by the French SIREN. */
    fun luhn(value: String): Boolean {
        if (value.isEmpty() || !value.all(Char::isDigit)) return false
        var sum = 0
        var double = false
        for (i in value.indices.reversed()) {
            var d = value[i] - '0'
            if (double) {
                d *= 2
                if (d > LUHN_NINE) d -= LUHN_NINE
            }
            sum += d
            double = !double
        }
        return sum % MOD_10 == 0
    }

    /** ISO 7064 MOD 97-10 over the alphanumeric expansion (A=10 … Z=35); a valid LEI leaves 1. */
    fun mod97(value: String): Boolean {
        if (value.length != LEI_LENGTH) return false
        var remainder = 0
        for (ch in value) {
            val digits = when {
                ch.isDigit() -> (ch - '0').toString()
                ch in 'A'..'Z' -> (ch - 'A' + LETTER_BASE).toString()
                else -> return false
            }
            for (d in digits) remainder = (remainder * MOD_10 + (d - '0')) % MOD_97
        }
        return remainder == 1
    }

    private const val ICO_LENGTH = 8
    private const val NIP_LENGTH = 10
    private const val LEI_LENGTH = 20
    private const val SIREN_UNASSIGNED = "000000000"
    private const val MOD_10 = 10
    private const val MOD_11 = 11
    private const val MOD_97 = 97
    private const val LUHN_NINE = 9
    private const val LETTER_BASE = 10

    /** The official NIP weights (Ustawa o NIP). A literal table, not arithmetic. */
    @Suppress("MagicNumber")
    private val NIP_WEIGHTS = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
}

/**
 * National check digits inside an EU VAT number (`CC` + national part). EVERY member-state prefix
 * the VIES service knows is dispatched to its own rule (see [EuVatNationalRules] for the states added
 * in #16774); a prefix that is not listed is REJECTED, and a state that is deliberately only
 * shape-checked must be named in [SHAPE_ONLY_STATES] with its reason, so an unimplemented algorithm
 * can no longer pass silently. Prefixes: the 27 member states (Greece is `EL`) plus `XI`
 * (Northern Ireland, goods). Source for the prefix list: https://ec.europa.eu/taxation_customs/vies/.
 */
object EuVatChecksums {

    /** States accepted on shape alone, each with the cited reason. Empty: every VIES prefix has a rule. */
    val SHAPE_ONLY_STATES: Map<String, String> = emptyMap()

    private val RULES: Map<String, (String) -> Boolean> = mapOf(
        "AT" to this::at, "BE" to EuVatNationalRules::be, "BG" to EuVatCentralSouthEast::bg,
        "CY" to EuVatMediterranean::cy, "CZ" to this::czDic, "DE" to this::de, "DK" to EuVatNordicBaltic::dk,
        "EE" to EuVatNordicBaltic::ee, "EL" to EuVatMediterranean::el, "ES" to EuVatNationalRules::es,
        "FI" to EuVatNordicBaltic::fi, "FR" to this::fr, "HR" to EuVatCentralSouthEast::hr,
        "HU" to EuVatCentralSouthEast::hu, "IE" to EuVatNationalRules::ie, "IT" to EuVatNationalRules::it,
        "LT" to EuVatNordicBaltic::lt, "LU" to EuVatNationalRules::lu, "LV" to EuVatNordicBaltic::lv,
        "MT" to EuVatMediterranean::mt, "NL" to EuVatNationalRules::nl, "PL" to IdentifierChecksums::nip,
        "PT" to EuVatNationalRules::pt, "RO" to EuVatCentralSouthEast::ro, "SE" to EuVatNordicBaltic::se,
        "SI" to EuVatCentralSouthEast::si, "SK" to this::sk, "XI" to EuVatMediterranean::xi,
    )

    fun valid(vat: String): Boolean {
        val country = vat.take(2)
        val national = vat.drop(2)
        if (country in SHAPE_ONLY_STATES) return true
        return RULES[country]?.invoke(national) ?: false
    }

    /**
     * SK IČ DPH: 10 digits, first digit not 0, third digit one of 2, 3, 4, 7, 8, 9, number divisible
     * by 11. The Slovak tax authority's published material gives the 10-digit shape only
     * (https://www.financnasprava.sk/_img/pfsedit/Dokumenty_PFS/Infoservis/Aktualne_informacie/dph/2016/2016.03.21_IC_DPH_EU.pdf);
     * the third-digit and mod 11 arithmetic is per python-stdnum `sk.dph`
     * (https://arthurdejong.org/python-stdnum/doc/2.2/stdnum.sk.dph.html). stdnum additionally lets
     * a Slovak rodné číslo through before applying the rule and its own source calls that unclear;
     * this validator applies the rule to every number, which is the stricter reading.
     */
    fun sk(n: String): Boolean = n.length == SK_LENGTH &&
        n.all(Char::isDigit) &&
        n[0] != '0' &&
        n[2] in SK_THIRD_DIGITS &&
        n.toLong() % MOD_11 == 0L

    /**
     * Czech DIČ (zákon 280/2009 Sb. § 130): a legal entity uses its IČO (8 digits, never starting
     * with 9); a natural person uses the rodné číslo (9 or 10 digits, validated by [RodneCislo]);
     * a person without one gets a "special" 9-digit number starting with 6 and its own check digit.
     */
    fun czDic(n: String): Boolean {
        if (!n.all(Char::isDigit)) return false
        return when (n.length) {
            ICO_LENGTH -> n[0] != '9' && IdentifierChecksums.ico(n)
            RC_LENGTH_SHORT -> RodneCislo.isValid(n) || czSpecial(n)
            RC_LENGTH_LONG -> RodneCislo.isValid(n)
            else -> false
        }
    }

    private fun czSpecial(n: String): Boolean {
        if (n[0] != '6') return false
        val sum = (1 until RC_LENGTH_SHORT - 1).sumOf { (n[it] - '0') * (ICO_LENGTH + 1 - it) } % MOD_11
        val expected = Math.floorMod(ICO_LENGTH - (MOD_10 - sum) % MOD_11, MOD_10)
        return expected == n.last() - '0'
    }

    /** DE USt-IdNr: 9 digits, ISO 7064 MOD 11,10 over the first eight. */
    internal fun de(n: String): Boolean {
        if (n.length != DE_LENGTH || !n.all(Char::isDigit) || n[0] == '0') return false
        var product = MOD_10
        for (i in 0 until DE_LENGTH - 1) {
            val sum = ((n[i] - '0') + product) % MOD_10
            product = (2 * (if (sum == 0) MOD_10 else sum)) % MOD_11
        }
        return (MOD_11 - product) % MOD_10 == n.last() - '0'
    }

    /**
     * FR TVA: 2-character key + 9-digit SIREN. A numeric key must equal
     * (12 + 3 × (SIREN mod 97)) mod 97; the SIREN must be Luhn-valid except for Monaco entities
     * (SIREN prefix `000`), which carry no Luhn digit. An alphanumeric key (the newer allocation
     * scheme) has a different, unpublished derivation, so it passes on format alone.
     */
    internal fun fr(n: String): Boolean {
        if (n.length != FR_LENGTH) return false
        val key = n.take(2)
        val siren = n.drop(2)
        if (!siren.all(Char::isDigit) || !key.all { it.isDigit() || it in 'A'..'Z' }) return false
        if (!siren.startsWith(MONACO_PREFIX) && !IdentifierChecksums.luhn(siren)) return false
        if (!key.all(Char::isDigit)) return true
        return key.toLong() == (FR_KEY_BASE + FR_KEY_FACTOR * (siren.toLong() % MOD_97)) % MOD_97
    }

    /** AT UID: `U` + 8 digits; odd positions doubled with digit sum, check = (10 − (sum + 4) mod 10) mod 10. */
    internal fun at(n: String): Boolean {
        if (n.length != AT_LENGTH || n[0] != 'U' || !n.drop(1).all(Char::isDigit)) return false
        val d = n.drop(1).map { it - '0' }
        val sum = (0 until AT_LENGTH - 2).sumOf { i ->
            if (i % 2 == 1) (d[i] * 2).let { it / MOD_10 + it % MOD_10 } else d[i]
        }
        return (MOD_10 - (sum + AT_OFFSET) % MOD_10) % MOD_10 == d.last()
    }

    private const val ICO_LENGTH = 8
    private const val RC_LENGTH_SHORT = 9
    private const val RC_LENGTH_LONG = 10
    private const val SK_LENGTH = 10
    private const val SK_THIRD_DIGITS = "234789"
    private const val DE_LENGTH = 9
    private const val FR_LENGTH = 11
    private const val AT_LENGTH = 9
    private const val AT_OFFSET = 4
    private const val FR_KEY_BASE = 12
    private const val MONACO_PREFIX = "000"
    private const val FR_KEY_FACTOR = 3
    private const val MOD_10 = 10
    private const val MOD_11 = 11
    private const val MOD_97 = 97L
}
