// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.web

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.toApprovalItem
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.ConfigProvider

/**
 * The shared BODY of a service's checker-facing maker-checker endpoint (ADR-0155, ADR-0227 D1/D2).
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
 *
 * ADR-0227 D1: `listPending` projects each store row into the canonical
 * [com.openbank.libs.approval.ApprovalItem] and serves it through the additively-extended
 * [ApprovalResponse] (new fields are all optional, so existing consumers and the 19 services'
 * openapi MINOR bumps stay backward-compatible). The store carries no domain or risk class, so
 * `domain` is derived from `quarkus.application.name` and `riskClass` is UNKNOWN here; the
 * admin-UI BFF re-derives the authoritative risk class from rules.yaml action-class metadata.
 * Nothing here creates a CDI bean or touches config beyond reading `quarkus.application.name` —
 * reading a config value is not a bean presence, so the four-eyes invariant above is untouched.
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

        /**
         * The domain label for the D1 projection, derived from `quarkus.application.name` with
         * the same normalisation rest.rego applies to `money_path_services` (strip `openbank-`
         * and a trailing `-service`). Falls back to `unknown` only when the property is absent
         * (e.g. a plain unit test without Quarkus config); the BFF keys items off its own source
         * list, so a mislabelled domain here degrades display, never routing.
         */
        fun derivedDomain(): String = ConfigProvider.getConfig()
            .getOptionalValue("quarkus.application.name", String::class.java)
            .map { name -> name.removePrefix("openbank-").removeSuffix("-service") }
            .orElse("unknown")
    }
}

data class DecideApprovalRequest(val approve: Boolean)

/**
 * Wire shape of a pending/decided approval, shared by every migrated service's `openapi.yaml`.
 *
 * The first eight fields are the original ADR-0155 shape. The trailing fields are the ADR-0227
 * D1 canonical projection ([com.openbank.libs.approval.ApprovalItem]) — all optional, so the
 * change is additive per the ADR-0048 api-contract gate and existing consumers are unaffected:
 * - `summary`: the persisted request-binding summary; `null` for application-created approvals.
 * - `domain`: this service's domain, derived from `quarkus.application.name`.
 * - `amount` / `currency`: always `null` today — the maker's original request amount is not
 *   persisted (see ApprovalItem's KDoc); string-typed so a future amount serialises exactly.
 * - `riskClass`: UNKNOWN from the service side — the authoritative classification lives in
 *   rules.yaml action-class metadata the store cannot see; the admin-UI BFF re-derives it.
 * - `payloadRef`: always `null` today — the persisted fingerprint is an integrity digest, not a
 *   retrievable payload pointer.
 */
data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val makerId: String?,
    val makerActorKind: String,
    val createdAt: String?,
    val decidedBy: String?,
    val summary: String? = null,
    val domain: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    val riskClass: String? = null,
    val payloadRef: String? = null,
)

fun PendingApproval.toApprovalResponse() = toApprovalItem(ApprovalEndpointSupport.derivedDomain()).let { item ->
    ApprovalResponse(
        id = item.id,
        action = item.action,
        resourceId = resourceId,
        status = item.state.name,
        makerId = item.maker.id,
        makerActorKind = item.maker.channel.name,
        createdAt = item.maker.at.toString(),
        decidedBy = decidedBy,
        summary = item.summary,
        domain = item.domain,
        amount = item.amount?.toPlainString(),
        currency = item.currency,
        riskClass = item.riskClass.name,
        payloadRef = item.payloadRef,
    )
}
