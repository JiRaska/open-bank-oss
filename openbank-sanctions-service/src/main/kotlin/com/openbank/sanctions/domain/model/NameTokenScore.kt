// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Name-structure score: how well the individual name PARTS of a screened name align with the
 * parts of a list entry's name, in 0.0–1.0.
 *
 * The candidate search ranks by pg_trgm `word_similarity` over the whole string, which counts
 * shared trigrams and is blind to which part of the name they came from. A shared given name plus
 * the first letters of a surname is therefore enough: `Jiří Raška` scores 0.64 against
 * `Jiří Rathouský`, `Jiří Zlatuška` and `Jüri Ratas` alike — three different people, matched on
 * "Jiří" and "Ra". This score aligns part with part instead, and a part with no counterpart close
 * enough to be a spelling, transliteration or typo variant contributes nothing.
 *
 * Shape of the score:
 *  - every part is compared by Jaro-Winkler, which tolerates the variants a list actually
 *    carries (`Wladimir`/`Vladimir` 0.92, `Muhammad`/`Mohammed` 0.85, `Qadhafi`/`Gaddafi` 0.81)
 *    and rejects different surnames (`Raška`/`Rathouský` 0.63, `Raška`/`Ratas` 0.79);
 *  - parts are paired greedily by best score, each used once, and a pair under [PART_FLOOR]
 *    counts as 0 — "same first name, different surname" is not half a match, it is no match;
 *  - the sum is divided by the SHORTER name's part count, so an omitted middle name or patronymic
 *    (`Vladimir Putin` vs `Vladimir Vladimirovich Putin`) does not dilute a true match.
 *
 * It never raises a candidate's score, and it only removes or lowers one where it has told the
 * names apart clearly (see [decide]): it removes false positives, never adds recall.
 */
object NameTokenScore {

    /** Below this a pair of name parts is treated as two different names. */
    const val PART_FLOOR = 0.80

    /** Best part-aligned score between any of [queryNames] and any of [entryNames]. */
    fun best(queryNames: Collection<String>, entryNames: Collection<String>): Double {
        val entryParts = entryNames.map(::parts).filter { it.isNotEmpty() }
        var best = 0.0
        for (q in queryNames) {
            val qp = parts(q)
            if (qp.isEmpty()) continue
            for (ep in entryParts) best = max(best, score(qp, ep))
        }
        return best
    }

    /**
     * The deciding score of a trigram candidate, or null when the candidate is no match at all.
     *
     * Fail-closed by construction: the name-structure score may only REMOVE a candidate it has
     * clearly told apart (below [reviewFloor], e.g. a shared given name with a different surname),
     * or lower it within the band the caller acts on. Where it lands between [reviewFloor] and the
     * caller's [searchFloor] it has neither confirmed nor refuted the match, so the trigram score
     * stands — a PAYMENT screen (floor 0.85) never turns a HIT into a POTENTIAL_HIT that its
     * policy would treat as REVIEW, or as CLEAR for an internal transfer. With the CUSTOMER floor
     * (0.65) that band is empty and the result is simply the lower of the two scores.
     */
    fun decide(
        trigramScore: Double,
        queryNames: Collection<String>,
        entryNames: Collection<String>,
        searchFloor: Double,
        reviewFloor: Double,
    ): Double? {
        val structure = best(queryNames, entryNames)
        return when {
            structure < reviewFloor -> null
            structure < searchFloor -> trigramScore
            else -> min(trigramScore, structure)
        }
    }

