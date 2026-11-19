// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

/**
 * The national part of a VAT number (the text after the two-letter prefix) checked against each
 * member state's published check-digit rule (#16774). Every function is pure, takes the already
 * normalised national part and answers only "is this a possible number", never "is it registered".
 *
 * Sources. The structure of every number is the one in the Commission's list of VAT number
 * formats, https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A52016XC1223%2802%29 (OJ
 * 2016/C 481/08), and the VIES service, https://ec.europa.eu/taxation_customs/vies/. That list gives
 * the shape and not the check-digit arithmetic, which the member states publish in national
 * guidance in their own language and which is reproduced in python-stdnum 2.2; each function names
 * the stdnum module it follows, at https://arthurdejong.org/python-stdnum/doc/2.2/stdnum.<module>.html.
 * Where stdnum itself says an algorithm "has not been confirmed by an independent source" the KDoc
 * of that function repeats it.
 *
 * The weight tables are literal data taken from those rules, not arithmetic, hence the
 * `MagicNumber` suppression on this object (detekt flags every digit of a published table).
 */
@Suppress("MagicNumber")
internal object EuVatNationalRules {
    // ---- ES -------------------------------------------------------------------------------

    private const val DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE"
    private const val ES_CIF_KINDS = "ABCDEFGHJNPQRSUVW"

    /**
     * ES NIF/NIE/CIF (stdnum `es.nif`, `es.cif`, `es.dni`, `es.nie`), 9 characters. A DNI, `K`/`L`/`M`
     * number or NIE (`X`/`Y`/`Z` read as 0/1/2) ends in the letter `DNI_LETTERS[number mod 23]`;
     * a company CIF starts with an organisation letter and ends in the Luhn digit of its 7 digits
     * OR the matching letter of `JABCDEFGHI` — sources disagree on which organisation types use
     * which, so both are accepted, as stdnum does.
     */
    fun es(n: String): Boolean {
        if (n.length != 9 || !vatDigits(n.substring(1, 8))) return false
        val first = n[0]
        return when {
            first in "KLM" -> n[8] == dniLetter(n.substring(1, 8))
            first.isDigit() -> vatDigits(n.substring(0, 8)) && n[8] == dniLetter(n.substring(0, 8))
            first in "XYZ" -> n[8] == dniLetter("XYZ".indexOf(first).toString() + n.substring(1, 8))
            first in ES_CIF_KINDS -> {
                val check = vatLuhnDigit(n.substring(1, 8))
                n[8] == '0' + check || n[8] == "JABCDEFGHI"[check]
            }
            else -> false
        }
    }

    private fun dniLetter(num: String) = DNI_LETTERS[num.toInt() % 23]

    // ---- IT -------------------------------------------------------------------------------

    /**
     * IT partita IVA (stdnum `it.iva`): 11 digits, the first seven not all zero, the tax-office
     * code in digits 8-10 in 001..100 or 120, 121, 888, 999, the whole number Luhn-valid.
     */
    fun it(n: String): Boolean {
        if (n.length != 11 || !vatDigits(n) || n.substring(0, 7).all { it == '0' }) return false
        val office = n.substring(7, 10).toInt()
        return (office in 1..100 || office in setOf(120, 121, 888, 999)) && vatLuhnOk(n)
    }

    // ---- NL -------------------------------------------------------------------------------

    /**
     * NL btw-id (stdnum `nl.btw`, `nl.bsn`): 9 digits, `B`, a 2-digit non-zero suffix. The 9 digits
     * are either a BSN (elfproef: sum of (9-i)·digit over the first eight minus the ninth, mod 11)
     * or, for the numbers issued from 2020-01-01 to sole proprietors, `NL`+number is ISO 7064
     * MOD 97-10 valid. The number must be positive.
     */
    fun nl(n: String): Boolean {
        if (n.length != 12 || n[9] != 'B') return false
        if (!vatDigits(n.substring(0, 9)) || !vatDigits(n.substring(10))) return false
        if (n.substring(0, 9).toLong() <= 0 || n.substring(10).toInt() <= 0) return false
        val bsn = (0 until 8).sumOf { (9 - it) * vatDigit(n, it) } - vatDigit(n, 8)
        return bsn % 11 == 0 || vatMod97("NL$n")
    }

