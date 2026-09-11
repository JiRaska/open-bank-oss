// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.domain

/**
 * ADR-0285 D3's mandatory save-time control: a business editor is a new injection vector, and
 * this is the first of the two guards ("both mandatory") that keep them from weakening the
 * core. Deterministic, closed rule set — never an LLM judgement, because "a guard is proven by
 * what it rejects" and an LLM judge cannot be held to a known-positive/known-negative test the
 * way a regex set can (the same argument the ADR's own Alternatives section makes against an
 * LLM-judged whole-prompt editor).
 *
 * Six rejection classes, matching D3 verbatim:
 *  1. instruction-shaped content addressing the model's own rules (English + Czech forms)
 *  2. tool-name / tool-schema references
 *  3. promises of a completed action ("the payment is done")
 *  4. amounts or exchange rates asserted as fact
 *  5. personal-data patterns (birth number, IBAN, card PAN — ADR-0176 D1 sensitivity classes)
 *  6. secret-shaped tokens
 * plus a size cap per persona (ADR-0218 budget-cap logic).
 *
 * A caller passes every free-text field an editor can set; [lint] returns every violation, not
 * just the first, so an editor sees the whole problem in one round trip (mirrors
 * `check-security-checklist.py`'s "list every unticked box" reasoning: report the whole set
 * once rather than dribbling out one rejection per save).
 */
object CommStyleLinter {

    /** Total characters across every editable field, before retrieval trims it further (D3). */
    const val MAX_TOTAL_CHARS = 4000

    // (?:all |the |previous |above |prior )* — zero or more modifiers, in any combination
    // ("ignore all previous instructions" stacks two), not a single either/or choice.
    private val INSTRUCTION_OVERRIDE = Regex(
        "ignore (?:all |the |previous |above |prior )*instructions" +
            "|disregard (?:all |the |previous |above |prior )*instructions" +
            "|you are now|act as if you (are|were)|pretend (you are|to be)" +
            "|developer mode|maintenance mode|debug mode|jailbreak" +
            "|ignoruj (?:všechny |předchozí )*(instrukce|pokyny)" +
            "|zapomeň na (?:všechny |předchozí )*(instrukce|pokyny)" +
            "|vývojářský režim|servisní režim|ladicí režim",
        RegexOption.IGNORE_CASE,
    )

    private val SYSTEM_PROMPT_EXFILTRATION = Regex(
        "system prompt|your instructions|reveal your (rules|prompt|instructions)" +
            "|repeat (the text|everything) above" +
            "|systémový prompt|tvoje instrukce|prozraď (svá |tvá )?pravidla",
        RegexOption.IGNORE_CASE,
    )

    private val TOOL_REFERENCE = Regex(
        "\\btool[_ ]?call\\b|\\bfunction[_ ]?call\\b|\\bapi[_ ]?key\\b" +
            "|\\bmcp[_ ]?tool\\b|\\btool[_ ]?schema\\b|\\bjson[_ ]?schema\\b",
        RegexOption.IGNORE_CASE,
    )

    private val PROMISED_ACTION = Regex(
        "(the )?payment (is|has been) (done|completed|sent)" +
            "|(the )?transfer (is|has been) (done|completed|sent)" +
            "|i have (transferred|sent|paid|moved)" +
            "|platba (je|byla) (provedena|odeslána|hotová)" +
            "|převod (je|byl) (proveden|odeslán|hotový)" +
            "|(já )?jsem (převedl|odeslal|zaplatil)",
        RegexOption.IGNORE_CASE,
    )

    /** A number followed by an ISO-4217-shaped code or a CZK/EUR/USD word — asserted as fact. */
    private val AMOUNT_AS_FACT = Regex(
        "\\d[\\d ,.]*\\s?(CZK|EUR|USD|Kč|korun)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val EXCHANGE_RATE_AS_FACT = Regex(
        "(exchange|kurz)\\s?rate\\s?(is|=|:)?\\s?\\d|kurz\\s?(je|=|:)?\\s?\\d",
        RegexOption.IGNORE_CASE,
    )

    // Structural detection only (does the text CONTAIN something IBAN/PAN/RČ-shaped) — masking
    // an already-known value is PiiMask's job (openbank-libs-domain), a different question.
    private val IBAN_SHAPED = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b")
    private val PAN_SHAPED = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val RODNE_CISLO_SHAPED = Regex("\\b\\d{6}/?\\d{3,4}\\b")

    private val SECRET_SHAPED = Regex(
        "\\b(sk|pk|api)[-_][A-Za-z0-9_-]{16,}\\b" +
            "|\\bBEGIN (RSA |EC )?PRIVATE KEY\\b" +
            "|\\bAKIA[0-9A-Z]{16}\\b",
    )

    data class Violation(val rule: String, val field: String)

    /**
     * Runs every rule against every field in [fields] (field name -> free text) plus the
     * combined length against [MAX_TOTAL_CHARS]. Returns the empty list when clean.
     */
    fun lint(fields: Map<String, String>): List<Violation> {
        val violations = mutableListOf<Violation>()
        val checks = listOf(
            "instruction-override" to INSTRUCTION_OVERRIDE,
            "system-prompt-exfiltration" to SYSTEM_PROMPT_EXFILTRATION,
            "tool-reference" to TOOL_REFERENCE,
            "promised-action" to PROMISED_ACTION,
            "amount-as-fact" to AMOUNT_AS_FACT,
            "exchange-rate-as-fact" to EXCHANGE_RATE_AS_FACT,
            "iban-shaped" to IBAN_SHAPED,
            "pan-shaped" to PAN_SHAPED,
            "national-id-shaped" to RODNE_CISLO_SHAPED,
            "secret-shaped" to SECRET_SHAPED,
        )
        for ((field, text) in fields) {
            for ((rule, pattern) in checks) {
                if (pattern.containsMatchIn(text)) {
                    violations += Violation(rule, field)
                }
            }
        }
        val total = fields.values.sumOf { it.length }
        if (total > MAX_TOTAL_CHARS) {
            violations += Violation("size-cap-exceeded ($total/$MAX_TOTAL_CHARS chars)", "*")
        }
        return violations
    }
}
