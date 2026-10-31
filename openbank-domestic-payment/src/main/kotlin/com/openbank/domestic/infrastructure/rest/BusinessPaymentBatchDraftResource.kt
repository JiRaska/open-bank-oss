// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.usecase.CzechDomesticIban
import com.openbank.domestic.infrastructure.persistence.entity.BusinessPaymentBatchDraftEntity
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.LockModeType
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
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
import org.eclipse.microprofile.jwt.JsonWebToken
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

private const val DRAFT_PAGE_SIZE = 20

/** Draft-only aggregate. It has no submit endpoint and cannot dispatch money. */
@Path("/api/v1/business-payment-batches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_PAYMENTS")
class BusinessPaymentBatchDraftResource(
    private val store: BusinessPaymentBatchDraftStore,
    private val mapper: ObjectMapper,
) {
    @Inject
    lateinit var identity: SecurityIdentity

    private fun trustedEdge() {
        val jwt = identity.principal as? JsonWebToken ?: throw ForbiddenException("trusted edge required")
        if (jwt.getClaim<String>("preferred_username") != "service-account-openbank-edge" ||
            jwt.getClaim<String>("azp") != "openbank-edge"
        ) {
            throw ForbiddenException("trusted edge required")
        }
    }

    data class Item(
        val itemId: UUID,
        val creditorAccountNumber: String,
        val creditorBankCode: String,
        val creditorName: String,
        val amountMinor: Long,
        val currency: String,
        val variableSymbol: String? = null,
        val messageForPayee: String? = null,
    ) {
        @JsonAnySetter
        fun rejectUnknown(name: String, ignored: Any?): Nothing =
            throw IllegalArgumentException("unsupported item field $name")
    }

    data class Create(val debtorAccountId: UUID, val items: List<Item>) {
        @JsonAnySetter
        fun rejectUnknown(name: String, ignored: Any?): Nothing =
            throw IllegalArgumentException("unsupported batch field $name")
    }

    data class Replace(val items: List<Item>) {
        @JsonAnySetter
        fun rejectUnknown(name: String, ignored: Any?): Nothing =
            throw IllegalArgumentException("unsupported replacement field $name")
    }

    // Read raw JSON so a reused key is compared to the complete canonical body before
    // validation, including fields that a new request must reject.
    @POST
    suspend fun create(
        body: String?,
        @HeaderParam("X-Customer-Party-Id") entity: UUID?,
        @HeaderParam("X-Actor-Party-Id") actor: UUID?,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response {
        trustedEdge()
        val party = requireNotNull(entity) { "company context required" }
        val human = requireNotNull(actor) { "human actor required" }
        val retryKey = requireNotNull(key?.takeIf { it.isNotBlank() && it.length <= MAX_KEY_CHARS }) {
            "Idempotency-Key required (1..128)"
        }
        val rawBody = requireNotNull(body) { "JSON body is required" }
        require(rawBody.length <= MAX_BODY_CHARS) { "batch body is too large" }
        val node = mapper.readTree(rawBody)
        require(node != null && node.isObject) { "batch body must be a JSON object" }
        val hash = sha256(mapper.writeValueAsBytes(canonical(node)))
        store.replay(party, human, retryKey, hash)?.let { return Response.ok(originalDraftView(it, mapper)).build() }
        val request = mapper.treeToValue(node, Create::class.java)
        val summary = validate(request.items)
        val (saved, replayed) = store.create(party, human, retryKey, hash, request, summary)
        return Response.status(
            if (replayed) Response.Status.OK else Response.Status.CREATED,
        ).entity(if (replayed) originalDraftView(saved, mapper) else view(saved, 0)).build()
    }

    @GET
    suspend fun list(
        @HeaderParam("X-Customer-Party-Id") entity: UUID?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): Response {
        trustedEdge()
        val party = requireNotNull(entity) { "company context required" }
        val p = page ?: 0
        val s = size ?: PAGE_SIZE
        require(p in 0..MAX_LIST_PAGE && s in 1..PAGE_SIZE) { "page or size out of range" }
        val body = mapOf("data" to store.list(party, p, s).map { view(it, null) }, "page" to p, "size" to s)
        return Response.ok(body).build()
    }

    @GET
    @Path("/{id}")
    suspend fun get(
        @HeaderParam("X-Customer-Party-Id") entity: UUID?,
        @PathParam("id") id: UUID,
        @QueryParam("page") page: Int?,
    ): Response {
        trustedEdge()
        val party = requireNotNull(entity) { "company context required" }
        val p = page ?: 0
        require(p in 0..MAX_ITEM_PAGE) { "page out of range" }
        val saved = store.get(party, id) ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(view(saved, p)).build()
    }

    @PUT
    @Path("/{id}/items")
    suspend fun replace(
        @HeaderParam("X-Customer-Party-Id") entity: UUID?,
        @PathParam("id") id: UUID,
        @HeaderParam("If-Match") ifMatch: String?,
        @HeaderParam("X-Actor-Party-Id") actor: UUID?,
        body: String?,
    ): Response {
        trustedEdge()
        val party = requireNotNull(entity) { "company context required" }
        val human = requireNotNull(actor) { "human actor required" }
        val expected = ifMatch?.trim('"')?.toLongOrNull() ?: return Response.status(HTTP_PRECONDITION_REQUIRED).build()
        val rawBody = requireNotNull(body) { "JSON body is required" }
        require(rawBody.length <= MAX_BODY_CHARS) { "batch body is too large" }
        val request = mapper.readValue(rawBody, Replace::class.java)
        val summary = validate(request.items)
        val saved = store.replace(party, id, human, expected, mapper.writeValueAsString(request.items), summary)
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(view(saved, 0)).build()
    }

    private fun view(row: BusinessPaymentBatchDraftEntity, page: Int?): Map<String, Any?> {
        val items = if (page == null) null else mapper.readTree(row.itemsJson).drop(page * PAGE_SIZE).take(PAGE_SIZE)
        return mapOf(
            "id" to row.id, "state" to "DRAFT", "debtorAccountId" to row.debtorAccountId,
            "itemCount" to row.itemCount, "totalAmountMinor" to row.amountMinor,
            "currency" to "CZK", "revision" to row.revision,
            "createdAt" to row.createdAt, "updatedAt" to row.updatedAt, "items" to items,
        )
    }

    data class Summary(val count: Int, val total: Long)

    internal fun validate(items: List<Item>): Summary {
        require(items.size in 1..MAX_ITEMS) { "between 1 and 100 items required" }
        require(items.map { it.itemId }.toSet().size == items.size) { "itemId must be unique" }
        var total = 0L
        items.forEach { item ->
            require(item.amountMinor > 0) { "amountMinor must be positive" }
            total = try {
                Math.addExact(total, item.amountMinor)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("batch total exceeds supported range")
            }
            require(item.currency == "CZK") { "only CZK is supported" }
            require(item.creditorAccountNumber.length in 1..MAX_ACCOUNT_CHARS) { "creditor account is too long" }
            require(item.creditorBankCode.matches(Regex("[0-9]{4}"))) { "invalid bank code" }
            require(CzechDomesticIban.fromAccountNumber(item.creditorAccountNumber, item.creditorBankCode) != null) {
                "invalid Czech creditor account"
            }
            require(item.creditorName.isNotBlank() && item.creditorName.length <= MAX_TEXT_CHARS) {
                "invalid creditor name"
            }
            require(item.variableSymbol == null || item.variableSymbol.matches(Regex("[0-9]{1,10}"))) {
                "invalid variable symbol"
            }
            require(item.messageForPayee == null || item.messageForPayee.length <= MAX_TEXT_CHARS) {
                "message too long"
            }
        }
        return Summary(items.size, total)
    }

    private fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().apply {
            node.fieldNames().asSequence().sorted().forEach { name ->
                set<JsonNode>(name, canonical(node.get(name)))
            }
        }
        node.isArray -> mapper.createArrayNode().apply {
            node.forEach { item -> add(canonical(item)) }
        }
        else -> node
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BODY_CHARS = 64_000
        const val MAX_KEY_CHARS = 128
        const val PAGE_SIZE = DRAFT_PAGE_SIZE
        const val MAX_LIST_PAGE = 1000
        const val MAX_ITEM_PAGE = 4
        const val MAX_ITEMS = 100
        const val MAX_ACCOUNT_CHARS = 17
        const val MAX_TEXT_CHARS = 140
        const val HTTP_PRECONDITION_REQUIRED = 428
    }
}