    // ---- BE -------------------------------------------------------------------------------

    /**
     * BE ondernemingsnummer (stdnum `be.vat`): 10 digits (the pre-2005 9-digit form is not
     * accepted — VIES uses 10), first digit 0 or 1, first eight + last two ≡ 0 mod 97.
     */
    fun be(n: String): Boolean {
        if (n.length != 10 || !vatDigits(n)) return false
        if (n[0] !in "01" || n.toLong() <= 0) return false
        return (n.substring(0, 8).toInt() + n.substring(8).toInt()) % 97 == 0
    }

    // ---- PT -------------------------------------------------------------------------------

    /** PT NIF (stdnum `pt.nif`): 9 digits, first not 0, check = (11 − Σ(9−i)·dᵢ) mod 11 mod 10. */
    fun pt(n: String): Boolean {
        if (n.length != 9 || !vatDigits(n) || n[0] == '0') return false
        val s = (0 until 8).sumOf { (9 - it) * vatDigit(n, it) }
        return Math.floorMod(11 - s, 11) % 10 == vatDigit(n, 8)
    }

    // ---- IE -------------------------------------------------------------------------------

    private const val IE_ALPHABET = "WABCDEFGHIJKLMNOPQRSTUV"

    /**
     * IE VAT (stdnum `ie.vat`): 8 or 9 characters. New form: 7 digits + check letter (+ optional
     * second letter, which weighs 9 in the sum); old form: digit, letter, 5 digits, check letter,
     * checked as the 7-digit number `0` + digits 3-7 + the first digit.
     */
    fun ie(n: String): Boolean {
        if (n.length !in 8..9 || !n[0].isDigit() || !vatDigits(n.substring(2, 7))) return false
        if (!n.substring(7).all { it in IE_ALPHABET }) return false
        val second = if (n.length == 9) IE_ALPHABET.indexOf(n[8]) else 0
        return when {
            vatDigits(n.substring(0, 7)) -> n[7] == IE_ALPHABET[(ieSum(n.substring(0, 7)) + 9 * second) % 23]
            n[1] in 'A'..'Z' -> n[7] == IE_ALPHABET[ieSum("0" + n.substring(2, 7) + n[0]) % 23]
            else -> false
        }
    }

    private fun ieSum(seven: String) = (0 until 7).sumOf { (8 - it) * vatDigit(seven, it) }

    // ---- LU -------------------------------------------------------------------------------

    /** LU TVA (stdnum `lu.tva`): 8 digits, the last two = (first six as a number) mod 89. */
    fun lu(n: String): Boolean =
        n.length == 8 && vatDigits(n) && n.substring(0, 6).toInt() % 89 == n.substring(6).toInt()
}

/** Nordic and Baltic VAT rules; sources as in [EuVatNationalRules]. */
@Suppress("MagicNumber")
internal object EuVatNordicBaltic {
    // ---- DK -------------------------------------------------------------------------------

    private val DK_WEIGHTS = intArrayOf(2, 7, 6, 5, 4, 3, 2, 1)

    /** DK CVR (stdnum `dk.cvr`): 8 digits, first not 0, weights 2,7,6,5,4,3,2,1, sum ≡ 0 mod 11. */
    fun dk(n: String) = n.length == 8 && vatDigits(n) && n[0] != '0' && vatWeighted(n, DK_WEIGHTS) % 11 == 0

    // ---- FI -------------------------------------------------------------------------------

