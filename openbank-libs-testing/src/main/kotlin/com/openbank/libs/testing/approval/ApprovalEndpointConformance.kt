// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.approval

import com.openbank.libs.approval.ApprovalRequestBinding
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.MakerActorKind
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.security.Principal
import java.time.OffsetDateTime

/**
 * Conformance suite for a service's checker-facing four-eyes `ApprovalResource` (ADR-0155,
 * ADR-0227 D2). Replaces the per-service `ApprovalResourceMappingTest` copies: a service binds its
 * own resource in [bind] and inherits every check below.
 *
 * What it pins is the RESOURCE's responsibility, not the shared body's (that is
 * `ApprovalEndpointSupportTest` in libs-runtime):
 * - the checker id passed to [ApprovalStore.decide] is the authenticated principal's name, never
 *   anything from the request, and an absent principal is the literal `anonymous`;
 * - a null body is rejected BEFORE the identity is read (#3029, #11033 — a resource that passes a
 *   pre-resolved identity instead of a supplier reads a `lateinit` property eagerly);
 * - an unknown id is a 404, self-approval propagates, and a caller-controlled `limit` is clamped.
 *
 * - the resource's declared security: `@RolesAllowed` and `@Authorize` (action + resource) of the two
 *   endpoint methods against the values the service declares in [listPendingSecurity] /
 *   [decideSecurity]. `AuthorizeInterceptor` fails OPEN on a method with no `@Authorize`, and a
 *   widened role set is a silent grant, so a service that changes either must change its subclass.
 *
 * Assertions go through a recording [ApprovalStore] and the HTTP status, never the entity type, so
 * a service with its own wire DTO (sca) conforms on the same terms.
 */
@Suppress("FunctionNaming", "TooManyFunctions") // one backticked @Test per case, as OutboxDispatchConformanceIT
abstract class ApprovalEndpointConformance {

    /** The two operations every approval resource serves, adapted from the service's own types. */
    interface Endpoint {
        suspend fun listPending(limit: Int): Response

        /** `approve == null` sends a null request body. */
        suspend fun decide(id: String, approve: Boolean?): Response
    }

    /** The security a service pins on one endpoint method: its roles and `@Authorize` action/resource. */
    data class EndpointSecurity(val roles: Set<String>, val action: String, val resource: String)

    /** The service's `ApprovalResource` class, whose `listPending`/`decide` annotations are asserted. */
    protected abstract val resourceClass: Class<*>

    /** Expected `@RolesAllowed` / `@Authorize` of `listPending`. */
    protected abstract val listPendingSecurity: EndpointSecurity

    /** Expected `@RolesAllowed` / `@Authorize` of `decide`. */
    protected abstract val decideSecurity: EndpointSecurity

    /** Construct the service's resource around [store], authenticated as [identity]. */
    protected abstract fun bind(store: ApprovalStore, identity: SecurityIdentity): Endpoint

    @Test
    fun `listPending serves the store's queue`(): Unit = runBlocking {
        val store = RecordingApprovalStore(pending = listOf(pending()))

        val response = bind(store, identity("checker-1")).listPending(DEFAULT_LIMIT)

        assertThat(response.status).isEqualTo(Response.Status.OK.statusCode)
        assertThat(store.findPendingLimits).containsExactly(DEFAULT_LIMIT)
        val queue = response.entity as List<*>
        assertThat(queue).hasSize(1)
        val item = queue.single()
        assertThat(property(item, "id")).isEqualTo(PENDING_ID)
        assertThat(property(item, "action")).isEqualTo(PENDING_ACTION)
        // Provenance: who asked and when, as the checker sees it.
        assertThat(property(item, "makerId")).isEqualTo(MAKER)
        assertThat(property(item, "createdAt")).isEqualTo(PENDING_CREATED_AT_WIRE)
    }

    @Test
    fun `listPending clamps a caller-controlled limit`(): Unit = runBlocking {
        val store = RecordingApprovalStore()

        bind(store, identity("checker-1")).listPending(OVERSIZED_LIMIT)

        assertThat(store.findPendingLimits.single()).isBetween(1, MAX_PENDING_LIMIT)
    }

    @Test
    fun `decide passes the authenticated principal name as checker`(): Unit = runBlocking {
        val store = RecordingApprovalStore(pending = listOf(pending()))

        val response = bind(store, identity("checker-1")).decide(PENDING_ID, approve = true)

        assertThat(response.status).isEqualTo(Response.Status.OK.statusCode)
        assertThat(store.decisions).containsExactly(Triple(PENDING_ID, "checker-1", true))
        val decided = response.entity
        assertThat(property(decided, "status")).isEqualTo("APPROVED")
        assertThat(property(decided, "decidedBy")).isEqualTo("checker-1")
    }

    @Test
    fun `an absent principal decides as the literal anonymous id`(): Unit = runBlocking {
        val store = RecordingApprovalStore(pending = listOf(pending()))

        bind(store, identity(null)).decide(PENDING_ID, approve = false)

        assertThat(store.decisions).containsExactly(Triple(PENDING_ID, "anonymous", false))
    }

