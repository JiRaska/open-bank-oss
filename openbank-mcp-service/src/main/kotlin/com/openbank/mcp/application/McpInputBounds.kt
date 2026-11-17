// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.domain.account.Iban

/**
 * Input bounds for the JSON-RPC surface (#16628). Every caller-supplied string that is reflected
 * into a response, written to an audit event or forwarded downstream passes through one of these.
 *
 * - [containsNul]: the fleet NUL rule (`NulByteGuards`, #5913) hooks Jackson's String
 *   deserializer, which a `JsonNode`-bound body never uses — so the MCP endpoint scans the decoded
 *   tree itself, keys and values, recursively.
 * - [echo]: caps a reflected value, so the response cannot grow with the input.
 * - [auditKeys]: bounds the argument KEY list written to the audit trail (count and length).
 * - [requireIban]: strict canonical form + libs-domain mod-97 check of an account IBAN, before any consent call.
 * - [transactionLimit]: positive, bounded, integral; default when absent.
 */
object McpInputBounds {
    const val MAX_ECHO_CHARS = 64
    const val MAX_AUDIT_KEYS = 32
    const val MAX_TX_LIMIT = 200
    const val DEFAULT_TX_LIMIT = 50
    private const val TRUNCATED = "...[truncated]"
    private const val IBAN_MIN = 15
    private const val IBAN_MAX = 34
    const val IBAN_PATTERN = "^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$"
    private val IBAN_SHAPE = Regex(IBAN_PATTERN)

    fun echo(value: String): String =
        if (value.length <= MAX_ECHO_CHARS) value else value.take(MAX_ECHO_CHARS) + TRUNCATED

    fun auditKeys(arguments: JsonNode): List<String> {
        val keys = arguments.fieldNames().asSequence().map(::echo).sorted().toList()
        return if (keys.size <= MAX_AUDIT_KEYS) keys else keys.take(MAX_AUDIT_KEYS) + TRUNCATED
    }

    /** True when U+0000 appears in any decoded string, object key included, at any depth. */
    fun containsNul(node: JsonNode): Boolean {
        val stack = ArrayDeque<JsonNode>().apply { add(node) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isTextual && hasNul(n.textValue())) return true
            if (n.isObject && n.fieldNames().asSequence().any(::hasNul)) return true
            if (n.isContainerNode) n.forEach(stack::add)
        }
        return false
    }

    private fun hasNul(s: String): Boolean = s.indexOf('\u0000') >= 0

    /**
     * Strict account IBAN: the canonical uppercase, no-space form ONLY. Consent stores the exact
     * IBAN and ConsentGate compares exactly, so a lowercase or spaced variant is refused rather than
     * normalised. The shape regex runs first so the shared mod-97 check only ever sees [A-Z0-9];
     * the checksum itself is libs-domain's [Iban.isValid] — one implementation fleet-wide.
     */
    fun requireIban(accountId: String): String {
        require(
            accountId.length in IBAN_MIN..IBAN_MAX &&
                IBAN_SHAPE.matches(accountId) &&
                accountId == Iban.of(accountId).value &&
                Iban.isValid(accountId),
        ) { "accountId is not a valid IBAN (uppercase, no spaces)" }
        return accountId
    }

    fun transactionLimit(node: JsonNode): Int {
        if (node.isMissingNode || node.isNull) return DEFAULT_TX_LIMIT
        require(node.isIntegralNumber && node.canConvertToInt() && node.intValue() in 1..MAX_TX_LIMIT) {
            "limit must be an integer between 1 and $MAX_TX_LIMIT"
        }
        return node.intValue()
    }
}