    private val FI_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1)

    /** FI ALV (stdnum `fi.alv`): 8 digits, weights 7,9,10,5,8,4,2,1, sum ≡ 0 mod 11. */
    fun fi(n: String) = n.length == 8 && vatDigits(n) && vatWeighted(n, FI_WEIGHTS) % 11 == 0

    // ---- SE -------------------------------------------------------------------------------

    /** SE moms (stdnum `se.vat`, `se.orgnr`): 10-digit organisation number (Luhn) + the literal `01`. */
    fun se(n: String): Boolean {
        if (n.length != 12 || !vatDigits(n) || !n.endsWith("01")) return false
        return vatLuhnOk(n.substring(0, 10))
    }

    // ---- EE -------------------------------------------------------------------------------

    private val EE_WEIGHTS = intArrayOf(3, 7, 1, 3, 7, 1, 3, 7, 1)

    /** EE KMKR (stdnum `ee.kmkr`): 9 digits, weights 3,7,1,3,7,1,3,7,1, sum ≡ 0 mod 10. */
    fun ee(n: String) = n.length == 9 && vatDigits(n) && vatWeighted(n, EE_WEIGHTS) % 10 == 0

    // ---- LV -------------------------------------------------------------------------------

    private val LV_LEGAL_WEIGHTS = intArrayOf(9, 1, 4, 8, 3, 10, 2, 5, 7, 6, 1)
    private val LV_PERSON_WEIGHTS = intArrayOf(10, 5, 8, 4, 2, 1, 6, 3, 7, 9)

    /**
     * LV PVN (stdnum `lv.pvn`): 11 digits. First digit above 3: a legal entity, Σwᵢdᵢ with weights
     * 9,1,4,8,3,10,2,5,7,6,1 ≡ 3 mod 11. Otherwise a personal code (a real birth date unless it
     * starts `32`) with check = (1 + Σwᵢdᵢ) mod 11 mod 10; stdnum notes that personal-code
     * algorithm "has not been confirmed by an independent source".
     */
    fun lv(n: String): Boolean {
        if (n.length != 11 || !vatDigits(n)) return false
        if (n[0] > '3') return vatWeighted(n, LV_LEGAL_WEIGHTS) % 11 == 3
        if (!n.startsWith("32")) {
            val year = 1800 + vatDigit(n, 6) * 100 + n.substring(4, 6).toInt()
            if (!vatValidDate(year, n.substring(2, 4).toInt(), n.substring(0, 2).toInt())) return false
        }
        return (1 + vatWeighted(n, LV_PERSON_WEIGHTS)) % 11 % 10 == vatDigit(n, 10)
    }

    // ---- LT -------------------------------------------------------------------------------

    /**
     * LT PVM (stdnum `lt.pvm`): 9 digits with a `1` in position 8 (legal entity) or 12 digits with a
     * `1` in position 11; check = Σ(1 + i mod 9)·dᵢ mod 11, recomputed with weights (1 + (i+2) mod 9)
     * when that is 10, then mod 11 mod 10.
     */
    fun lt(n: String): Boolean {
        val legal = n.length == 9 && n[7] == '1'
        val person = n.length == 12 && n[10] == '1'
        if (!vatDigits(n) || !(legal || person)) return false
        val last = n.length - 1
        var check = (0 until last).sumOf { (1 + it % 9) * vatDigit(n, it) } % 11
        if (check == 10) check = (0 until last).sumOf { (1 + (it + 2) % 9) * vatDigit(n, it) }
        return check % 11 % 10 == vatDigit(n, last)
    }
}

/** Central and south-eastern VAT rules; sources as in [EuVatNationalRules]. */
@Suppress("MagicNumber")
internal object EuVatCentralSouthEast {
    // ---- SI -------------------------------------------------------------------------------

    /** SI DDV (stdnum `si.ddv`): 8 digits, first not 0, check = 11 − Σ(8−i)·dᵢ mod 11 (10 → 0; 11 is invalid). */
    fun si(n: String): Boolean {
        if (n.length != 8 || !vatDigits(n) || n[0] == '0') return false
        val check = 11 - (0 until 7).sumOf { (8 - it) * vatDigit(n, it) } % 11
        return check != 11 && (if (check == 10) 0 else check) == vatDigit(n, 7)
    }

    // ---- HR -------------------------------------------------------------------------------

    /** HR OIB (stdnum `hr.oib`): 11 digits, ISO 7064 MOD 11,10 over all of them. */
    fun hr(n: String): Boolean {
        if (n.length != 11 || !vatDigits(n)) return false
        var check = 5
        for (c in n) check = ((if (check == 0) 10 else check) * 2 % 11 + (c - '0')) % 10
        return check == 1
    }

