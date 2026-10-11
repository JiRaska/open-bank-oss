// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.util.UUID

/**
 * Transfer-out (ZDPS: moving a contract to another provider) and the shared tax-cap declaration,
 * over pension-service API 2.0.0. Same rules as [CustomerPensionResource]: the participant is the
 * token's own party, a contract or transfer of another party is the same 404 as an unknown one,
 * every change carries the caller's `Idempotency-Key`, and every change is SCA-bound.
 *
 *  - **Transfer-out request and consent are document-bound**: pension-service spends the challenge
 *    over `pension-transfer-out:<contractId>:<providerId>:<contractNumber>` (consent appends
 *    `:<transferId>`), payload = SHA-256 of that id. Without a challenge the edge answers 403
 *    `SCA_REQUIRED` with exactly that linking and nothing goes upstream.
 *  - **The tax-cap declaration is edge-bound**: pension-service does not spend a challenge for it,
 *    so the edge consumes one over `pension.declare-cap:<contractId>` bound to the exact body.
 */
@Path("/customer/v1/pension")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions") // one method per customer route plus their validators, as the sibling resources
class CustomerPensionTransferResource(
    private val upstream: UpstreamClient,
    private val parties: CustomerPartyResolver,
) {
    @ConfigProperty(name = "openbank.edge.pension-service-url")
    lateinit var pensionServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sca-service-url")
    lateinit var scaServiceUrl: String

    /** Ask for the contract to be moved to another provider; answers 202 with the transfer. */
    @POST
    @Path("/contracts/{contractId}/transfers-out")
    @Authorize(action = "customer.pension.transfer.request", resource = "")
    @Blocking
    fun requestTransferOut(
        @PathParam("contractId") id: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val input = receiving(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            val linking = linking("pension-transfer-out:$contractId:${input.providerId}:${input.contractNumber}")
            val challenge = ScaConsume.challengeId(sca) ?: return@owned PensionDocuments.required(linking)
            transfer(
                upstream.post(
                    "${api()}/transfers/out",
                    party,
                    json(
                        mapOf(
                            "contractId" to contractId.toString(),
                            "receivingProviderId" to input.providerId,
                            "receivingProviderName" to input.providerName,
                            "receivingContractNumber" to input.contractNumber,
                            "scaChallengeId" to challenge.toString(),
                        ),
                    ),
                    idempotencyKey,
                ),
            )
        }
    }

    /** One of the caller's transfers, e.g. a receiving provider's request awaiting consent. */
    @GET
    @Path("/transfers/{transferId}")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun transferStatus(@PathParam("transferId") id: String?): Response {
        val transferId = uuid(id) ?: return transferNotFound()
        return transfer(upstream.get("${api()}/transfers/$transferId", party()))
    }

    /** SCA consent to a transfer-out a receiving provider requested for the caller's contract. */
    @POST
    @Path("/transfers/{transferId}/consent")
    @Authorize(action = "customer.pension.transfer.request", resource = "")
    @Blocking
    fun consentTransferOut(
        @PathParam("transferId") id: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val transferId = uuid(id) ?: return transferNotFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        val party = party()
        val read = upstream.get("${api()}/transfers/$transferId", party)
        if (read.status != OK) return transfer(read)
        val current = EdgeJson.parse(read)?.takeIf { it.isObject } ?: return badUpstream()
        val ref = listOf(
            "pension-transfer-out",
            current.text("contractId"),
            current.text("counterpartyProviderId"),
            current.text("counterpartyContractNumber"),
            transferId.toString(),
        )
        if (ref.any { it.isNullOrBlank() }) return badUpstream()
        val challenge = ScaConsume.challengeId(sca)
            ?: return PensionDocuments.required(linking(ref.joinToString(":")))
        return transfer(
            upstream.post(
                "${api()}/transfers/$transferId/consent",
                party,
                json(mapOf("scaChallengeId" to challenge.toString())),
                idempotencyKey,
            ),
        )
    }

    /**
     * The caller's transfers in either direction — by default only those still pending, so a
     * receiving provider's request that waits for their consent shows up here.
     */
    @GET
    @Path("/transfers")
    @Authorize(action = "customer.pension.contract.read", resource = "")
    @Blocking
    fun transfers(@QueryParam("direction") direction: String?, @QueryParam("pending") pending: String?): Response {
        val dir = direction?.trim()?.uppercase()
        if (dir != null && dir !in DIRECTIONS) return invalid("direction must be IN or OUT")
        val pendingOnly = when (pending?.trim()?.lowercase()) {
            null, "", "true" -> true
            "false" -> false
            else -> return invalid("pending must be true or false")
        }
        val query = buildString {
            append("?pending=").append(pendingOnly)
            if (dir != null) append("&direction=").append(dir)
        }
        val response = upstream.get("${api()}/transfers$query", party())
        if (response.status != OK) return PensionFailure.of(response)
        val list = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream()
        return EdgeJson.ok(list.map { project(it) }, OK)
    }

    /**
     * Withdraw a transfer-out — the caller's own request, or their consent to a provider's — before
     * it is valued. Document-bound like consent: pension-service spends the challenge over
     * `pension-transfer-withdraw:<transferId>`; without one nothing goes upstream.
     */
    @POST
    @Path("/transfers/{transferId}/withdrawal")
    @Authorize(action = "customer.pension.transfer.request", resource = "")
    @Blocking
    fun withdrawTransferOut(
        @PathParam("transferId") id: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val transferId = uuid(id) ?: return transferNotFound()
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        val challenge = ScaConsume.challengeId(sca)
            ?: return PensionDocuments.required(linking("pension-transfer-withdraw:$transferId"))
        return transfer(
            upstream.post(
                "${api()}/transfers/$transferId/withdrawal",
                party(),
                json(mapOf("scaChallengeId" to challenge.toString())),
                idempotencyKey,
            ),
        )
    }

    /**
     * Declare what the participant used of the shared tax cap at other providers in [year]
     * (`{"usage":{"<capGroup>":"<amount>"}}`), so the deductible amount is not overstated.
     */
    @PUT
    @Path("/contracts/{contractId}/tax-years/{year}/external-cap-usage")
    @Authorize(action = "customer.pension.contract.manage", resource = "")
    @Blocking
    fun declareExternalCapUsage(
        @PathParam("contractId") id: String?,
        @PathParam("year") year: String?,
        body: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        val taxYear = PensionInput.taxYear(year) ?: return invalid("year must be a past or current tax year")
        val usage = usage(EdgeJson.parseObject(body)).getOrElse { return invalid(it) }
        val idempotencyKey = requiredKey(key) ?: return keyRequired()
        return owned(id) { party, contractId ->
            val payload = mapOf("usage" to usage)
            val linking = PensionScaLinking.of("declare-cap", contractId, mapOf("year" to taxYear) + payload)
            val challenge = ScaConsume.challengeId(sca)
                ?: return@owned ScaConsume.required(mapOf("scaLinking" to linking + ("purpose" to "APPROVAL")))
            val human = UUID.fromString(party)
            val consumed = ScaConsume.consume(upstream, scaServiceUrl, challenge, party, human, linking)
            if (consumed.status != OK) return@owned ScaConsume.rejected()
            val response = upstream.put(
                "${api()}/funding/contracts/$contractId/tax-years/$taxYear/external-cap-usage",
                party,
                json(payload),
                idempotencyKey,
                emptyMap(),
            )
            if (response.status == OK) passThrough(response) else PensionFailure.of(response)
        }
    }

    private data class Receiving(val providerId: String, val providerName: String, val contractNumber: String)

    private fun receiving(body: JsonNode?): Result<Receiving> = runCatching {
        requireNotNull(body) { "a JSON object body is required" }
        fun field(name: String, pattern: Regex): String {
            val value = body.text(name)?.trim()
            require(value != null && pattern.matches(value)) { "$name is required and must be well-formed" }
            return value
        }
        Receiving(
            providerId = field("receivingProviderId", TOKEN),
            providerName = field("receivingProviderName", NAME),
            contractNumber = field("receivingContractNumber", TOKEN),
        )
    }

    private fun usage(body: JsonNode?): Result<Map<String, String>> = runCatching {
        val raw = body?.path("usage")?.takeIf { it.isObject }
        requireNotNull(raw) { "usage must be an object" }
        require(raw.size() in 1..MAX_GROUPS) { "usage must name 1 to $MAX_GROUPS cap groups" }
        raw.properties().associate { (group, amount) ->
            require(CAP_GROUP.matches(group)) { "cap group codes must be upper-case tokens" }
            val value = when {
                amount.isTextual -> runCatching { BigDecimal(amount.textValue()) }.getOrNull()
                amount.isNumber -> amount.decimalValue()
                else -> null
            }
            require(value != null && value.signum() >= 0 && value.scale() <= 2) {
                "usage['$group'] must be a non-negative amount with at most 2 decimals"
            }
            group to value.toPlainString()
        }.toSortedMap()
    }

    /** Rule 3 of [CustomerPensionResource]: only a contract the caller holds, by canonical id. */
    private inline fun owned(id: String?, action: (String, UUID) -> Response): Response {
        val contractId = uuid(id) ?: return notFound()
        val party = party()
        val response = upstream.get("${api()}/contracts/$contractId", party)
        if (response.status == NOT_FOUND) return notFound()
        if (response.status != OK) return EdgeJson.upstreamFailure(response, SERVICE)
        val contract = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return badUpstream()
        if (contract.text("participantPartyId") != party) return notFound()
        return action(party, contractId)
    }

    /** pension-service scopes transfers by party: another party's transfer is its 404, and ours. */
    private fun transfer(response: Response): Response = when (response.status) {
        OK, ACCEPTED -> EdgeJson.parse(response)?.takeIf { it.isObject }
            ?.let { EdgeJson.ok(project(it), response.status) } ?: badUpstream()
        NOT_FOUND -> transferNotFound()
        else -> PensionFailure.of(response)
    }

    /** The fields the app shows; the party id and internal failure detail never leave the edge. */
    private fun project(node: JsonNode): Map<String, Any?> = PROJECTED.associateWith { field ->
        node.get(field)?.takeUnless { it.isNull }?.let { if (it.isNumber) it.decimalValue() else it.asText() }
    }

    private fun party(): String = parties.resolve(null).toString()

    private fun api() = "${pensionServiceUrl.trimEnd('/')}/api/v2/pension"

    private fun json(value: Any) = EdgeJson.mapper.writeValueAsString(value)

    private fun passThrough(response: Response): Response =
        EdgeJson.parse(response)?.let { EdgeJson.ok(it, response.status) } ?: badUpstream()

    private companion object {
        const val SERVICE = "pension service"
        const val MAX_KEY = 256
        const val MAX_GROUPS = 8
        const val OK = 200
        const val ACCEPTED = 202
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val BAD_GATEWAY = 502
        val DIRECTIONS = setOf("IN", "OUT")
        val TOKEN = Regex("^[A-Za-z0-9_./-]{1,64}$")
        val CAP_GROUP = Regex("^[A-Z0-9_]{1,32}$")
        val NAME = Regex("^[\\p{L}\\p{N} .,&'()-]{1,128}$")
        val PROJECTED = listOf(
            "transferId", "direction", "origin", "contractId", "counterpartyProviderId",
            "counterpartyProviderName", "counterpartyContractNumber", "status", "deadline",
            "grossAmount", "fee", "netAmount", "currency", "createdAt", "updatedAt",
        )

        fun linking(ref: String): Map<String, String> =
            mapOf("purpose" to "APPROVAL", "approvalRequestId" to ref, "payloadSha256" to PensionDocuments.sha256(ref))

        fun uuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        fun requiredKey(raw: String?): String? = raw?.trim()?.takeIf { it.length in 1..MAX_KEY }

        fun keyRequired() = invalid("Idempotency-Key header is required")

        fun invalid(error: Throwable) = invalid(error.message ?: "invalid request")

        fun invalid(message: String) = EdgeJson.error(BAD_REQUEST, message)

        fun notFound() = EdgeJson.error(NOT_FOUND, "pension contract not found")

        fun transferNotFound() = EdgeJson.error(NOT_FOUND, "pension transfer not found")

        fun badUpstream() = EdgeJson.error(BAD_GATEWAY, "unexpected $SERVICE response")
    }
}
