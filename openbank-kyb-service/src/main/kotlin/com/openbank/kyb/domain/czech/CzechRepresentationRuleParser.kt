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
 * The phrasings below are the ones that actually occur in the register (measured over a sample of
 * s.r.o. and a.s. records); the tests pin each family.
 */
object CzechRepresentationRuleParser {

    /** Czech numerals as a *způsob jednání* spells them; the values ARE the numbers. */
    @Suppress("MagicNumber")
    private val numberWords = mapOf(
        "jeden" to 1, "jedna" to 1, "jednoho" to 1,
        "dva" to 2, "dvou" to 2, "dvema" to 2,
        "tri" to 3, "trech" to 3, "tremi" to 3,
        "ctyri" to 4, "ctyr" to 4, "ctyrmi" to 4,
    )

    private val jointlyAll =
        listOf(
            "vsichni jednatele spolecne",
            "vsichni clenove predstavenstva spolecne",
            "spolecne vsichni",
            "vsichni spolecne",
        )
    private val jointlyN =
        Regex(
            "(?:alespon|nejmene|minimalne)?\\s*(\\d+|jeden|dva|dve|tri|ctyri)\\s+(?:jednatele|jednatel|clenove|cleny|clenu|clen|clenove predstavenstva)[^.]*spolecne",
        )
    private val jointlyPair =
        Regex(
            "(jednatel|clen predstavenstva|predseda predstavenstva|mistopredseda predstavenstva)[^.]*spolecne s[^.]*(jednatel|clen|predseda|mistopredseda)",
        )
    private val solo =
        Regex("(kazdy|kazdy z|kterykoli|jednatel|clen predstavenstva|predseda predstavenstva)[^.]*(samostatne|sam)")
    private val soloShort = Regex("^(samostatne|jedna samostatne|jednatel jedna samostatne)\\.?$")

    fun parse(text: String?): RepresentationRule {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return RepresentationRule.UNKNOWN
        val t = fold(raw)
        return jointAll(t, raw)
            ?: jointCount(t, raw)
            ?: sole(t, raw)
            ?: conditional(t, raw)
            ?: RepresentationRule(RepresentationMode.UNKNOWN, null, raw)
    }

    private fun jointAll(t: String, raw: String): RepresentationRule? =
        if (jointlyAll.any { t.contains(it) }) RepresentationRule(RepresentationMode.JOINT_ALL, null, raw) else null

    private fun jointCount(t: String, raw: String): RepresentationRule? {
        countIn(t)?.let { return RepresentationRule(RepresentationMode.JOINT_N, it, raw) }
        return if (jointlyPair.containsMatchIn(t) && !t.contains(SOLO_WORD)) {
            RepresentationRule(RepresentationMode.JOINT_N, PAIR, raw)
        } else {
            null
        }
    }

    private fun sole(t: String, raw: String): RepresentationRule? =
        if (soloShort.matches(t) || (solo.containsMatchIn(t) && !t.contains(JOINT_WORD))) {
            RepresentationRule(RepresentationMode.SOLE, 1, raw)
        } else {
            null
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

    private fun countIn(t: String): Int? = jointlyN.find(t)?.let { m ->
        (m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1]])?.takeIf { it >= 1 }
    }

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
