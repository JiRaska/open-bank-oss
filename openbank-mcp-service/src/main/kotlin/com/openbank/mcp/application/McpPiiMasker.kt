// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import jakarta.enterprise.context.ApplicationScoped

/**
 * Enforces the `mcp-anonymous` charter's `data_scope.pii: masked`
 * (`openbank-libs/governance/agents.yaml`) on everything an MCP tool hands back to a calling model.
 *
 * Until this existed the charter advertised a control that no code implemented: after ADR-0195
 * step 4 wired the real read ports, `get_balance` / `list_transactions` / `list_accounts` returned
 * raw IBANs, account-holder and counterparty names and free-text remittance narratives verbatim
 * into a model context (#2412). The masking is applied ONCE, as a single response-shaping step in
 * [McpToolRegistry.call], deliberately not field-by-field inside each read adapter — a per-adapter
 * policy drifts per tool, and a NEW tool would silently ship unmasked.
 *
 * Policy (deny-by-default in spirit: an unrecognised field is left alone only because it is not
 * PII-shaped; anything that *looks* like an IBAN is masked wherever it appears, whatever its key):
 *  - IBAN-shaped values and IBAN-named fields keep only the last [IBAN_TAIL] characters, so a model
 *    can still correlate "the account ending 1234" across tool calls without holding the identifier.
 *  - Natural-person identifiers (names, contact details, addresses, national/tax/document ids,
 *    dates of birth) and free-text payment narratives are replaced wholesale with [MASK].
 *  - Amounts, currencies, timestamps, statuses and internal surrogate ids are NOT masked — they
 *    carry no natural-person identity and are the whole point of a consent-scoped read.
 *
 * Masking is unconditional: there is no config toggle, because the only charter this service acts
 * under says `masked` and a switch that can turn a declared control off is how the declaration
 * became fiction in the first place.
 */
@ApplicationScoped
class McpPiiMasker(private val mapper: ObjectMapper) {

    /** Returns a masked deep copy of [node]; the input is never mutated. */
    fun mask(node: JsonNode): JsonNode = maskNode(node, fieldName = null)

    private fun maskNode(node: JsonNode, fieldName: String?): JsonNode = when {
        node.isObject -> maskObject(node)
        node.isArray -> mapper.createArrayNode().apply {
            node.forEach { add(maskNode(it, fieldName)) }
        }
        node.isTextual -> maskText(node.asText(), fieldName)
        else -> node
    }

    private fun maskObject(node: JsonNode): ObjectNode = mapper.createObjectNode().apply {
        node.fields().forEach { (name, value) -> set<JsonNode>(name, maskNode(value, name)) }
    }

    private fun maskText(value: String, fieldName: String?): JsonNode {
        val key = fieldName?.lowercase().orEmpty()
        return when {
            IBAN_FIELDS.any { key.contains(it) } || looksLikeIban(value) -> TextNode.valueOf(tail(value))
            // A surrogate id (account/consent/transaction UUID) identifies a row, not a person, and
            // is what lets a model chain one tool call into the next — never redacted.
            UUID_PATTERN.matches(value) -> TextNode.valueOf(value)
            REDACTED_FIELDS.any { key.contains(it) } -> TextNode.valueOf(MASK)
            else -> TextNode.valueOf(value)
        }
    }

    private fun looksLikeIban(value: String): Boolean = IBAN_PATTERN.matches(value.replace(" ", ""))

    /** Keeps the last [IBAN_TAIL] characters of an identifier, e.g. `CZ65…4567` -> `****4567`. */
    private fun tail(value: String): String {
        val compact = value.replace(" ", "")
        return if (compact.length <= IBAN_TAIL) MASK else MASK_PREFIX + compact.takeLast(IBAN_TAIL)
    }

    private companion object {
        const val MASK = "***"
        const val MASK_PREFIX = "****"
        const val IBAN_TAIL = 4

        val IBAN_PATTERN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$")
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Field-name fragments whose value is an account identifier — masked to a correlatable tail. */
        val IBAN_FIELDS = setOf("iban", "accountnumber", "bban", "pan", "cardnumber")

        /**
         * Field-name fragments whose value identifies a natural person or is free text a payer
         * typed (which routinely carries a name, a phone number or a case reference).
         */
        val REDACTED_FIELDS = setOf(
            "name", "holder", "owner", "counterparty", "payee", "payer", "debtor", "creditor",
            "email", "phone", "msisdn", "address", "street", "city", "postal", "zip",
            "birth", "nationalid", "personalid", "taxid", "ssn", "passport", "identitydocument",
            "narrative", "description", "remittance", "memo", "note", "message", "purpose",
        )
    }
}
