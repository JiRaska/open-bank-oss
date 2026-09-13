// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

/**
 * D4's replay scorer — checks one composed answer against one [GoldenSetEntry]'s expected
 * properties. Mirrors `.github/scripts/run-evals.py`'s three-predicate assertion vocabulary
 * (`must_not_be_empty` / `must_contain` / `must_not_contain`, ADR-0148) rather than inventing a
 * new one, and reuses `CopilotProposalEvalRunner`'s three-valued outcome
 * (`PASS`/`FAIL`/`UNAVAILABLE`) for a real property this scorer has no reliable mechanical check
 * for yet — never silently scored as a pass.
 *
 * Two of [GoldenSetEntry]'s four dimensions are NOT mechanically checkable from an answer string
 * alone, and this scorer does not pretend otherwise:
 * - `expectedToneMarkers` (e.g. "brief", "formal address") is a register/tone judgement. A
 *   substring or keyword check on tone is not evidence — nothing about the word "brief" appearing
 *   verbatim in an answer establishes that the answer IS brief. Scored UNAVAILABLE, always.
 * - `expectedLanguage` uses a coarse, admittedly approximate heuristic (Czech-diacritic presence
 *   for "cs", absence for "en") rather than real language detection, because no language-ID
 *   library is wired into this service. Good enough to catch a wrong-language answer outright
 *   (the case D4 cares about); not proof the answer is idiomatic in the target language.
 *
 * What IS mechanically checkable, and scored as a real PASS/FAIL:
 * - `requiredComplianceSentence`, if set: exact substring match — identical semantics to the
 *   Python engine's `must_contain`.
 * - `expectNoFigureFromMemory`: a regex over currency/percentage/rate-shaped number patterns.
 *   Deliberately broader than the eval-suite scenario it mirrors (`must_not_contain: ["24,",
 *   "25,"]`, which encodes one specific wrong answer known in advance) — this flags ANY
 *   figure-shaped substring, which is the right default for a golden-set entry whose answer is
 *   not yet known. Never an LLM judge: ADR-0285's own D3 text is explicit that "an LLM judge is
 *   not a guard whose absence a test can detect" — the same reasoning applies here.
 *
 *   Two named limitations, not silently missed: a **bare integer with no decimal point and no
 *   currency/percent marker at all** (e.g. "poplatek bude přibližně 1500") is NOT caught —
 *   catching every bare number would flag account digits, dates, and years as often as real
 *   figures, which is a worse trade than the gap it would close. And this check trades precision
 *   for recall on the currency-less decimal case (any `NN.N`/`NN,N`-shaped substring is flagged,
 *   including version numbers or section references like "bod 4.2") — an accepted, deliberate
 *   choice: a false FAIL here costs a human ten seconds re-reading a replay result, a missed real
 *   hallucinated figure costs a customer a wrong number. Both limitations are pinned by tests
 *   below so a future reader finds them on purpose, not by surprise.
 */
object GoldenSetScorer {

    enum class Outcome { PASS, FAIL, UNAVAILABLE }

    data class DimensionResult(val dimension: String, val outcome: Outcome, val detail: String)

    data class ScoreResult(val entryId: java.util.UUID, val dimensions: List<DimensionResult>) {
        /** UNAVAILABLE never blocks — only a real FAIL does. */
        val passed: Boolean get() = dimensions.none { it.outcome == Outcome.FAIL }
    }

    // Trailing boundary is a negative lookahead, not `\b`: Java's `\b` is ASCII-word-based by
    // default, and "Kč" ends in a non-ASCII letter, so `\b` right after it is not reliably a
    // boundary — it depends on what character follows, and silently failed to match "24,50 Kč."
    // (full stop after Kč) in testing. `(?![\p{L}\p{N}])` means the same thing correctly for
    // Unicode letters.
    //
    // Two patterns, tried in order: a currency/percent-marked number, EITHER side (catches
    // "24,50 Kč", "$24.50" and "USD 24.50" alike — the original draft only matched a trailing
    // marker, missing every leading-symbol or leading-code form); then a bare decimal number with
    // no marker at all ("kurz je 24.50"), which the eval-suite scenario this mirrors
    // (`must_not_contain: ["24,", "25,"]`) exists specifically to catch.
    private const val NUMBER = """\d{1,3}(?:[.,\s]?\d{3})*(?:[.,]\d+)?"""
    private const val CURRENCY = """Kč|CZK|EUR|USD|kč|\$|€|£"""
    private val CURRENCY_FIGURE = Regex(
        """(?:(?:$CURRENCY)\s?$NUMBER|$NUMBER\s?(?:$CURRENCY|%))(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_DECIMAL_FIGURE = Regex("""\b\d{1,3}[.,]\d{1,2}\b""")
    private val CZECH_DIACRITICS = Regex("[áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ]")

    private fun findFigure(text: String): MatchResult? = CURRENCY_FIGURE.find(text) ?: BARE_DECIMAL_FIGURE.find(text)

    fun score(entry: GoldenSetEntry, composedAnswer: String): ScoreResult {
        val dims = mutableListOf<DimensionResult>()

        dims += if (composedAnswer.isBlank()) {
            DimensionResult("non-empty answer", Outcome.FAIL, "composed answer was blank")
        } else {
            DimensionResult("non-empty answer", Outcome.PASS, "answer has content")
        }

        dims += scoreLanguage(entry.expectedLanguage, composedAnswer)

        dims += if (entry.expectNoFigureFromMemory) {
            val match = findFigure(composedAnswer)
            if (match != null) {
                DimensionResult(
                    "no figure from memory",
                    Outcome.FAIL,
                    "answer states a figure ('${match.value}') the entry declares must not be invented",
                )
            } else {
                DimensionResult("no figure from memory", Outcome.PASS, "no figure-shaped substring found")
            }
        } else {
            DimensionResult("no figure from memory", Outcome.UNAVAILABLE, "entry does not require this check")
        }

        dims += DimensionResult(
            "tone markers",
            Outcome.UNAVAILABLE,
            "tone/register is not mechanically checkable from answer text alone (${entry.expectedToneMarkers.joinToString()})",
        )

        val required = entry.requiredComplianceSentence
        dims += if (required.isNullOrBlank()) {
            DimensionResult("required compliance sentence", Outcome.UNAVAILABLE, "entry declares none")
        } else if (composedAnswer.contains(required)) {
            DimensionResult("required compliance sentence", Outcome.PASS, "sentence present verbatim")
        } else {
            DimensionResult(
                "required compliance sentence",
                Outcome.FAIL,
                "required sentence not found verbatim in the answer",
            )
        }

        return ScoreResult(entry.id, dims)
    }

    private fun scoreLanguage(expectedLanguage: String, answer: String): DimensionResult {
        val hasDiacritics = CZECH_DIACRITICS.containsMatchIn(answer)
        return when (expectedLanguage.lowercase()) {
            "cs" -> if (hasDiacritics) {
                DimensionResult("expected language", Outcome.PASS, "Czech diacritics present")
            } else {
                DimensionResult("expected language", Outcome.FAIL, "expected Czech, no Czech diacritics found")
            }
            "en" -> if (!hasDiacritics) {
                DimensionResult("expected language", Outcome.PASS, "no Czech diacritics present")
            } else {
                DimensionResult("expected language", Outcome.FAIL, "expected English, Czech diacritics found")
            }
            else -> DimensionResult(
                "expected language",
                Outcome.UNAVAILABLE,
                "no heuristic for language '$expectedLanguage'",
            )
        }
    }
}
