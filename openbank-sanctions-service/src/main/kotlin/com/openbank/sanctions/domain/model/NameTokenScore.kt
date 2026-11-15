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
 * It only ever lowers a candidate's score (see [combine]), so it cannot raise an alert the
 * trigram search would not have raised: it removes false positives, never adds recall.
 */
object NameTokenScore {

    /** Below this a pair of name parts is treated as two different names. */
    const val PART_FLOOR = 0.80

    private const val WINKLER_PREFIX = 4
    private const val WINKLER_SCALE = 0.1
    private const val HALF = 2.0
    private const val JARO_TERMS = 3.0

    /** Best part-aligned score between any of [queryNames] and any of [entryNames]. */
    fun best(queryNames: Collection<String>, entryNames: Collection<String>): Double {
        var best = 0.0
        for (q in queryNames) {
            val qp = parts(q)
            if (qp.isEmpty()) continue
            for (e in entryNames) best = max(best, score(qp, parts(e)))
        }
        return best
    }

    /** The screening score: the candidate's trigram score, capped by its name-structure score. */
    fun combine(trigramScore: Double, queryNames: Collection<String>, entryNames: Collection<String>): Double =
        min(trigramScore, best(queryNames, entryNames))

    /**
     * Lower-cased, diacritic-free name parts. Apostrophes join (`sa'idi` is one part), any other
     * punctuation separates, [NOISE] words are dropped and a fused Arabic article is stripped
     * (`alghamdi` and `al-ghamdi` both become `ghamdi`).
     */
    fun parts(name: String): List<String> = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .replace(Regex("['\u2019`]"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() && it !in NOISE }
        .map(::stripFusedArticle)

    private fun stripFusedArticle(part: String): String =
        if (part.length > FUSED_ARTICLE_MIN && (part.startsWith("al") || part.startsWith("el"))) part.drop(2) else part

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

    /** A part longer than this that starts with `al`/`el` is a fused article plus a name. */
    private const val FUSED_ARTICLE_MIN = 5

    /** What a pair of parts scores when only their consonant skeleton agrees, or an initial. */
    private const val PHONETIC_SCORE = 0.90
    private const val INITIAL_SCORE = 0.85

    /**
     * Two parts' similarity. Jaro-Winkler first; then two cheap rescues for what it scores badly:
     * transliterations that keep the consonants (`adanov`/`adnuf`, `hammam`/`humam`, `wez`/`wazz`)
     * and an initial standing for a name (`j` for `jiri`).
     */
    internal fun partScore(a: String, b: String): Double {
        var s = jaroWinkler(a, b)
        val ka = skeleton(a)
        if (ka.length >= 2 && ka == skeleton(b)) s = max(s, PHONETIC_SCORE)
        if ((a.length == 1 || b.length == 1) && a[0] == b[0]) s = max(s, INITIAL_SCORE)
        return s
    }

    /** Consonant skeleton with common transliteration pairs folded together and repeats collapsed. */
    internal fun skeleton(part: String): String {
        val folded = part.replace("ph", "f").replace("kh", "k").replace("sh", "s").replace("ch", "k")
            .map { SKELETON_FOLD[it] ?: it }
            .filter { it !in "aeiou" }
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

    internal fun jaroWinkler(a: String, b: String): Double {
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