@ApplicationScoped
class BusinessPaymentBatchDraftStore(private val mapper: ObjectMapper) :
    PanacheRepository<BusinessPaymentBatchDraftEntity> {
    suspend fun replay(entity: UUID, actor: UUID, key: String, hash: String): BusinessPaymentBatchDraftEntity? {
        val existing = Panache.withSession {
            find("entityPartyId = ?1 and idempotencyKey = ?2", entity, key).firstResult()
        }.awaitSuspending()
        if (existing != null && (existing.requestHash != hash || existing.actorPartyId != actor)) {
            throw BatchDraftConflict()
        }
        return existing
    }

    // Constraint failures arrive wrapped by Hibernate Reactive; inspect only the named unique key before replaying.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun create(
        entity: UUID,
        actor: UUID,
        key: String,
        hash: String,
        request: BusinessPaymentBatchDraftResource.Create,
        summary: BusinessPaymentBatchDraftResource.Summary,
    ): Pair<BusinessPaymentBatchDraftEntity, Boolean> {
        val existing = Panache.withSession {
            find("entityPartyId = ?1 and idempotencyKey = ?2", entity, key).firstResult()
        }.awaitSuspending()
        if (existing != null) {
            if (existing.requestHash != hash || existing.actorPartyId != actor) throw BatchDraftConflict()
            return existing to true
        }
        val now = Instant.now()
        val row = BusinessPaymentBatchDraftEntity().apply {
            id = Ids.newId()
            entityPartyId = entity
            actorPartyId = actor
            updatedByPartyId = actor
            idempotencyKey = key
            requestHash = hash
            debtorAccountId = request.debtorAccountId
            itemsJson = mapper.writeValueAsString(request.items)
            itemCount = summary.count
            amountMinor = summary.total
            createdAt = now
            updatedAt = now
            originalResponseJson = originalDraftResponse(this, mapper)
        }
        return try {
            Panache.withTransaction { persistAndFlush(row).replaceWith(row) }.awaitSuspending() to false
        } catch (e: RuntimeException) {
            if (!e.isBatchKeyViolation()) throw e
            val winner = Panache.withSession {
                find("entityPartyId = ?1 and idempotencyKey = ?2", entity, key).firstResult()
            }.awaitSuspending()
                ?: throw e
            if (winner.requestHash != hash || winner.actorPartyId != actor) throw BatchDraftConflict()
            winner to true
        }
    }

    private fun Throwable.isBatchKeyViolation(): Boolean =
        generateSequence(this) { cause -> cause.cause.takeIf { it !== cause } }.any { cause ->
            val constraint = (cause as? org.hibernate.exception.ConstraintViolationException)?.constraintName
            constraint == "uq_business_batch_idempotency" ||
                cause.message?.contains("uq_business_batch_idempotency") == true
        }

    suspend fun get(entity: UUID, id: UUID): BusinessPaymentBatchDraftEntity? = Panache.withSession {
        find("entityPartyId = ?1 and id = ?2", entity, id).firstResult()
    }.awaitSuspending()

    suspend fun list(entity: UUID, page: Int, size: Int): List<BusinessPaymentBatchDraftEntity> = Panache.withSession {
        find("entityPartyId = ?1 order by createdAt desc, id desc", entity)
            .page(page, size).list()
    }.awaitSuspending()

    suspend fun replace(
        entity: UUID,
        id: UUID,
        actor: UUID,
        expected: Long,
        itemsJson: String,
        summary: BusinessPaymentBatchDraftResource.Summary,
    ): BusinessPaymentBatchDraftEntity? = Panache.withTransaction {
        find("entityPartyId = ?1 and id = ?2", entity, id)
            .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().onItem().transform { row ->
                if (row == null) return@transform null
                if (row.revision != expected) throw BatchDraftConflict()
                row.itemsJson = itemsJson
                row.itemCount = summary.count
                row.amountMinor = summary.total
                row.updatedByPartyId = actor
                row.updatedAt = Instant.now()
                row
            }
    }.awaitSuspending()
}

internal fun originalDraftView(row: BusinessPaymentBatchDraftEntity, mapper: ObjectMapper): JsonNode =
    mapper.readTree(row.originalResponseJson)

private fun originalDraftResponse(row: BusinessPaymentBatchDraftEntity, mapper: ObjectMapper): String =
    mapper.writeValueAsString(
        mapOf(
            "id" to row.id, "state" to "DRAFT", "debtorAccountId" to row.debtorAccountId,
            "itemCount" to row.itemCount, "totalAmountMinor" to row.amountMinor,
            "currency" to "CZK", "revision" to row.revision,
            "createdAt" to row.createdAt, "updatedAt" to row.updatedAt,
            "items" to mapper.readTree(row.itemsJson).take(DRAFT_PAGE_SIZE),
        ),
    )

class BatchDraftConflict : RuntimeException("Draft revision or idempotency key conflicts")

@jakarta.ws.rs.ext.Provider
class BatchDraftConflictMapper : jakarta.ws.rs.ext.ExceptionMapper<BatchDraftConflict> {
    override fun toResponse(exception: BatchDraftConflict): Response = Response.status(Response.Status.CONFLICT)
        .entity(mapOf("code" to "BATCH_DRAFT_CONFLICT", "error" to exception.message))
        .build()
}