    @Test
    fun `an unknown or already-decided id is a 404`() {
        val store = RecordingApprovalStore()

        assertThatThrownBy { runBlocking { bind(store, identity("checker-1")).decide("does-not-exist", true) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `self-approval refused by the store propagates`() {
        val store = RecordingApprovalStore(pending = listOf(pending()), selfApprovalGuard = true)

        assertThatThrownBy { runBlocking { bind(store, identity(MAKER)).decide(PENDING_ID, true) } }
            .isInstanceOf(SelfApprovalNotAllowedException::class.java)
    }

    @Test
    fun `a null body is rejected before the identity is read`() {
        val store = RecordingApprovalStore(pending = listOf(pending()))
        val reads = intArrayOf(0)

        assertThatThrownBy { runBlocking { bind(store, identity("checker-1", reads)).decide(PENDING_ID, null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(reads[0]).describedAs("SecurityIdentity reads before the null-body check").isZero()
        assertThat(store.decisions).isEmpty()
    }

    @Test
    fun `listPending carries the pinned roles and authorization action`() {
        assertSecurity(method("listPending"), listPendingSecurity)
    }

    @Test
    fun `decide carries the pinned roles and authorization action`() {
        assertSecurity(method("decide"), decideSecurity)
    }

    // Both endpoints are `suspend fun`s, so the compiled signature has a synthetic trailing
    // Continuation parameter: locate by declared name rather than guessing the erased signature.
    private fun method(name: String) = resourceClass.declaredMethods.single { it.name == name }

    private fun assertSecurity(method: java.lang.reflect.Method, expected: EndpointSecurity) {
        val roles = method.getAnnotation(RolesAllowed::class.java)
        assertThat(roles).describedAs("${method.name}: @RolesAllowed missing").isNotNull
        assertThat(roles.value.toSet()).describedAs("${method.name}: @RolesAllowed").isEqualTo(expected.roles)
        // Read by name: libs-testing does not depend on libs-runtime, where @Authorize lives.
        val found = method.annotations.singleOrNull { it.annotationClass.qualifiedName == AUTHORIZE_FQN }
        assertThat(found)
            .describedAs("${method.name}: @Authorize missing - the interceptor then fails OPEN")
            .isNotNull
        val authorize = requireNotNull(found)
        assertThat(attribute(authorize, "action")).describedAs("${method.name}: action").isEqualTo(expected.action)
        assertThat(attribute(authorize, "resource"))
            .describedAs("${method.name}: resource")
            .isEqualTo(expected.resource)
    }

    private fun attribute(annotation: Annotation, name: String): Any? =
        annotation.annotationClass.java.getMethod(name).invoke(annotation)

    /** Reads a wire-DTO property by getter, so a service with its own DTO (sca, settlement) conforms too. */
    private fun property(entity: Any?, name: String): Any? {
        val target = requireNotNull(entity) { "response entity is null" }
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        return target.javaClass.getMethod(getter).invoke(target)
    }

    private fun pending() = PendingApproval(
        id = PENDING_ID,
        action = PENDING_ACTION,
        resourceId = "resource-1",
        makerId = MAKER,
        status = ApprovalStatus.PENDING,
        createdAt = OffsetDateTime.parse(PENDING_CREATED_AT),
    )

    /** A [SecurityIdentity] whose every method call is counted in [reads]; only `principal` answers. */
    private fun identity(principalName: String?, reads: IntArray = intArrayOf(0)): SecurityIdentity =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SecurityIdentity::class.java)) { _, method, _ ->
            reads[0]++
            when (method.name) {
                "getPrincipal" -> principalName?.let { name -> Principal { name } }
                "isAnonymous" -> principalName == null
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "ConformanceIdentity($principalName)"
                else -> throw UnsupportedOperationException("conformance identity: ${method.name}")
            }
        } as SecurityIdentity

    private class RecordingApprovalStore(
        private val pending: List<PendingApproval> = emptyList(),
        private val selfApprovalGuard: Boolean = false,
    ) : ApprovalStore {
        val findPendingLimits = mutableListOf<Int>()
        val decisions = mutableListOf<Triple<String, String, Boolean>>()

        override suspend fun create(
            action: String,
            resourceId: String?,
            makerId: String,
            ttlSeconds: Long,
            binding: ApprovalRequestBinding?,
            makerActorKind: MakerActorKind,
        ): PendingApproval = throw UnsupportedOperationException("not part of the checker endpoint")

        override suspend fun find(id: String): PendingApproval? = pending.firstOrNull { it.id == id }

        override suspend fun findPending(limit: Int): List<PendingApproval> {
            findPendingLimits += limit
            return pending.take(limit)
        }

        override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? {
            val found = pending.firstOrNull { it.id == id } ?: return null
            if (selfApprovalGuard && found.makerId == decidedBy) throw SelfApprovalNotAllowedException(decidedBy)
            decisions += Triple(id, decidedBy, approve)
            return found.copy(
                status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
                decidedBy = decidedBy,
            )
        }

        override suspend fun markExecuted(id: String): PendingApproval? = null
    }

    private companion object {
        const val PENDING_ID = "appr-pending"
        const val MAKER = "maker"
        const val PENDING_ACTION = "conformance.action"
        const val PENDING_CREATED_AT = "2026-07-13T00:00:00Z"

        /** `OffsetDateTime.toString()` of [PENDING_CREATED_AT], which is what the wire DTOs carry. */
        const val PENDING_CREATED_AT_WIRE = "2026-07-13T00:00Z"
        const val AUTHORIZE_FQN = "com.openbank.libs.authz.Authorize"
        const val DEFAULT_LIMIT = 50
        const val OVERSIZED_LIMIT = 10_000

        /** Mirrors `ApprovalEndpointSupport.MAX_PENDING_LIMIT` (libs-runtime). */
        const val MAX_PENDING_LIMIT = 200
    }
}
