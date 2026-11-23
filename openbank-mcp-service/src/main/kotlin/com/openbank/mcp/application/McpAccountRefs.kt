// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.mcp.application.port.out.ConsentContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Caller-scoped opaque account handles (#16678, ADR-0383 D7).
 *
 * The masker returns an account's IBAN as `****1234` (data minimisation towards the third-party
 * model), so the model cannot pass it back as `accountId` — tool chaining `list_accounts` ->
 * `get_balance` was broken. Every result object carrying an `iban` now also carries
 * `accountRef = "acr_" + base64url(HMAC-SHA256(key, scope || iban))`, where scope is the caller's
 * (agentId, consentId, sessionId). The model gets a handle it can chain, never the identifier.
 *
 * **A ref is never decoded.** HMAC is one-way: [resolve] recomputes the ref of every IBAN in the
 * CALLER'S OWN account list (the consent-validated `list_accounts` read) for the CALLER'S OWN scope
 * and compares in constant time. So a ref minted for another agent, consent or session matches
 * nothing and is refused, and a tampered ref matches nothing and is refused. Outside MCP the value
 * names no account anywhere — no service accepts it.
 *
 * The key is shared by every replica (a ref from `list_accounts` must resolve on whichever pod takes
 * the next call), supplied as config and checked at boot by `AccountRefKeyConfig`; it is never
 * logged and never part of an exception message.
 */
class McpAccountRefs(
    key: ByteArray,
    /**
     * Told each time a handle is withheld or refused for want of a session id, with the operation
     * (`issue` or `resolve`). Wired to `openbank_mcp_account_ref_unavailable_total{reason="no_session"}`
     * by `AccountRefKeyConfig`, so sid-less callers silently losing chaining stays visible.
     */
    private val onUnavailable: (op: String) -> Unit = {},
) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size >= MIN_KEY_BYTES) { "account-ref key must be at least $MIN_KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), ALGORITHM)
    }

    /**
     * The handle for [iban] as seen by the caller [ctx], or null when [ctx] carries no session id.
     *
     * Fail closed on a missing `sid`: without it the scope would be (agent, consent) only, so every
     * session of that agent under that consent would share refs. Rather than silently issuing a
     * weaker handle, no handle is issued at all, and [resolve] refuses every ref for such a caller.
     */
    fun refFor(ctx: ConsentContext, iban: String): String? {
        val session = ctx.sessionId?.takeIf { it.isNotBlank() } ?: return null
        val mac = Mac.getInstance(ALGORITHM).apply { init(keySpec) }
        mac.update(DOMAIN.toByteArray(Charsets.UTF_8))
        // Length-prefixed fields, all non-null by construction (session checked above), so no choice
        // of agentId/consentId/sid can make two scopes collide and null vs "" cannot arise.
        listOf(ctx.agentId, ctx.consentId, session, iban).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return PREFIX + ENCODER.encodeToString(mac.doFinal())
    }

    /**
     * The IBAN behind [ref] among [callerIbans] (the caller's own consent-scoped account list), or
     * null — always null for a caller without a session id. Every candidate is compared, so timing
     * does not reveal which position matched.
     */
    fun resolve(ctx: ConsentContext, ref: String, callerIbans: Collection<String>): String? {
        if (!isRefShape(ref)) return null
        if (ctx.sessionId.isNullOrBlank()) {
            onUnavailable(OP_RESOLVE)
            return null
        }
        val presented = ref.toByteArray(Charsets.UTF_8)
        var match: String? = null
        callerIbans.forEach { iban ->
            val expected = refFor(ctx, iban) ?: return null
            if (MessageDigest.isEqual(presented, expected.toByteArray(Charsets.UTF_8)) && match == null) {
                match = iban
            }
        }
        return match
    }

    /**
     * Attaches server-generated refs to [shaped], the ALREADY masked and sanitised copy of [raw].
     *
     * Runs AFTER the masker and sanitiser, so the handle needs no sanitiser exemption: an upstream
     * value under `accountRef` was sanitised like any other field, and for an object that has a
     * valid raw `iban` it is overwritten here by the server's own ref. The two trees have the same
     * shape (the masker and sanitiser rewrite values, not structure), so the walk pairs them by
     * field name and array index; anything that does not pair is left as sanitised.
     */
    fun attach(ctx: ConsentContext, raw: JsonNode, shaped: JsonNode): JsonNode {
        if (ctx.sessionId.isNullOrBlank()) {
            if (carriesIban(raw)) onUnavailable(OP_ISSUE)
            return shaped
        }
        val out = shaped.deepCopy<JsonNode>()
        pair(ctx, raw, out)
        return out
    }

    private fun carriesIban(node: JsonNode): Boolean =
        node.findValues(IBAN_FIELD).any { it.isTextual && McpIbanDetection.isIban(it.asText()) }

    private fun pair(ctx: ConsentContext, raw: JsonNode, out: JsonNode) {
        when {
            raw.isObject && out is ObjectNode -> {
                val iban = raw.get(IBAN_FIELD)
                if (iban != null && iban.isTextual && McpIbanDetection.isIban(iban.asText())) {
                    refFor(ctx, iban.asText())?.let { out.put(REF_FIELD, it) }
                }
                raw.properties().forEach { (name, child) -> out.get(name)?.let { pair(ctx, child, it) } }
            }
            raw.isArray && out.isArray && raw.size() == out.size() ->
                for (i in 0 until raw.size()) pair(ctx, raw.get(i), out.get(i))
        }
    }

    companion object {
        const val REF_FIELD = "accountRef"
        const val IBAN_FIELD = "iban"
        const val MIN_KEY_BYTES = 32
        const val PREFIX = "acr_"
        const val OP_ISSUE = "issue"
        const val OP_RESOLVE = "resolve"
        private const val ALGORITHM = "HmacSHA256"
        private const val DOMAIN = "openbank-mcp-account-ref/v2"

        /** `acr_` + 43 base64url characters (256-bit MAC, unpadded). */
        private val REF_SHAPE = Regex("^acr_[A-Za-z0-9_-]{43}$")
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun isRefShape(value: String): Boolean = REF_SHAPE.matches(value)

        /**
         * A per-instance random key: refs do not survive a restart or cross replicas. Only for a
         * registry built by hand (unit tests); the CDI bean always takes the configured key.
         */
        fun ephemeral(onUnavailable: (String) -> Unit = {}): McpAccountRefs =
            McpAccountRefs(ByteArray(MIN_KEY_BYTES).also { SecureRandom().nextBytes(it) }, onUnavailable)
    }
}
