// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.mcp.application.McpInputBounds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * `propose_payment` on the customer channel (ADR-0383 D6): records ONE payment instruction in
 * delegation-service's proposal store as PENDING, for the customer to approve — or not — in the
 * bank app with SCA. It never executes and never approves: the only store route it can reach is
 * create ([PaymentProposalStoreClient]), and an answer in any state other than PENDING is refused.
 *
 * The arguments are model-composed, so they are validated here to the store's own rules before any
 * network call (cheapest refusal first, and a malformed instruction never costs a store write):
 * the IBAN through [McpInputBounds.requireIban]; the amount as a plain decimal string, positive, at
 * most two fractional digits, at most [MAX_AMOUNT]; the currency CZK or EUR, which also fixes the
 * rail; creditor name and reference trimmed, NFC-normalised and refused — never stripped — if they
 * carry a Cc/Cf/Zl/Zp character, link or markup, exactly the store's `PaymentProposalPayload` rule.
 *
 * Retries: the store's `Idempotency-Key` is DERIVED, never taken from the client. It hashes the
 * grant triple (party, `azp`, `cnf.jkt`), the token's session and the model's `clientRequestId`, so
 * an MCP retry of the same call replays the first answer instead of writing a second proposal, and
 * two clients (or two customers) can never collide on one key.
 */