    // ---- HU -------------------------------------------------------------------------------

    private val HU_WEIGHTS = intArrayOf(9, 7, 3, 1, 9, 7, 3, 1)

    /** HU ANUM (stdnum `hu.anum`): 8 digits, weights 9,7,3,1,9,7,3,1, sum ≡ 0 mod 10. */
    fun hu(n: String) = n.length == 8 && vatDigits(n) && vatWeighted(n, HU_WEIGHTS) % 10 == 0

    // ---- RO -------------------------------------------------------------------------------

    private val RO_WEIGHTS = intArrayOf(7, 5, 3, 2, 1, 7, 5, 3, 2)
    private val RO_CNP_WEIGHTS = intArrayOf(2, 7, 9, 1, 4, 6, 3, 5, 8, 2, 7, 9)
    private val RO_COUNTIES = (1..48).toSet() + setOf(51, 52, 70, 80, 81, 82, 83)

    /**
     * RO CIF (stdnum `ro.cf`, `ro.cui`, `ro.cnp`): a company CUI of 2-10 digits, first not 0,
     * check digit = 10·Σwᵢdᵢ mod 11 mod 10 over the payload left-padded to 9 digits with weights
     * 7,5,3,2,1,7,5,3,2; or the 13-digit CNP of a sole trader (first digit 1-9, a real birth
     * date, a known county code, mod 11 check digit with weights 2,7,9,1,4,6,3,5,8,2,7,9). stdnum
     * notes the CNP check "has not been confirmed by an independent source".
     */
    fun ro(n: String): Boolean {
        if (!vatDigits(n)) return false
        return when (n.length) {
            13 -> roCnp(n)
            in 2..10 -> n[0] != '0' && roCui(n)
            else -> false
        }
    }

    private fun roCui(n: String): Boolean {
        val payload = n.dropLast(1).padStart(9, '0')
        return 10 * vatWeighted(payload, RO_WEIGHTS) % 11 % 10 == vatDigit(n, n.length - 1)
    }

    private fun roCnp(n: String): Boolean {
        if (n[0] == '0') return false
        val century = when (n[0]) {
            '3', '4' -> 1800
            '5', '6' -> 2000
            else -> 1900
        }
        if (!vatValidDate(century + n.substring(1, 3).toInt(), n.substring(3, 5).toInt(), n.substring(5, 7).toInt())) {
            return false
        }
        if (n.substring(7, 9).toInt() !in RO_COUNTIES) return false
        val sum = vatWeighted(n, RO_CNP_WEIGHTS) % 11
        return (if (sum == 10) 1 else sum) == vatDigit(n, 12)
    }

    // ---- BG -------------------------------------------------------------------------------

    private val BG_EGN_WEIGHTS = intArrayOf(2, 4, 8, 5, 10, 9, 7, 3, 6)
    private val BG_PNF_WEIGHTS = intArrayOf(21, 19, 17, 13, 11, 9, 7, 3, 1)
    private val BG_OTHER_WEIGHTS = intArrayOf(4, 3, 2, 7, 6, 5, 4, 3, 2)

    /**
     * BG VAT (stdnum `bg.vat`, `bg.egn`, `bg.pnf`): 9 digits for a legal entity (check = Σ(i+1)·dᵢ
     * mod 11, or with weights i+3 when that is 10, then mod 10); 10 digits for a natural person,
     * foreigner or other: a valid EGN (birth date + mod 11 check), a valid PNF (mod 10 check), or
     * the "other" rule check = (11 − Σwᵢdᵢ) mod 11 with weights 4,3,2,7,6,5,4,3,2 (10 is invalid).
     */
    fun bg(n: String): Boolean {
        if (!vatDigits(n)) return false
        return when (n.length) {
            9 -> {
                var check = (0 until 8).sumOf { (it + 1) * vatDigit(n, it) } % 11
                if (check == 10) check = (0 until 8).sumOf { (it + 3) * vatDigit(n, it) } % 11
                check % 10 == vatDigit(n, 8)
            }
            10 -> bgEgn(n) ||
                vatWeighted(n, BG_PNF_WEIGHTS) % 10 == vatDigit(n, 9) ||
                Math.floorMod(11 - vatWeighted(n, BG_OTHER_WEIGHTS), 11) == vatDigit(n, 9)
            else -> false
        }
    }

