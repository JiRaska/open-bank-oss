// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

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
    EU_VAT(null, "VAT", Regex("^[A-Z]{2}[A-Z0-9]{2,12}$"), checksum = false),
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
        IdentifierScheme.FR_SIREN -> luhn(value)
        IdentifierScheme.LEI -> mod97(value)
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
    private const val MOD_10 = 10
    private const val MOD_11 = 11
    private const val MOD_97 = 97
    private const val LUHN_NINE = 9
    private const val LETTER_BASE = 10

    /** The official NIP weights (Ustawa o NIP). A literal table, not arithmetic. */
    @Suppress("MagicNumber")
    private val NIP_WEIGHTS = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
}