@ApplicationScoped
class CustomerPaymentProposer @Inject constructor(
    private val store: PaymentProposalStore,
    private val mapper: ObjectMapper,
) {
    /** A validated instruction, already in the store's canonical form. */
    data class Instruction(
        val debtorAccountId: UUID,
        val creditorIban: String,
        val creditorName: String,
        val amount: String,
        val currency: String,
        val operationType: String,
        val reference: String?,
        val clientRequestId: String,
    )

    /** What the store said, reduced to what the tool may act on. */
    sealed interface Result {
        data class Proposed(val body: JsonNode) : Result

        /** [message] is shown to the client verbatim: fixed text, never store or model input. */
        data class Refused(
            val reason: String,
            val message: String?,
            val unavailable: Boolean = false,
            val invalidateGrant: Boolean = false,
        ) : Result
    }

    @Suppress("TooGenericExceptionCaught") // every store failure must become one audited refusal
    fun propose(instruction: Instruction, key: GrantKey, sessionId: String?): Result {
        val request = CreatePaymentProposalRequest(
            partyId = key.partyId,
            mcpClientId = key.clientId,
            keyThumbprint = requireNotNull(key.keyThumbprint) { "payment proposals require a DPoP grant" },
            operationType = instruction.operationType,
            debtorAccountId = instruction.debtorAccountId,
            creditorIban = instruction.creditorIban,
            creditorName = instruction.creditorName,
            amount = instruction.amount,
            currency = instruction.currency,
            reference = instruction.reference,
            connectionId = key.connectionId,
        )
        val receipt = try {
            store.create(idempotencyKey(key, sessionId, instruction.clientRequestId), request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProposalStoreRefusal) {
            return refusal(e)
        } catch (e: Exception) {
            return Result.Refused("proposal store failed: ${e.javaClass.simpleName}", null, unavailable = true)
        }
        return receiptOf(receipt, instruction)
    }

    private fun receiptOf(receipt: McpProposalReceipt, instruction: Instruction): Result {
        val id = receipt.proposalId
        // Fail closed on anything but a fresh or deduplicated PENDING proposal: this tool must never
        // relay an APPROVED/EXECUTED state, whatever answers it (ProposedOnly's rule, store vocabulary).
        if (id == null || receipt.status != PENDING) {
            return Result.Refused("proposal store answered status ${receipt.status}", null, unavailable = true)
        }
        val deduplicated = receipt.deduplicated == true
        val root: ObjectNode = mapper.createObjectNode()
            .put("proposalId", id.toString())
            .put("status", PENDING)
            .put("deduplicated", deduplicated)
            .put("amount", instruction.amount)
            .put("currency", instruction.currency)
            .put("creditorIban", instruction.creditorIban)
        receipt.expiresAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.let { root.put("expiresAt", it.toString()) }
        root.put("summary", summary(instruction, deduplicated))
        return Result.Proposed(root)
    }

    /** Bank-rendered from the validated instruction — never the model's free text or the store's prose. */
    private fun summary(instruction: Instruction, deduplicated: Boolean): String {
        val lead = if (deduplicated) {
            "An identical open proposal for ${instruction.amount} ${instruction.currency} already exists."
        } else {
            "Payment proposal for ${instruction.amount} ${instruction.currency} recorded."
        }
        return "$lead Nothing has been paid: the customer must review and approve it in the bank app " +
            "with strong customer authentication before it expires."
    }

    private fun refusal(e: ProposalStoreRefusal): Result.Refused {
        val reason = "proposal store refused: ${e.status} ${e.code ?: "no code"}"
        return when {
            e.status == FORBIDDEN -> Result.Refused(reason, NOT_PERMITTED, invalidateGrant = true)
            e.status == TOO_MANY -> Result.Refused(reason, LIMIT_REACHED)
            e.status == CONFLICT && e.code == KEY_REUSED -> Result.Refused(reason, REQUEST_ID_REUSED)
            e.status == CONFLICT -> Result.Refused(reason, IN_PROGRESS)
            e.status == BAD_REQUEST -> Result.Refused(reason, INVALID_INSTRUCTION)
            else -> Result.Refused(reason, null, unavailable = true)
        }
    }

    companion object {
        const val PENDING = "PENDING"
        val MAX_AMOUNT: BigDecimal = BigDecimal("1000000.00")
        const val MAX_NAME = 70
        const val MAX_REFERENCE = 140
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private const val CONFLICT = 409
        private const val BAD_REQUEST = 400
        private const val KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
        private const val NOT_PERMITTED = "Not permitted"
        const val LIMIT_REACHED = "Proposal limit reached: the customer already has too many open or recent " +
            "proposals. Ask them to review the pending ones in the bank app."
        const val REQUEST_ID_REUSED = "This clientRequestId was already used for a different payment. " +
            "Use a new clientRequestId for a new proposal."
        const val IN_PROGRESS = "The same proposal is still being recorded. Retry with the same clientRequestId."
        const val INVALID_INSTRUCTION = "The bank refused the payment instruction as invalid."

        private const val KEY_VERSION = "mcp-customer-propose-v1"
        private const val BOUND_KEY_VERSION = "mcp-customer-propose-v2"
        private const val NO_SESSION = "-"

        /**
         * The store's `Idempotency-Key` for one MCP call: SHA-256 over the grant triple, the token's
         * session and the client's request id, unit-separated so no field can shift into another.
         */
        fun idempotencyKey(key: GrantKey, sessionId: String?, clientRequestId: String): String {
            val material = buildList {
                add(if (key.connectionId == null) KEY_VERSION else BOUND_KEY_VERSION)
                add(key.partyId.toString())
                add(key.clientId)
                add(requireNotNull(key.keyThumbprint) { "payment proposals require a DPoP grant" })
                key.connectionId?.let { add(it.toString()) }
                add(sessionId ?: NO_SESSION)
                add(clientRequestId)
            }.joinToString("\u001f")
            return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        // The store's PaymentProposalPayload rule, character for character: Unicode Cc/Cf/Zl/Zp, the
        // markup brackets and backtick, and any URL scheme separator.
        private val UNSAFE = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}<>\\[\\]`]|://")
        private val AMOUNT_SHAPE = Regex("^(0|[1-9][0-9]{0,6})(\\.[0-9]{1,2})?$")
        private val REQUEST_ID_SHAPE = Regex("^[A-Za-z0-9._-]{8,64}$")
        private val UUID_SHAPE = Regex(CustomerReadTools.UUID_PATTERN)
        private val RAILS = mapOf("CZK" to "DOMESTIC_PAYMENT", "EUR" to "SEPA_PAYMENT")

        const val DEBTOR = "debtorAccountId"
        const val IBAN = "creditorIban"
        const val NAME = "creditorName"
        const val AMOUNT = "amount"
        const val CURRENCY = "currency"
        const val REFERENCE = "reference"
        const val REQUEST_ID = "clientRequestId"
        val ARGUMENTS = setOf(DEBTOR, IBAN, NAME, AMOUNT, CURRENCY, REFERENCE, REQUEST_ID)

        /** Validates model-composed arguments to the store's rules; throws [IllegalArgumentException]. */
        @Suppress("CyclomaticComplexMethod") // one require per rule, kept flat so each reads as the rule
        fun parse(args: JsonNode): Instruction {
            require(args.isObject) { "arguments must be an object" }
            val unknown = args.fieldNames().asSequence().filter { it !in ARGUMENTS }.toList()
            require(unknown.isEmpty()) { "unknown argument" }
            val debtor = text(args, DEBTOR)
            require(debtor != null && UUID_SHAPE.matches(debtor)) { "$DEBTOR must be an accountId from list_accounts" }
            val iban = text(args, IBAN)
            require(iban != null && runCatching { McpInputBounds.requireIban(iban) }.isSuccess) {
                "$IBAN must be a valid IBAN (uppercase, no spaces)"
            }
            val currency = text(args, CURRENCY).orEmpty()
            val rail = RAILS[currency] ?: throw IllegalArgumentException("$CURRENCY must be CZK or EUR")
            require(currency != "CZK" || iban.startsWith("CZ")) { "a CZK proposal needs a Czech creditor IBAN" }
            val amount = amountOf(text(args, AMOUNT))
            val name = safeText(text(args, NAME), NAME, MAX_NAME)
                ?: throw IllegalArgumentException("$NAME is required")
            val reference = safeText(text(args, REFERENCE), REFERENCE, MAX_REFERENCE)
            val requestId = text(args, REQUEST_ID)
            require(requestId != null && REQUEST_ID_SHAPE.matches(requestId)) {
                "$REQUEST_ID must be 8-64 characters of A-Z a-z 0-9 . _ -"
            }
            return Instruction(UUID.fromString(debtor), iban, name, amount, currency, rail, reference, requestId)
        }

        private fun text(args: JsonNode, field: String): String? {
            val node = args.path(field)
            if (node.isMissingNode || node.isNull) return null
            require(node.isTextual) { "$field must be a string" }
            return node.asText()
        }

        private fun amountOf(raw: String?): String {
            require(raw != null && AMOUNT_SHAPE.matches(raw)) {
                "$AMOUNT must be a plain decimal string with at most two decimals, e.g. 12.50"
            }
            val value = BigDecimal(raw)
            require(value.signum() > 0) { "$AMOUNT must be greater than zero" }
            require(value <= MAX_AMOUNT) { "$AMOUNT exceeds the proposal ceiling" }
            return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        }

        private fun safeText(raw: String?, field: String, max: Int): String? {
            if (raw == null) return null
            val value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
            require(value.isNotEmpty()) { "$field must not be blank" }
            require(value.length <= max) { "$field must be at most $max characters" }
            require(!UNSAFE.containsMatchIn(value)) { "$field contains control, format or markup characters" }
            return value
        }
    }
}
