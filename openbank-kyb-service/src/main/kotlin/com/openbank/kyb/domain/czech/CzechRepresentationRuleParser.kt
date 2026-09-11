// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.czech

import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import java.text.Normalizer

/**
 * Turns the free-text *způsob jednání* from the Czech public register into a
 * [RepresentationRule] (ADR-0284 D2). Heuristic on purpose and biased to the SAFE side: any
 * phrasing it does not recognise yields [RepresentationMode.UNKNOWN], which routes the case to
 * manual review — never a signer count lower than the register may require.
 *
 * ## What "safe" rests on, measured (issue #9709)
 *
 * The failure that matters is reading a JOINT rule as SOLE: the bank would then contract a company
 * on one signature where the register demands two. The register writes plenty of two-signature
 * rules that never use the word *společně*, so a "no *společně*" test alone cannot carry this:
 *
 * > Za družstvo jedná navenek předseda nebo místopředseda. Je-li však pro právní úkon … předepsána
 * > písemná forma, je třeba podpisu **alespoň dvou členů představenstva**.
 *
 * **The property is held by the ORDER of [parse], not by a veto.** Every joint branch runs before
 * [sole], so a text matching [secondSignature] is claimed as JOINT_N and never reaches the solo
 * patterns — which is why those patterns can afford a generous window. A veto inside [sole] was
 * tried and removed: [sole] runs only when [jointCount] declined, so the condition was provably
 * unreachable, and removing it changed no verdict in the corpus. `an ordering flip is caught by`
 * `CzechRepresentationRuleParserTest.a written-form second signature outranks the solo opening`,
 * which is the test that fails if the branches are ever reordered.
 *
 * Measured over 142 live *způsob jednání* texts (2026-09-11): 97 SOLE, 10 JOINT_N, 6 role-
 * constrained, 2 JOINT_ALL, 26 UNKNOWN — and **none** of the 97 SOLE texts demands a second
 * signature.
 *
 * ## Word order
 *
 * The register writes both orders and the earlier patterns only accepted one, which is the single
 * largest cause of the 31% UNKNOWN rate in that sample: `jednatel … samostatně` was recognised and
 * `jedná samostatně jednatel` was not; `dva členové … společně` was recognised and
 * `jednají společně dva členové` was not. Each family below therefore carries both directions.
 */
object CzechRepresentationRuleParser {

    /** Czech numerals as a *způsob jednání* spells them; the values ARE the numbers. */
    @Suppress("MagicNumber")
    private val numberWords = mapOf(
        "jeden" to 1, "jedna" to 1, "jednoho" to 1,
        "dva" to 2, "dve" to 2, "dvou" to 2, "dvema" to 2,
        "tri" to 3, "trech" to 3, "tremi" to 3,
        "ctyri" to 4, "ctyr" to 4, "ctyrmi" to 4,
    )

    private const val NUM = "\\b(\\d+|jeden|jedna|jednoho|dva|dve|dvou|dvema|tri|trech|tremi|ctyri|ctyr|ctyrmi)\\b"
    private const val SIGNER = "(?:jednatele|jednatelu|jednatel|clenove|cleny|clenu|clena|clen|spolecniku|spolecnici)"
    private const val OFFICE = "(?:predseda|predsedy|predsedou|mistopredseda|mistopredsedy|mistopredsedou|" +
        "reditel|reditele|jednatel|jednatele|clen|clena|clenem)"

    private val jointlyAll =
        listOf(
            "vsichni jednatele spolecne",
            "vsichni clenove predstavenstva spolecne",
            "spolecne vsichni",
            "vsichni spolecne",
        )

    /**
     * Both orders, and a short gap between the numeral and its noun — the register writes
     * `vždy dva (2) členové představenstva společně`, where the two are not adjacent.
     */
    private val jointlyNounFirst =
        Regex("(?:alespon|nejmene|minimalne)?\\s*$NUM[^.]{0,16}?\\s$SIGNER[^.]*spolecne")
    private val jointlyAdverbFirst =
        Regex("spolecne[^.]{0,24}?(?:alespon|nejmene|minimalne)?\\s*$NUM[^.]{0,16}?\\s$SIGNER")

    /**
     * A second signature demanded WITHOUT the word *společně* — the družstvo written-form clause,
     * and `podpisu předsedy a místopředsedy`. Doubles as the SOLE veto; see the class KDoc.
     */
    private val secondSignature =
        Regex("(?:alespon|nejmene|minimalne)\\s*$NUM\\s*$SIGNER|podpis\\w*\\s+predsedy\\s+a\\s+mistopredsedy")

    /**
     * A pair of NAMED offices. `spolu s` and a plain `a` both occur, so neither literal can be
     * required. Yields [RepresentationRule.requiredRoles], never a bare count — see that field.
     */
    private val rolePair =
        Regex("($OFFICE)(?:\\s+\\w+){0,8}?\\s+(?:spolecne\\s+s|spolu\\s+s|a)\\s+(?:\\w+\\s+){0,2}?($OFFICE)")