    private fun bgEgn(n: String): Boolean {
        var year = 1900 + n.substring(0, 2).toInt()
        var month = n.substring(2, 4).toInt()
        if (month > 40) {
            year += 100
            month -= 40
        } else if (month > 20) {
            year -= 100
            month -= 20
        }
        return vatValidDate(year, month, n.substring(4, 6).toInt()) &&
            vatWeighted(n, BG_EGN_WEIGHTS) % 11 % 10 == vatDigit(n, 9)
    }
}

/** Mediterranean VAT rules and the Northern Ireland `XI` rule; sources as in [EuVatNationalRules]. */
@Suppress("MagicNumber")
internal object EuVatMediterranean {
    // ---- EL -------------------------------------------------------------------------------

    /** EL ΑΦΜ (stdnum `gr.vat`): 9 digits (the old 8-digit form is not accepted), check = (2·fold) mod 11 mod 10, fold = fold·2 + dᵢ. */
    fun el(n: String): Boolean {
        if (n.length != 9 || !vatDigits(n)) return false
        var acc = 0
        for (i in 0 until 8) acc = acc * 2 + vatDigit(n, i)
        return acc * 2 % 11 % 10 == vatDigit(n, 8)
    }

    // ---- CY -------------------------------------------------------------------------------

    private val CY_ODD = intArrayOf(1, 0, 5, 7, 9, 13, 15, 17, 19, 21)

    /** CY ΦΠΑ (stdnum `cy.vat`): 8 digits (not starting `12`) + check letter = A..Z[(Σ odd-position table + Σ even-position digits) mod 26]. */
    fun cy(n: String): Boolean {
        if (n.length != 9 || !vatDigits(n.substring(0, 8)) || n.startsWith("12")) return false
        val sum = (0 until 8).sumOf { if (it % 2 == 0) CY_ODD[vatDigit(n, it)] else vatDigit(n, it) }
        return n[8] == 'A' + sum % 26
    }

    // ---- MT -------------------------------------------------------------------------------

    private val MT_WEIGHTS = intArrayOf(3, 4, 6, 7, 8, 9, 10, 1)

    /** MT VAT (stdnum `mt.vat`): 8 digits, first not 0, weights 3,4,6,7,8,9,10,1, sum ≡ 0 mod 37. */
    fun mt(n: String) = n.length == 8 && vatDigits(n) && n[0] != '0' && vatWeighted(n, MT_WEIGHTS) % 37 == 0

    // ---- XI -------------------------------------------------------------------------------

    private val GB_WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 1)

    /**
     * XI (Northern Ireland, stdnum `gb.vat`): 9 digits (or 12 with a 3-digit branch suffix),
     * Σwᵢdᵢ over the first nine with weights 8,7,6,5,4,3,2,10,1 ≡ 0 mod 97 (also 42 or 55 when the
     * first three digits are 100 or more); or a 5-character `GD`/`HA` government/health-authority code.
     */
    fun xi(n: String): Boolean = when {
        n.length == 5 -> vatDigits(n.substring(2)) && govOrHealth(n.substring(0, 2), n.substring(2).toInt())
        n.length == 11 && n.take(6) in setOf("GD8888", "HA8888") ->
            vatDigits(n.substring(6)) &&
                govOrHealth(n.substring(0, 2), n.substring(6, 9).toInt()) &&
                n.substring(6, 9).toInt() % 97 == n.substring(9).toInt()
        n.length == 9 || n.length == 12 -> vatDigits(n) &&
            gbSum(n) in
            if (n.substring(0, 3).toInt() >= 100) setOf(0, 42, 55) else setOf(0)
        else -> false
    }

    private fun govOrHealth(prefix: String, number: Int) =
        prefix == "GD" && number < 500 || prefix == "HA" && number >= 500

    private fun gbSum(n: String) = vatWeighted(n, GB_WEIGHTS) % 97
}
