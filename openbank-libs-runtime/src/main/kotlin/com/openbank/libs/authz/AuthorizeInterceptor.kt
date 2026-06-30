// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.Priority
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import kotlin.reflect.jvm.kotlinFunction

/**
 * CDI interceptor that turns [Authorize]-annotated methods into a call to
 * the injected [PolicyDecisionPoint] (ADR-0034 D5 Phase 2). Bound by the
 * [Authorize] annotation itself (it is also an `@InterceptorBinding`); no
 * `beans.xml` is required because Quarkus discovers CDI components via
 * Jandex from the libs JAR (ADR-0014).
 *
 * Resource extraction (`@Authorize(resource = "#partyId")`):
 *   1. Parse the leading `#`, take the remainder as a parameter name.
 *   2. Find the parameter via Kotlin reflection on the intercepted method
 *      (preferred — keeps names without `-parameters` javac flag).
 *   3. Pull the runtime arg by index from [InvocationContext.parameters].
 *   4. Derive `ResourceRef.type` from the action prefix
 *      (`party.update` → "party") — keeps the policy schema stable when
 *      callers pass a String id rather than a typesafe-id wrapper.
 *
 * Enforce vs advisory (`authz.enforce`, ADR-0034 D5 phased rollout):
 *   - `true` (default — safe for any service that opts in): failure modes
 *     below apply as written (403 on deny, 503 on outage).
 *   - `false` (Phase 3 pilot): the decision is still computed and a deny /
 *     outage is logged at WARN, but the call PROCEEDS. This is how a service
 *     adopts `@Authorize` without changing externally observable behavior,
 *     so the CI audit can confirm the policy rejects the right calls before
 *     anyone flips enforce on. Advisory must never brick an endpoint when no
 *     sidecar is deployed yet.
 *
 * Failure modes (enforce=true):
 *   - PDP returns `deny`  → `ForbiddenException` (HTTP 403) — the user is
 *     authenticated, the role check passed, but the policy denied.
 *   - PDP unreachable     → `ServiceUnavailableException` (HTTP 503) — do
 *     NOT fail open, do NOT pretend the user was forbidden. Distinct
 *     audit signal so an outage doesn't look like a flurry of access
 *     violations.
 *
 * Priority sits between PLATFORM_AFTER (authn already populated the
 * SecurityContext) and APPLICATION (business logic). The same slot the
 * Quarkus `@RolesAllowed` interceptor uses.
 */
@Authorize(action = "")
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER + 100)
class AuthorizeInterceptor {
    private val log = Logger.getLogger(AuthorizeInterceptor::class.java)

    // Resolved lazily via CDI Instance rather than a hard @Inject: the libs JAR
    // is on every service's classpath, so the interceptor bean (and its injection
    // points) is validated at Quarkus ArC augmentation in EVERY service — even
    // those that never annotate a method with @Authorize. A hard
    // `@Inject lateinit var pdp: PolicyDecisionPoint` therefore breaks the whole
    // fleet's build until each service ships a PDP bean. Instance<T> defers
    // resolution to call time: a service that has not opted in simply has no
    // @Authorize methods, so authorize() never runs; one that does opt in must
    // provide a PolicyDecisionPoint producer (see ADR-0034 D5 Phase 2).
    @Inject
    lateinit var pdp: Instance<PolicyDecisionPoint>

    // Instance<> (not a hard @Inject) for the same reason as `pdp` above: the libs JAR is
    // on every service's classpath, so this interceptor bean is validated in EVERY service —
    // including those with no security extension (e.g. the in-memory product-catalog). A hard
    // `@Inject SecurityIdentity` / `SecurityContext` therefore breaks those services' build
    // with an UnsatisfiedResolutionException. Instance<> defers resolution to call time, which
    // only happens inside an @Authorize-annotated request — a security service always has the
    // beans there; a non-security service simply has no @Authorize methods, so authorize()
    // never runs and the lookup never happens.
    @Inject
    lateinit var securityContext: Instance<SecurityContext>

    @Inject
    lateinit var identity: Instance<SecurityIdentity>

    // Optional — absent in non-HTTP contexts (batch/timer). Instance<> defers
    // resolution so the interceptor can still be used without an active HTTP request.
    @Inject
    lateinit var httpHeaders: Instance<HttpHeaders>

    @Inject
    lateinit var clock: Clock

    /**
     * Phase toggle (ADR-0034 D5). Default `true` so a service that adds
     * `@Authorize` without an explicit setting gets enforcement — the safe
     * default. The Phase-3 pilot sets `authz.enforce=false` to run advisory.
     */
    @ConfigProperty(name = "authz.enforce", defaultValue = "true")
    var enforce: Boolean = true