    /**
     * A COUNT qualified by an office: *dva členové představenstva společně, z nichž jeden musí být
     * předsedou*. The count is met by any two members and the rule is not, so this is the same
     * class as [rolePair] and must not fall through to a plain [RepresentationMode.JOINT_N].
     */
    private val roleQualifier =
        Regex("z nichz\\s+(?:alespon\\s+)?(?:jeden|jedna|jednoho)\\s+musi\\s+byt\\s+($OFFICE)")

    /**
     * The gap is generous (`jednatel jedná jménem společnosti ve všech úkonech samostatně` is 45
     * characters of it) and that is only safe because [secondSignature] and *společně* veto this
     * branch outright — widening the window cannot reach across a joint clause.
     */
    private val solo = Regex("($OFFICE|kazdy|kterykoli)[^.]{0,80}(samostatne|sam\\b)")
    private val soloAdverbFirst = Regex("(samostatne)[^.]{0,80}($OFFICE)")
    private val soloBare = Regex("(?:jedna|jednaji|zastupuje|zastupuji|podepisuje)\\s+(?:\\w+\\s+){0,3}samostatne")

    /** The register does sometimes hold the whole rule as one word. */
    private val soloShort = Regex("^(samostatne|jedna samostatne|jednatel jedna samostatne)\\.?$")

    fun parse(text: String?): RepresentationRule {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return RepresentationRule.UNKNOWN
        val t = fold(raw)
        return jointAll(t, raw)
            ?: roleConstrained(t, raw)
            ?: jointCount(t, raw)
            ?: sole(t, raw)
            ?: conditional(t, raw)
            ?: RepresentationRule(RepresentationMode.UNKNOWN, null, raw)
    }

    private fun jointAll(t: String, raw: String): RepresentationRule? =
        if (jointlyAll.any { t.contains(it) }) RepresentationRule(RepresentationMode.JOINT_ALL, null, raw) else null

    /**
     * Ordered BEFORE [jointCount] deliberately: `předseda představenstva spolu s jedním členem`
     * would otherwise answer `JOINT_N(2)`, and any two members satisfy a count of two while not
     * satisfying that rule.
     */
    private fun roleConstrained(t: String, raw: String): RepresentationRule? {
        if (!t.contains(JOINT_WORD) && !t.contains("spolu s")) return null
        roleQualifier.find(t)?.let { q ->
            return RepresentationRule(
                RepresentationMode.JOINT_N,
                countIn(t) ?: PAIR,
                raw,
                requiredRoles = listOf(q.groupValues[1]),
            )
        }
        val m = rolePair.find(t) ?: return null
        val roles = listOf(m.groupValues[1], m.groupValues[2])
        // Two mentions of the same office is "two board members", a count — not a named pair.
        if (roles[0] == roles[1]) return null
        return RepresentationRule(RepresentationMode.JOINT_N, PAIR, raw, requiredRoles = roles)
    }

    private fun jointCount(t: String, raw: String): RepresentationRule? =
        countIn(t)?.let { RepresentationRule(RepresentationMode.JOINT_N, it, raw) }
            ?: secondSignature.find(t)?.let {
                RepresentationRule(RepresentationMode.JOINT_N, countOf(it.groupValues[1]) ?: PAIR, raw)
            }

    /**
     * Reached ONLY when [jointCount] declined, which is what keeps a two-signature rule out of here
     * — see the ordering note in the class KDoc. The remaining guard is live: a text can carry
     * *společně* without matching any counting pattern, and must not fall through to SOLE.
     */
    private fun sole(t: String, raw: String): RepresentationRule? {
        if (t.contains(JOINT_WORD)) return null
        val matched = soloShort.matches(t) ||
            solo.containsMatchIn(t) ||
            soloAdverbFirst.containsMatchIn(t) ||
            soloBare.containsMatchIn(t)
        return if (matched) RepresentationRule(RepresentationMode.SOLE, 1, raw) else null
    }

    /**
     * "Jednatel jedná samostatně; v záležitostech nad X Kč jednají dva jednatelé společně." — a
     * threshold-conditional rule. The framework agreement is the higher-value act, so the STRICTER
     * count applies; with no count stated, two, never one.
     */
    private fun conditional(t: String, raw: String): RepresentationRule? {
        if (!t.contains(SOLO_WORD) || !t.contains(JOINT_WORD)) return null
        return RepresentationRule(RepresentationMode.JOINT_N, countIn(t) ?: PAIR, raw)
    }

    private fun countIn(t: String): Int? =
        (jointlyNounFirst.find(t) ?: jointlyAdverbFirst.find(t))?.let { countOf(it.groupValues[1]) }

    private fun countOf(token: String): Int? = (token.toIntOrNull() ?: numberWords[token])?.takeIf { it >= 1 }

    private const val SOLO_WORD = "samostatne"
    private const val JOINT_WORD = "spolecne"
    private const val PAIR = 2

    /** Lower-case, diacritics stripped, whitespace collapsed — so `společně` and `spolecne` are one token. */
    internal fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}
