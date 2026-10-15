// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * The documents pension-service's document-bound SCA challenges sign (ADR-0335,
 * `docs/ux/pension-client-flows.md` SCA table). pension-service spends these challenges itself, so
 * the edge never consumes them: it only tells the app the exact `approvalRequestId` and
 * `payloadSha256` to raise the sca-service APPROVAL challenge with. Where the document is the
 * request itself (mandate set-up and cancel, strategy change) the hash is computed here with the
 * same canonical form pension-service uses; where it is issued upstream (schedule and beneficiary
 * preview, annuity offers) the edge reads it from pension-service.
 *
 * The `pension-` approval namespace is reserved to pension-service (sca-service refuses to let any
 * other consumer spend it), which is why the edge's own edge-bound gate uses `pension.<op>:`.
 */
internal object PensionDocuments {
    private val HEX = Regex("^[0-9a-f]{64}$")

    fun sha256(document: String): String = MessageDigest.getInstance("SHA-256")
        .digest(document.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** The linking for `pension-<operation>:<hash>`, or null when [hash] is not a SHA-256 hex digest. */
    fun linking(operation: String, hash: String?): Map<String, String>? {
        val digest = hash?.lowercase()?.takeIf(HEX::matches) ?: return null
        return mapOf(
            "purpose" to "APPROVAL",
            "approvalRequestId" to "pension-$operation:$digest",
            "payloadSha256" to digest,
        )
    }

    /** `pension-mandate-setup|contractId|kind|IBAN|amount|CURRENCY|firstCollection` (PaymentMandateSetup). */
    fun mandateSetup(contractId: UUID, input: Map<String, Any?>): String = sha256(
        listOf(
            "pension-mandate-setup",
            contractId,
            input["kind"],
            (input["debtorIban"] as String).replace(" ", "").uppercase(),
            (input["amount"] as BigDecimal).stripTrailingZeros().toPlainString(),
            (input["currency"] as String).uppercase(),
            input["firstCollection"],
        ).joinToString("|"),
    )

    /** `pension-mandate-cancel|contractId|mandateId` (PaymentMandateLifecycle). */
    fun mandateCancel(contractId: UUID, mandateId: UUID): String =
        sha256("pension-mandate-cancel|$contractId|$mandateId")

    /** `pension-strategy-change|contractId|strategyCode|effectiveFrom|SORTED,ACKS` (StrategyChangeDocument). */
    fun strategyChange(contractId: UUID, input: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val acks = (input["acknowledgedWarnings"] as List<String>).sorted().joinToString(",")
        return sha256(
            listOf("pension-strategy-change", contractId, input["strategyCode"], input["effectiveFrom"], acks)
                .joinToString("|"),
        )
    }

    /** The annuity offer's selection hash, by partner and offer, from an AnnuityPurchase. */
    fun selectionHash(purchase: JsonNode, partnerId: String, offerId: String): String? =
        purchase.path("offers").firstOrNull { it.text("partnerId") == partnerId && it.text("offerId") == offerId }
            ?.text("selectionHash")

    /** 403 SCA_REQUIRED carrying the document-bound linking the app must raise. */
    fun required(linking: Map<String, String>, extra: Map<String, Any?> = emptyMap()): Response =
        ScaConsume.required(mapOf("scaLinking" to linking) + extra)
}

/**
 * Upstream pension refusals the app must act on, projected field by field — an upstream error body
 * is never forwarded as is ([EdgeJson.upstreamFailure]). Only closed vocabularies and ids pass.
 */
internal object PensionFailure {
    private const val BAD_REQUEST = 400
    private const val FORBIDDEN = 403
    private const val CONFLICT = 409
    private const val UNPROCESSABLE = 422
    private val TOKEN = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val QUESTION = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private const val SERVICE = "pension service"

    fun of(response: Response): Response {
        val body = EdgeJson.parse(response)?.takeIf { it.isObject }
        val code = body?.text("code")
        return when (response.status) {
            BAD_REQUEST -> EdgeJson.error(
                BAD_REQUEST,
                "refused by the pension rules",
                mapOf("code" to "PENSION_RULE_REFUSED"),
            )
            FORBIDDEN -> when (code) {
                "STRATEGY_NOT_PERMITTED" -> EdgeJson.error(
                    FORBIDDEN,
                    "this strategy cannot be chosen under the current assessment",
                    mapOf("code" to code),
                )
                "SCA_REJECTED" -> ScaConsume.rejected()
                // pension-service's other 403s carry no code (a refused or spent challenge, a debit
                // account that is not the customer's own, a frozen designation): one fixed answer.
                else -> EdgeJson.error(
                    FORBIDDEN,
                    "refused: the challenge was not accepted for this change, or the account is not your own",
                    mapOf("code" to "PENSION_CHANGE_REFUSED"),
                )
            }
            CONFLICT -> conflict(body, code)
            UNPROCESSABLE -> inconsistent(body) ?: EdgeJson.upstreamFailure(response, SERVICE)
            else -> EdgeJson.upstreamFailure(response, SERVICE)
        }
    }

    private fun conflict(body: JsonNode?, code: String?): Response = when (code) {
        "REASSESSMENT_REQUIRED" -> EdgeJson.error(
            CONFLICT,
            "the questionnaire must be answered again first",
            mapOf(
                "code" to code,
                "reason" to body?.text("reason")?.takeIf(TOKEN::matches),
                "applicationId" to body?.text("applicationId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?.toString(),
            ),
        )
        "WARNINGS_REQUIRED" -> EdgeJson.error(
            CONFLICT,
            "the warnings must be shown and acknowledged first",
            mapOf("code" to code, "warnings" to tokens(body?.path("warnings"))),
        )
        else -> EdgeJson.error(
            CONFLICT,
            "the contract cannot do this in its current state",
            mapOf("code" to "INVALID_CONTRACT_STATE"),
        )
    }

    /** ESMA consistency check: the contradicting answers to show side by side. */
    private fun inconsistent(body: JsonNode?): Response? {
        val items = body?.path("inconsistencies")?.takeIf { it.isArray } ?: return null
        return EdgeJson.error(
            UNPROCESSABLE,
            "some answers contradict each other",
            mapOf(
                "code" to "INCONSISTENT_ANSWERS",
                "inconsistencies" to items.filter { it.isObject }.mapNotNull { item ->
                    item.text("code")?.takeIf(TOKEN::matches)?.let { c ->
                        mapOf(
                            "code" to c,
                            "questions" to item.path("questions").mapNotNull { q ->
                                q.takeIf { it.isTextual }?.textValue()?.takeIf(QUESTION::matches)
                            },
                        )
                    }
                },
            ),
        )
    }

    private fun tokens(node: JsonNode?): List<String> =
        node?.mapNotNull { n -> n.takeIf { it.isTextual }?.textValue()?.takeIf(TOKEN::matches) } ?: emptyList()
}