    @AroundInvoke
    fun authorize(ctx: InvocationContext): Any? {
        val annotation = ctx.method.getAnnotation(Authorize::class.java)
            ?: return ctx.proceed()

        if (!pdp.isResolvable) {
            // An @Authorize method exists but the service wired no PDP.
            if (!enforce) {
                log.warnf(
                    "advisory: no PolicyDecisionPoint bean for @Authorize %s.%s — proceeding (enforce=false)",
                    ctx.method.declaringClass.simpleName,
                    ctx.method.name,
                )
                return ctx.proceed()
            }
            // Fail closed — an authorization point that cannot reach a decision
            // must not silently allow. 503 (not 403) flags a wiring/outage, not a
            // policy denial, so it reads distinctly in the audit trail.
            log.errorf(
                "no PolicyDecisionPoint bean for @Authorize method %s.%s — failing closed",
                ctx.method.declaringClass.simpleName,
                ctx.method.name,
            )
            throw ServiceUnavailableException("policy decision point not configured")
        }
        val decisionPoint = pdp.get()

        val query = buildQuery(ctx, annotation)
        val decision: AuthzDecision = runBlocking {
            runCatching { decisionPoint.allow(query) }
                .getOrElse { ex ->
                    if (!enforce) {
                        log.warnf(
                            "advisory: PDP unavailable for action=%s: %s — proceeding (enforce=false)",
                            annotation.action,
                            ex.message,
                        )
                        return@runBlocking null
                    }
                    // JAX-RS ServiceUnavailableException has no (String, Throwable)
                    // ctor — chain the cause manually so logs still show the root.
                    throw ServiceUnavailableException("policy decision point unavailable: ${ex.message}").apply {
                        initCause(ex)
                    }
                }
        } ?: return ctx.proceed() // advisory + PDP unavailable: observe, do not block

        if (!decision.allow) {
            if (!enforce) {
                log.warnf(
                    "advisory: would DENY action=%s resource=%s principal=%s reason=%s — proceeding (enforce=false)",
                    annotation.action,
                    query.resource,
                    query.principal.id,
                    decision.reason ?: "unspecified",
                )
                return ctx.proceed()
            }
            log.debugf(
                "deny: action=%s resource=%s principal=%s reason=%s",
                annotation.action,
                query.resource,
                query.principal.id,
                decision.reason ?: "unspecified",
            )
            throw ForbiddenException(decision.reason ?: "policy denied")
        }
        return ctx.proceed()
    }

    private fun buildQuery(ctx: InvocationContext, annotation: Authorize): AuthzQuery {
        val sc = securityContext.get()
        val principal = Principal(
            id = sc.userPrincipal?.name ?: "anonymous",
            type = principalType(sc),
            roles = identity.get().roles.toList(),
        )
        val resource = annotation.resource.takeIf { it.isNotEmpty() }?.let { expr ->
            extractResource(ctx, annotation, expr)
        }
        val attrs = resolveAttributes(annotation)
        return AuthzQuery(principal = principal, action = annotation.action, resource = resource, attributes = attrs)
    }

    private fun resolveAttributes(annotation: Authorize): Map<String, Any?> {
        if (annotation.attributes.isEmpty()) return emptyMap()
        val headers = if (httpHeaders.isResolvable) httpHeaders.get() else null
        return annotation.attributes.mapNotNull { key ->
            val value: Any? = when (key) {
                "time-of-day" -> Instant.now(clock).toString()
                "client-ip" -> headers?.getRequestHeader("X-Forwarded-For")?.firstOrNull()
                "idempotency-key" -> headers?.getRequestHeader("Idempotency-Key")?.firstOrNull()
                else -> null
            }
            value?.let { key to it }
        }.toMap()
    }

    private fun extractResource(ctx: InvocationContext, annotation: Authorize, expr: String): ResourceRef? {
        if (!expr.startsWith("#")) return null
        val paramName = expr.substring(1)
        val kFunction = ctx.method.kotlinFunction ?: return null
        // First parameter on instance functions is the receiver; skip it.
        val paramIndex = kFunction.parameters
            .drop(1)
            .indexOfFirst { it.name == paramName }
        if (paramIndex < 0 || paramIndex >= ctx.parameters.size) return null
        val argValue = ctx.parameters[paramIndex] ?: return null
        val type = annotation.action.substringBefore('.')
        return ResourceRef(type = type, id = argValue.toString())
    }

    private fun principalType(sc: SecurityContext): String {
        // Convention: agents present `sub` prefixed `agent:` (ADR-0031); any
        // other authenticated principal is HUMAN. SERVICE-to-service uses a
        // separate mTLS path and never hits this interceptor.
        val name = sc.userPrincipal?.name ?: return "ANONYMOUS"
        return if (name.startsWith("agent:")) "AI_AGENT" else "HUMAN"
    }
}
