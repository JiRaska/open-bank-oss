// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.web

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response

/**
 * The shared BODY of a service's checker-facing maker-checker endpoint (ADR-0155, ADR-0227 D2).
 *
 * Deliberately a plain delegate, not an abstract JAX-RS resource and not a CDI bean:
 * - The per-service parts are ANNOTATIONS (`@Path`, `@RolesAllowed`, `@Authorize(action = ...)`,
 *   `@Tag`), and annotation values cannot be parameterised by a base class. An inherited
 *   `@Authorize` would carry one action string for every service; overriding the method to change
 *   it would re-declare everything anyway. So each service keeps a thin resource that owns its
 *   annotations and nullable parameters, and delegates here.
 * - It is not a bean, so adding it to libs-runtime changes no service's CDI graph. That matters:
 *   `AuthorizeInterceptor` keys four-eyes behaviour off `ApprovalStore` bean PRESENCE, so a
 *   libs-level producer would silently switch four-eyes on in every consumer.
 *
 * Security properties kept here, once:
 * - The checker id comes from [SecurityIdentity] (`principal.name`, the same form
 *   `AuthorizeInterceptor` records as the maker id), never from the request body.
 * - Self-approval is refused by [ApprovalStore.decide] (`SelfApprovalNotAllowedException`); this
 *   class does not catch it, so the service's existing exception mapping is unchanged.
 */
class ApprovalEndpointSupport(private val approvalStore: ApprovalStore) {

    /** Pending queue, oldest first; `limit` is clamped to `1..MAX_PENDING_LIMIT` (Redis scan amplification). */
    suspend fun listPending(limit: Int): Response {
        val pending = approvalStore.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT))
        return Response.ok(pending.map { it.toApprovalResponse() }).build()
    }

    /**
     * Records the decision made by the identity [identityProvider] resolves. A null body (JSON
     * `null`) is a 400 via libs-runtime's `IllegalArgumentException` mapping, not a 500; an
     * unknown or already-decided id is a 404.
     *
     * Takes a `() -> SecurityIdentity` SUPPLIER, not a resolved [SecurityIdentity], so nothing
     * touches identity until after the null-body check below — Kotlin evaluates a function's
     * argument expressions before the function body runs, so an eager `identity: SecurityIdentity`
     * parameter (or `checkerId(identity)` computed at the call site) reads the caller's
     * `SecurityIdentity` before `decide` ever gets to `requireNotNull`, regardless of what the
     * body does with it. `ApprovalNullBodyTest` (originally #3029) pins the intended order: "a
     * null body is rejected before any identity is resolved," so a request this malformed never
     * reaches authorization-adjacent code. A caller passes `{ identity }`, a closure over its own
     * `lateinit var identity: SecurityIdentity` — CREATING that closure does not read the
     * property, only calling it does, and [identityProvider] is called here strictly after
     * [requireNotNull] returns.
     *
     * Found the hard way twice. First (#11033): the pilot migration (#10917) passed the
     * pre-resolved `checkerId(identity)` string and broke this ordering, surfacing as
     * `UninitializedPropertyAccessException` instead of the expected 400. The follow-up fix
     * (#11047) changed the parameter to a bare `identity: SecurityIdentity` — which looked right
     * (`checkerId` moved inside the function, after `requireNotNull`) but did not fix anything:
     * reading a `lateinit` property to pass it as that very argument throws exactly as eagerly as
     * computing `checkerId(identity)` did, since both are argument expressions evaluated before
     * `decide`'s body runs. Reproduced by consent-service's migration (batch2, #11061) hitting the
     * identical `UninitializedPropertyAccessException` `ApprovalNullBodyTest` was written to
     * prevent — the supplier is what actually defers the read.
     */
    suspend fun decide(
        id: String,
        request: DecideApprovalRequest?,
        identityProvider: () -> SecurityIdentity,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val decided = approvalStore.decide(id, checkerId(identityProvider()), request.approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return Response.ok(decided.toApprovalResponse()).build()
    }

    companion object {
        const val MAX_PENDING_LIMIT = 200

        /**
         * `principal.name` (preferred_username), NOT the subject UUID — it MUST match how
         * `AuthorizeInterceptor` resolves the maker's id, or the self-approval guard in
         * [ApprovalStore.decide] would compare two spellings of the same person.
         */
        fun checkerId(identity: SecurityIdentity): String = identity.principal?.name ?: "anonymous"
    }
}

data class DecideApprovalRequest(val approve: Boolean)

/** Wire shape of a pending/decided approval, shared by every migrated service's `openapi.yaml`. */
data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val makerId: String?,
    val createdAt: String?,
    val decidedBy: String?,
)

fun PendingApproval.toApprovalResponse() = ApprovalResponse(
    id = id,
    action = action,
    resourceId = resourceId,
    status = status.name,
    makerId = makerId,
    createdAt = createdAt.toString(),
    decidedBy = decidedBy,
)
