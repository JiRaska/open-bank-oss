// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.application.port.out.BusinessSigningPort
import com.openbank.customeredge.application.port.out.SigningReply
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/**
 * Business signing for the customer app (#10281): the company's signing policy, its trusted
 * payees, and the approval requests waiting for signatures. delegation-service owns all of it;
 * this resource binds every call to the right two parties and nothing else:
 *
 *  - the **entity** from `X-Acting-For`, only after [ActingForResolver] confirmed an ACTIVE
 *    mandate (fail-closed 403; the header is required and must name a company);
 *  - the **signer** — always the token's HUMAN, never the entity and never a body field.
 *
 * Signing spends the human's SCA challenge on exactly this approval request and its payload hash
 * (sca-service dynamic linking). On the last signature of a PAYMENT the edge releases it to the
 * rail through [BusinessPaymentApprovals.release].
 *
 * The handlers live in this bean; the JAX-RS classes (`Business*Resource`, `MyApprovalsResource`) are thin
 * delegators, one per path prefix, because JAX-RS picks the resource CLASS by its longest matching
 * `@Path` first: under `/customer/v1` these routes were shadowed by `CustomerBusinessResource`'s
 * `/customer/v1/business` and answered 404.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class BusinessSigningHandlers(
    private val signing: BusinessSigningPort,
    private val approvals: BusinessPaymentApprovals,
    private val partyMergeResolver: PartyMergeResolver,
    private val actingForResolver: ActingForResolver,
    private val upstream: UpstreamClient,
    private val audit: EdgeAuditPublisher,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    // --- signing policy -------------------------------------------------------------------

    fun signingPolicy(actingFor: String?, acceptLanguage: String?): Response {
        val (_, entity) = parties(actingFor)
        val reply = signing.policy(entity)
        val node = objectOrNull(reply) ?: return passThrough(reply)
        val lang = SigningPolicySummary.lang(acceptLanguage)
        node.set<JsonNode>(
            "summary",
            objectMapper.valueToTree(SigningPolicySummary.policyLines(node, lang)),
        )
        if (!node.has("isDerivedFromRegister")) {
            // A policy nobody has changed yet is the one derived from the register mandate.
            val neverChanged = node.path("updatedByApprovalId").let { it.isNull || it.isMissingNode }
            node.put("isDerivedFromRegister", node.path("derivedFromRegister").asBoolean(neverChanged))
        }
        return ok(node)
    }

    /** A policy change is itself a signed item: this creates a POLICY_CHANGE approval request (202). */
    fun changeSigningPolicy(actingFor: String?, body: String): Response {
        val (human, entity) = parties(actingFor)
        val node = parseObject(body) ?: return badRequest("body must be a JSON object")
        node.put("initiatorPartyId", human.toString())
        return accepted(signing.changePolicy(entity, objectMapper.writeValueAsString(node)))
    }

    // --- trusted payees -------------------------------------------------------------------

    fun trustedPayees(actingFor: String?): Response {
        val (_, entity) = parties(actingFor)
        return dataList(signing.trustedPayees(entity))
    }

    /** Creates a PAYEE_ADD approval request (202); the payee is trusted only once the full round completes. */
    fun addTrustedPayee(actingFor: String?, body: String): Response {
        val (human, entity) = parties(actingFor)
        val node = parseObject(body) ?: return badRequest("body must be a JSON object")
        val iban = node.path("iban").asText("").replace(" ", "").uppercase()
        val name = node.path("name").asText("").trim()
        if (!IBAN.matches(iban)) return badRequest("iban is not a valid IBAN")
        if (name.isEmpty() || name.length > MAX_NAME) return badRequest("name must be 1..$MAX_NAME characters")
        val out = objectMapper.createObjectNode().apply {
            put("iban", iban)
            put("name", name)
            node.path("bic").takeIf { it.isTextual }?.let { put("bic", it.asText()) }
            put("initiatorPartyId", human.toString())
        }
        return accepted(signing.addTrustedPayee(entity, objectMapper.writeValueAsString(out)))
    }

    /** Creates a PAYEE_REMOVE approval request (202). */
    fun removeTrustedPayee(actingFor: String?, id: String): Response {
        val (human, entity) = parties(actingFor)
        val payeeId = runCatching { UUID.fromString(id) }.getOrNull() ?: return badRequest("id is not a payee id")
        return accepted(signing.removeTrustedPayee(entity, payeeId.toString(), human))
    }

    // --- approvals -----------------------------------------------------------------------

    fun listApprovals(actingFor: String?, status: String?, mine: String?): Response {
        val (human, entity) = parties(actingFor)
        require(status == null || status in STATUS_FILTERS) { "status must be PENDING or DONE" }
        require(mine == null || mine in MINE_FILTERS) { "mine must be toSign or initiated" }
        val reply = signing.approvals(
            entity,
            status = if (status == "PENDING") "PENDING" else null,
            signer = if (mine == "toSign") human else null,
        )
        val list = arrayOrNull(reply) ?: return passThrough(reply)
        val filtered = list.filter { a ->
            (status != "DONE" || a.path("status").asText() != "PENDING") &&
                (mine != "initiated" || a.path("initiatorPartyId").asText() == human.toString())
        }
        return ok(objectMapper.createObjectNode().set("data", objectMapper.valueToTree(filtered)))
    }

    fun approval(actingFor: String?, acceptLanguage: String?, id: String): Response {
        val (human, entity) = parties(actingFor)
        val approvalId = uuid(id) ?: return notFound()
        val reply = signing.approval(entity, approvalId)
        val node = objectOrNull(reply) ?: return passThrough(reply)
        return ok(detail(node, human, SigningPolicySummary.lang(acceptLanguage)))
    }

    /**
     * Sign with the human's SCA challenge (`X-SCA-Challenge-Id`), which must be dynamically linked
     * to this approval id and its payload hash. See [refuseSigner] for who may sign in which state.
     */
    fun sign(actingFor: String?, scaChallengeId: String?, acceptLanguage: String?, id: String): Response {
        val (human, entity) = parties(actingFor)
        val approvalId = uuid(id) ?: return notFound()
        val challenge = scaChallengeId?.let { uuid(it.trim()) }
            ?: return error(Response.Status.FORBIDDEN, "SCA_REQUIRED", "Strong customer authentication required")
        val current = signing.approval(entity, approvalId)
        val approval = objectOrNull(current) ?: return passThrough(current)
        refuseSigner(approval, human)?.let { return it }

        // Single consumer (#10315): delegation-service owns the approval and spends this challenge at
        // sca-service with approvalRequestId + payloadSha256 (+ amount/currency/creditor). Consuming it
        // here too would make every co-signature fail as already-consumed.
        val signed = signing.sign(entity, approvalId, human, challenge)
        audit.emit(
            eventType = "BUSINESS_APPROVAL_SIGNED",
            partyId = human.toString(),
            operation = "business.approvals.sign",
            result = if (signed.ok) "SUCCESS" else "FAILURE",
            resourceId = approvalId.toString(),
            details = mapOf("entityPartyId" to entity.toString(), "scaChallengeId" to challenge.toString()),
        )
        val after = objectOrNull(signed) ?: return passThrough(signed)
        val out = detail(after, human, SigningPolicySummary.lang(acceptLanguage))
        if (after.path("status").asText() == "APPROVED" && after.path("kind").asText() == "PAYMENT") {
            out.set<JsonNode>("release", objectMapper.valueToTree(approvals.release(entity, approvalId)))
        }
        return ok(out)
    }

    fun reject(actingFor: String?, id: String, body: String?): Response {
        val (human, entity) = parties(actingFor)
        val approvalId = uuid(id) ?: return notFound()
        val reason = body?.takeIf { it.isNotBlank() }?.let {
            parseObject(it)
                ?: return badRequest("body must be a JSON object")
        }
            ?.path("reason")?.takeIf { it.isTextual }?.asText()?.trim()?.take(MAX_REASON)
        val reply = signing.reject(entity, approvalId, human, reason)
        audit.emit(
            eventType = "BUSINESS_APPROVAL_REJECTED",
            partyId = human.toString(),
            operation = "business.approvals.reject",
            result = if (reply.ok) "SUCCESS" else "FAILURE",
            resourceId = approvalId.toString(),
            details = mapOf("entityPartyId" to entity.toString()),
        )
        return passThrough(reply)
    }

    /**
     * Everything waiting for the human across every entity they can sign for — the personal-profile
     * row and the switcher badges. No `X-Acting-For`: this is the human's own view, and the header is
     * ignored rather than switching it.
     */
    fun myPending(): Response = dataList(signing.pendingFor(human()))

    // --- helpers -------------------------------------------------------------------------

    /**
     * Who may sign now (contract pin 1). A request AWAITING_INITIATOR takes exactly one signature:
     * its initiator's own — a policy change or payee change never collects co-signatures, and
     * nobody is notified, before the person who proposed it has authenticated it. A PENDING request
     * takes anyone eligible who has not signed; the initiator's first signature is already counted,
     * so they never sign again.
     */
    private fun refuseSigner(approval: JsonNode, human: UUID): Response? {
        val me = human.toString()
        val isInitiator = approval.path("initiatorPartyId").asText() == me
        val signed = me in approval.path("signatures").map { it.path("partyId").asText() }
        return when (approval.path("status").asText()) {
            AWAITING_INITIATOR -> if (isInitiator && !signed) {
                null
            } else {
                error(
                    Response.Status.CONFLICT,
                    AWAITING_INITIATOR,
                    "The initiator has not authenticated this request yet",
                )
            }
            PENDING -> when {
                isInitiator -> error(
                    Response.Status.CONFLICT,
                    "INITIATOR_CANNOT_COSIGN",
                    "The initiator's signature is already counted",
                )
                signed -> error(Response.Status.CONFLICT, "ALREADY_SIGNED", "You have already signed this request")
                else -> null
            }
            else -> error(
                Response.Status.CONFLICT,
                "APPROVAL_NOT_PENDING",
                "This request is no longer waiting for signatures",
            )
        }
    }

    private fun detail(node: JsonNode, human: UUID, lang: SigningPolicySummary.Lang): ObjectNode {
        val out = (node.deepCopy<JsonNode>() as ObjectNode)
        val me = human.toString()
        val signedBy = node.path("signatures").associate { it.path("partyId").asText() to it.path("at").asText(null) }
        val eligible = node.path("eligibleSignerIds").map { it.asText() }
        val signers = (eligible + signedBy.keys).distinct().map { id ->
            mapOf(
                "partyId" to id,
                "name" to legalName(id),
                "status" to if (id in signedBy) "SIGNED" else "WAITING",
                "signedAt" to signedBy[id],
                "isYou" to (id == me),
                "isInitiator" to (id == node.path("initiatorPartyId").asText()),
            )
        }
        val isInitiator = node.path("initiatorPartyId").asText() == me
        val canSign = when (node.path("status").asText()) {
            AWAITING_INITIATOR -> isInitiator && me !in signedBy
            PENDING -> !isInitiator && me in eligible && me !in signedBy
            else -> false
        }
        out.set<JsonNode>("signers", objectMapper.valueToTree(signers))
        out.put("canSign", canSign)
        out.put("collected", signedBy.size)
        out.put("summaryText", SigningPolicySummary.approvalSummary(node, lang))
        return out
    }

    private fun legalName(partyId: String): String? {
        val id = uuid(partyId) ?: return null
        val r = upstream.get("$partyServiceUrl/api/v1/parties/$id", id.toString())
        if (r.status != OK) return null
        return runCatching { objectMapper.readTree((r.entity as? String).orEmpty()) }.getOrNull()
            ?.path("legalName")?.takeIf { it.isTextual }?.asText()
    }

    /** (human, entity): the header is required, mandate-checked, and must name someone other than the human. */
    private fun parties(actingFor: String?): Pair<UUID, UUID> {
        val human = human()
        if (actingFor.isNullOrBlank()) throw ForbiddenException("X-Acting-For is required for business signing")
        val entity = actingForResolver.resolve(human, actingFor)
        if (entity == human) throw ForbiddenException("X-Acting-For must name a company, not the customer")
        return human to entity
    }

    private fun human(): UUID {
        val raw = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw ForbiddenException("party_id claim is not a valid party UUID")
        return partyMergeResolver.resolve(claimed)
    }

    /**
     * 202 for a created non-payment request (contract pin 1): it starts AWAITING_INITIATOR, with
     * nothing collected — the initiator must sign it through `/sign` with their own SCA before
     * anyone else is asked.
     */
    private fun accepted(reply: SigningReply): Response {
        val node = objectOrNull(reply) ?: return passThrough(reply)
        val out = linkedMapOf(
            "approvalId" to (node.path("approvalId").textOrNull() ?: node.path("id").textOrNull()),
            "status" to (node.path("status").textOrNull() ?: AWAITING_INITIATOR),
            "payloadSha256" to node.path("payloadSha256").textOrNull(),
            "required" to node.path("required").takeIf { it.isInt }?.asInt(),
            "collected" to (node.path("signatures").takeIf { it.isArray }?.size() ?: 0),
            "expiresAt" to node.path("expiresAt").textOrNull(),
        )
        return Response.status(Response.Status.ACCEPTED).entity(objectMapper.writeValueAsString(out))
            .type(MediaType.APPLICATION_JSON).build()
    }

    private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()

    /** Contract pin 3: every list is `{"data": [...]}`; the owner may answer either shape. */
    private fun dataList(reply: SigningReply): Response {
        val items = arrayOrNull(reply) ?: return passThrough(reply)
        return ok(objectMapper.createObjectNode().set("data", objectMapper.valueToTree(items)))
    }

    private fun passThrough(reply: SigningReply): Response =
        Response.status(reply.status).entity(reply.body).type(MediaType.APPLICATION_JSON).build()

    private fun objectOrNull(reply: SigningReply): ObjectNode? = if (!reply.ok) {
        null
    } else {
        runCatching { objectMapper.readTree(reply.body) }.getOrNull() as? ObjectNode
    }

    private fun arrayOrNull(reply: SigningReply): List<JsonNode>? = if (!reply.ok) {
        null
    } else {
        runCatching { objectMapper.readTree(reply.body) }.getOrNull()
            ?.let { if (it.isObject) it.path("data") else it }
            ?.takeIf { it.isArray }?.toList()
    }

    private fun parseObject(body: String): ObjectNode? = runCatching {
        objectMapper.readTree(body)
    }.getOrNull() as? ObjectNode

    private fun uuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

    private fun ok(node: JsonNode): Response =
        Response.ok(objectMapper.writeValueAsString(node)).type(MediaType.APPLICATION_JSON).build()

    private fun badRequest(message: String) = error(Response.Status.BAD_REQUEST, "BAD_REQUEST", message)

    private fun notFound() = error(Response.Status.NOT_FOUND, "NOT_FOUND", "Approval request not found")

    private fun error(status: Response.Status, code: String, message: String): Response = Response.status(status)
        .entity(objectMapper.writeValueAsString(mapOf("error" to message, "code" to code)))
        .type(MediaType.APPLICATION_JSON)
        .build()

    companion object {
        const val ACTING_FOR = "X-Acting-For"
        const val AWAITING_INITIATOR = "AWAITING_INITIATOR"
        const val PENDING = "PENDING"
        const val OK = 200
        const val MAX_NAME = 140
        const val MAX_REASON = 500
        val IBAN = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
        val STATUS_FILTERS = setOf("PENDING", "DONE")
        val MINE_FILTERS = setOf("toSign", "initiated")
    }
}