    /**
     * Lower-cased, diacritic-free name parts. Apostrophes join (`sa'idi` is one part), any other
     * punctuation separates and [NOISE] words are dropped — unless that leaves nothing, in which
     * case every part is kept (`Abu Abd` is a name, not noise). A short vowel-less part is a run
     * of initials and splits into them (`VV` → `v`, `v`).
     */
    fun parts(name: String): List<String> {
        val all = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(APOSTROPHES, "")
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }
        val meaningful = all.filter { it !in NOISE }.ifEmpty { all }
        return meaningful.flatMap { if (isInitials(it)) it.map(Char::toString) else listOf(it) }
    }

    private fun isInitials(part: String): Boolean =
        part.length in 2..MAX_INITIALS && part.all { it.isLetter() && it !in VOWELS && it != 'y' }

    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val APOSTROPHES = Regex("['\u2019`]")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private const val VOWELS = "aeiou"
    private const val MAX_INITIALS = 3

    /**
     * Words that say nothing about who a person is: honorifics, generational suffixes, and the
     * articles and kinship particles list entries spell inconsistently (`al-`, `bin`, `ibn`, `abu`,
     * `van`, `de`) — kept, they would align with each other and pass as a matching part.
     */
    private val NOISE = setOf(
        "mr", "mrs", "ms", "dr", "ing", "mgr", "jr", "sr", "ii", "iii", "iv",
        "al", "el", "bin", "ibn", "bint", "ben", "abu", "abd",
        "van", "von", "de", "der", "da", "di", "du", "la", "le", "del",
    )

    /** What a pair of parts scores when only their consonant skeleton agrees, or an initial. */
    private const val PHONETIC_SCORE = 0.90
    private const val INITIAL_SCORE = 0.85

    /**
     * Two parts' similarity. Jaro-Winkler first; then two cheap rescues for what it scores badly:
     * transliterations that keep the consonants (`adanov`/`adnuf`, `hammam`/`humam`, `wez`/`wazz`)
     * and an initial standing for a name (`j` for `jiri`).
     */
    internal fun partScore(a: String, b: String): Double {
        // A fused Arabic article (`alghamdi` vs `ghamdi`) is tried as an alternative, never
        // imposed: stripping it unconditionally turned `alexei`/`aleksey` (0.82) into
        // `exei`/`eksey` (0.67) and lost the most common Russian given names.
        var s = rawPartScore(a, b)
        val sa = withoutArticle(a)
        val sb = withoutArticle(b)
        if (sa != a || sb != b) s = max(s, rawPartScore(sa, sb))
        return s
    }

    private fun withoutArticle(part: String): String =
        if (part.length > ARTICLE_LENGTH + 2 && (part.startsWith("al") || part.startsWith("el"))) {
            part.drop(ARTICLE_LENGTH)
        } else {
            part
        }

    private const val ARTICLE_LENGTH = 2

    private fun rawPartScore(a: String, b: String): Double {
        var s = JaroWinkler.similarity(a, b)
        val ka = skeleton(a)
        if (ka.length >= 2 && ka == skeleton(b)) s = max(s, PHONETIC_SCORE)
        if ((a.length == 1 || b.length == 1) && a[0] == b[0]) s = max(s, INITIAL_SCORE)
        return s
    }

    /** Consonant skeleton with common transliteration pairs folded together and repeats collapsed. */
    internal fun skeleton(part: String): String {
        val folded = part.replace("ph", "f").replace("kh", "k").replace("sh", "s").replace("ch", "k")
            .map { SKELETON_FOLD[it] ?: it }
            .filter { it !in VOWELS }
        val out = StringBuilder()
        for (c in folded) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    private val SKELETON_FOLD = mapOf(
        'w' to 'f',
        'v' to 'f',
        'q' to 'k',
        'c' to 'k',
        'g' to 'k',
        'z' to 's',
        'j' to 'i',
        'y' to 'i',
    )

    internal fun score(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val pairs = buildList {
            for (i in a.indices) for (j in b.indices) add(Triple(i, j, partScore(a[i], b[j])))
        }.sortedByDescending { it.third }
        val usedA = BooleanArray(a.size)
        val usedB = BooleanArray(b.size)
        var sum = 0.0
        for ((i, j, s) in pairs) {
            if (usedA[i] || usedB[j]) continue
            usedA[i] = true
            usedB[j] = true
            if (s >= PART_FLOOR) sum += s
        }
        return sum / min(a.size, b.size)
    }
}

/** Jaro-Winkler similarity of two strings, 0.0–1.0. */
internal object JaroWinkler {
    private const val WINKLER_PREFIX = 4
    private const val WINKLER_SCALE = 0.1
    private const val HALF = 2.0
    private const val JARO_TERMS = 3.0

    fun similarity(a: String, b: String): Double {
        val j = jaro(a, b)
        var prefix = 0
        while (prefix < min(WINKLER_PREFIX, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return j + prefix * WINKLER_SCALE * (1 - j)
    }

    @Suppress("ReturnCount")
    private fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val matchedA = BooleanArray(a.length)
        val matchedB = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val from = max(0, i - window)
            val to = min(b.length - 1, i + window)
            for (k in from..to) {
                if (!matchedB[k] && a[i] == b[k]) {
                    matchedA[i] = true
                    matchedB[k] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        val transpositions = transpositions(a, b, matchedA, matchedB)
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - transpositions / HALF) / m) / JARO_TERMS
    }

    private fun transpositions(a: String, b: String, matchedA: BooleanArray, matchedB: BooleanArray): Int {
        var count = 0
        var k = 0
        for (i in a.indices) {
            if (!matchedA[i]) continue
            while (!matchedB[k]) k++
            if (a[i] != b[k]) count++
            k++
        }
        return count
    }
}
