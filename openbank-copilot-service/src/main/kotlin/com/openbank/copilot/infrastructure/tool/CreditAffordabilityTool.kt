// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.application.CreditAiLevelResolver
import com.openbank.copilot.application.port.out.CopilotTool
import com.openbank.copilot.application.port.out.ToolResult
import com.openbank.copilot.domain.AffordabilityVerdict
import com.openbank.copilot.domain.CreditAffordability
import com.openbank.copilot.domain.CreditAiLevel
import com.openbank.copilot.infrastructure.client.CreditProfileReadClient
import com.openbank.copilot.infrastructure.client.CreditQuoteDto
import com.openbank.copilot.infrastructure.client.CreditQuoteRequestDto
import com.openbank.copilot.infrastructure.client.CustomerEdgeRestClient
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * L1 ADVISOR tool — "can I afford this?" (ADR-0269 rule 5).
 *
 * The one tool in this service whose job is to be willing to say no.
 *
 * ## What it does not do
 *
 * It computes no price: the instalment comes from the edge's quote route (rule 4). It computes no
 * verdict in the model: [CreditAffordability] decides, and the model narrates. It offers nothing —
 * a customer asking whether they can afford something has not asked to be sold anything, and this
 * tool returns an assessment with no product attached.
 *
 * ## Why the level check is here and not only in the policy gate
 *
 * The ADR-0034 policy gate authorises the CAPABILITY; the level is about which of the customer's
 * OWN consents are in force, which the gate has no view of. Both apply. An L0 customer gets a
 * refusal that names what would unlock it, because a silent "I can't help with that" reads as the
 * assistant being broken rather than as a setting the customer controls.
 */
@ApplicationScoped
class CreditAffordabilityTool(
    @param:RestClient private val edge: CustomerEdgeRestClient,
    @param:RestClient private val profiles: CreditProfileReadClient,
    private val levels: CreditAiLevelResolver,
    private val identity: SecurityIdentity,
) : CopilotTool {

    override val name = "credit_affordability"
    override val description =
        "Assess whether the customer could afford a loan of a given amount and term, with the workings. " +
            "Answers COMFORTABLE, TIGHT, UNAFFORDABLE or UNKNOWN — it may refuse, and never offers a product."
    override val capability = "credit.affordability.read"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "amount" to mapOf("type" to "string", "description" to "Requested amount, decimal"),
            "termMonths" to mapOf("type" to "integer", "description" to "Repayment term in months"),
        ),
        "required" to listOf("amount", "termMonths"),
    )

    override suspend fun call(arguments: JsonNode): ToolResult {
        val partyId = partyId() ?: return ToolResult(NO_PARTY, isError = true)
        if (!levels.levelFor(partyId).atLeast(CreditAiLevel.L1_ADVISOR)) {
            return ToolResult(NEEDS_L1, isError = true)
        }
        val request = parseRequest(arguments).getOrElse {
            return ToolResult(it.message ?: "Invalid request.", isError = true)
        }

        val quote = try {
            edge.quoteCredit(request).awaitSuspending()
        } catch (e: WebApplicationException) {
            // A suppressed quote (409) is not a tool failure and must not be narrated as one: the
            // bank declined to price, which is itself the answer the customer needs to hear.
            return if (e.response?.status == CONFLICT) {
                ToolResult(SUPPRESSED)
            } else {
                ToolResult("Pricing is unavailable right now (HTTP ${e.response?.status ?: 0}).", isError = true)
            }
        }
        val instalment = quote.monthlyPayment.toBigDecimalOrNull()
            ?: return ToolResult("Pricing returned no instalment.", isError = true)

        val profile = runCatching { profiles.profile(partyId).awaitSuspending() }.getOrNull()
        val answer = CreditAffordability.assess(
            incomeMonthly = profile?.incomeMonthly?.toBigDecimalOrNull(),
            obligationsMonthly = profile?.outflowMonthly?.toBigDecimalOrNull(),
            netMonthly = profile?.netMonthly?.toBigDecimalOrNull(),
            instalment = instalment,
        )
        return ToolResult(render(answer, quote))
    }

    /** Validation, split out so [call] stays one readable flow (and inside detekt's complexity cap). */
    private fun parseRequest(arguments: JsonNode): Result<CreditQuoteRequestDto> {
        val amount = arguments.get("amount")?.asText()?.trim()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(ToolError("Missing required 'amount'."))
        if (amount.toBigDecimalOrNull() == null) {
            return Result.failure(ToolError("'$amount' is not a valid amount."))
        }
        val term = arguments.get("termMonths")?.asInt() ?: 0
        if (term <= 0) return Result.failure(ToolError("Missing or invalid 'termMonths'."))
        return Result.success(CreditQuoteRequestDto(amount, term))
    }

    /**
     * The model sees the verdict, the reasons AND the numbers behind them. Nothing is left for it
     * to compute, which is the ADR-0089 D4 rule and the reason an enthusiastic narration cannot
     * turn an UNAFFORDABLE into a maybe.
     */
    private fun render(answer: com.openbank.copilot.domain.AffordabilityAnswer, quote: CreditQuoteDto): String =
        buildString {
            append("verdict=${answer.verdict}")
            append("; reasons=${answer.reasons.joinToString(",")}")
            append("; instalment=${quote.monthlyPayment} ${quote.currency}")
            append("; aprcPercent=${quote.aprcPercent ?: "unavailable"}")
            answer.dstiAfter?.let { append("; dstiAfter=$it") }
            answer.monthlySurplusAfter?.let { append("; monthlySurplusAfter=$it") }
            if (answer.verdict == AffordabilityVerdict.UNKNOWN) append("; note=$UNKNOWN_NOTE")
        }

    private fun partyId(): UUID? = (identity.principal as? JsonWebToken)?.getClaim<String>("party_id")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun String?.toBigDecimalOrNull(): BigDecimal? =
        this?.takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /** Carries a caller-facing message out of validation without inventing an exception hierarchy. */
    private class ToolError(override val message: String) : Exception(message)

    private companion object {
        const val CONFLICT = 409
        const val NO_PARTY = "This assistant cannot identify the customer, so it will not assess affordability."
        const val NEEDS_L1 =
            "Affordability advice is switched off. The customer can enable it in Settings → " +
                "Financial health and offers; nothing is assessed until they do."
        const val SUPPRESSED =
            "verdict=NOT_PRICED; reasons=SUPPRESSED; note=The bank is not pricing credit for this customer " +
                "right now. Say so plainly and do not estimate a figure."
        const val UNKNOWN_NOTE =
            "Not enough income history to answer. Say that; do not guess, and do not encourage."
    }
}
