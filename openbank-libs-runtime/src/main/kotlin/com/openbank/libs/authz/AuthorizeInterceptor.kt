// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.observability.DomainMetrics
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.Priority
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import kotlin.reflect.full.memberProperties
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
 * Every decision — either mode — increments `openbank.authz.decisions`
 * (see [DomainMetrics.authzDecision]), tagged with the `enforced` value in
 * effect. This is what makes each service's stated rollout precondition ("flip
 * to true only after an observation window with a clean advisory report")
 * actually evaluable: `outcome=deny, enforced=false` IS that report. Before
 * this metric existed the only advisory signal was a WARN line on stdout, so
 * the precondition could not be checked for any service in the fleet.
 *
 * Failure modes (enforce=true):
 *   - PDP returns `deny`  → `ForbiddenException` (HTTP 403) — the user is
 *     authenticated, the role check passed, but the policy denied.
 *   - PDP unreachable     → `PolicyDecisionException` → HTTP 503 (via its
 *     ExceptionMapper in openbank-libs-runtime; issue #1797) — do
 *     NOT fail open, do NOT pretend the user was forbidden. Distinct
 *     audit signal so an outage doesn't look like a flurry of access
 *     violations.
 *
 * Priority sits between PLATFORM_AFTER (authn already populated the
 * SecurityContext) and APPLICATION (business logic). The same slot the
 * Quarkus `@RolesAllowed` interceptor uses.
 *
 * Four-eyes (ADR-0155, issue #395): an ALLOWED decision may still carry
 * `attributes["four_eyes_required"] == true` (OPA's `rest.rego`, for a
 * money-path action). When a service opts in (`authz.four-eyes.enforce=true`)
 * and wires an [ApprovalStore], such a call is paused instead of proceeding —
 * see [requireFourEyes] — until a second, distinct principal decides a
 * [com.openbank.libs.approval.PendingApproval] via the service's own
 * approval-decide endpoint and the maker retries with `X-Approval-Id`.
 * Default off and no-op without a wired [ApprovalStore], so shipping this in
 * the shared libs JAR does not retroactively change behavior for services
 * that haven't opted in.
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

    // Instance<> for the same reason as `pdp`/`securityContext` above. DomainMetrics ships in this
    // same JAR and is itself no-op-safe without a MeterRegistry, so it is resolvable in practice —
    // but a hard @Inject here would make this interceptor's validation depend on it in EVERY
    // service, which is precisely the fleet-wide build hazard the comments above exist to avoid.
    @Inject
    lateinit var metrics: Instance<DomainMetrics>

    // Instance<> for the same reason as `pdp`/`securityContext` above: most services
    // never wire an ApprovalStore, so a hard @Inject would break their build.
    @Inject
    lateinit var approvalStore: Instance<ApprovalStore>

    /**
     * Phase toggle (ADR-0034 D5). Default `true` so a service that adds
     * `@Authorize` without an explicit setting gets enforcement — the safe
     * default. The Phase-3 pilot sets `authz.enforce=false` to run advisory.
     */
    @ConfigProperty(name = "authz.enforce", defaultValue = "true")
    var enforce: Boolean = true

    /**
     * Four-eyes opt-in (ADR-0155). Default `false`: merging this feature into the
     * shared libs JAR must not retroactively start blocking traffic anywhere. A
     * service flips this only after wiring an [ApprovalStore] and reviewing its
     * threat model for the maker/checker flow.
     */
    @ConfigProperty(name = "authz.four-eyes.enforce", defaultValue = "false")
    var fourEyesEnforce: Boolean = false

    @AroundInvoke
    fun authorize(ctx: InvocationContext): Any? {
        val annotation = ctx.method.getAnnotation(Authorize::class.java)
            ?: return ctx.proceed()

        if (!pdp.isResolvable) {
            return onMissingPdp(ctx, annotation)
        }
        val decisionPoint = pdp.get()

        val query = buildQuery(ctx, annotation)
        val decision: AuthzDecision = runBlocking {
            runCatching { decisionPoint.allow(query) }
                .getOrElse { ex ->
                    record(annotation.action, "pdp_unavailable", query.principal.type)
                    if (!enforce) {
                        log.warnf(
                            "advisory: PDP unavailable for action=%s: %s — proceeding (enforce=false)",
                            annotation.action,
                            ex.message,
                        )
                        return@runBlocking null
                    }
                    // Propagate the domain PolicyDecisionException (503 via its ExceptionMapper in
                    // openbank-libs-runtime). A thrown JAX-RS ServiceUnavailableException was being
                    // laundered to 422 across the Kotlin suspend/coroutine bridge (issue #1797); a
                    // mapper keyed on the concrete domain type is immune to that.
                    throw PolicyDecisionException("policy decision point unavailable: ${ex.message}", ex)
                }
        } ?: return ctx.proceed() // advisory + PDP unavailable: observe, do not block

        if (!decision.allow) {
            // The rollout signal: outcome=deny + enforced=false is the "would DENY" population that
            // a service's advisory window has to show empty before AUTHZ_ENFORCE can flip.
            record(annotation.action, "deny", query.principal.type)
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
        record(annotation.action, "allow", query.principal.type)
        return requireFourEyesOrProceed(ctx, annotation, query, decision)
    }

    /**
     * An `@Authorize` method exists but the service wired no [PolicyDecisionPoint]. Advisory
     * proceeds; enforce fails CLOSED with 503 (not 403) — an authorization point that cannot reach
     * a decision must not silently allow, and an outage must not read as a flurry of policy denials
     * in the audit trail.
     */
    private fun onMissingPdp(ctx: InvocationContext, annotation: Authorize): Any? {
        // No query was built yet, so the principal type is not yet known — hence the "unknown" tag.
        record(annotation.action, "pdp_unconfigured", "unknown")
        if (!enforce) {
            log.warnf(
                "advisory: no PolicyDecisionPoint bean for @Authorize %s.%s — proceeding (enforce=false)",
                ctx.method.declaringClass.simpleName,
                ctx.method.name,
            )
            return ctx.proceed()
        }
        log.errorf(
            "no PolicyDecisionPoint bean for @Authorize method %s.%s — failing closed",
            ctx.method.declaringClass.simpleName,
            ctx.method.name,
        )
        throw PolicyDecisionException("policy decision point not configured")
    }

    /**
     * `null` when no [DomainMetrics] bean resolves, so every call site is a safe-call no-op.
     * [DomainMetrics] in turn no-ops without a [io.micrometer.core.instrument.MeterRegistry], so a
     * service with no micrometer extension records nothing and pays nothing.
     */
    private val meters: DomainMetrics?
        get() = if (metrics.isResolvable) metrics.get() else null

    private fun record(action: String, outcome: String, principalType: String) =
        meters?.authzDecision(action, outcome, enforce, principalType)

    /**
     * ADR-0155: gate an otherwise-allowed money-path action behind a second
     * approver when OPA flagged it `four_eyes_required`. No-op (proceeds
     * immediately) unless the service opted in via [fourEyesEnforce] AND wired
     * an [ApprovalStore] — see the class KDoc.
     */
    private fun requireFourEyesOrProceed(
        ctx: InvocationContext,
        annotation: Authorize,
        query: AuthzQuery,
        decision: AuthzDecision,
    ): Any? {
        val fourEyesRequired = decision.attributes["four_eyes_required"] == true
        if (!fourEyesRequired) {
            return ctx.proceed()
        }
        if (!fourEyesEnforce) {
            // OPA asked for a second approver and we are about to proceed without one. Nothing
            // recorded this before, which made it indistinguishable from "four-eyes not required" —
            // so a fleet where authz.four-eyes.enforce is false everywhere (the current default,
            // and never overridden in gitops) looked exactly like a fleet with no flagged actions.
            meters?.authzFourEyes(annotation.action, "required_not_enforced")
            // debug, not warn (issue #1391): this is normal/expected state fleet-wide today, not a
            // misconfiguration — flipping authz.four-eyes.enforce is a deliberate, separate
            // operational decision. But the metric above wasn't paired with any log line, so this
            // advisory state was invisible to anyone tailing logs rather than querying metrics —
            // asymmetric with the ApprovalStore-missing branch below, which does log loudly for
            // exactly this class of "silently not enforcing" gap.
            log.debugf(
                "four-eyes: action=%s is flagged four_eyes_required but authz.four-eyes.enforce=false " +
                    "— proceeding without the second-approver gate (advisory)",
                annotation.action,
            )
            return ctx.proceed()
        }
        if (!approvalStore.isResolvable) {
            meters?.authzFourEyes(annotation.action, "no_approval_store")
            // Code review finding: this used to fall into the same silent-proceed branch as
            // "four-eyes not required" / "not enforced", with no log at all — indistinguishable
            // from a service correctly not opting in. Mirrors the log.errorf the PDP-missing
            // branch above already uses for an analogous misconfiguration; still proceeds
            // (ADR-0155 D3 deliberately keeps this a no-op, not a fail-closed 503) but now at
            // least leaves an operator-visible trail that four-eyes was supposed to gate this.
            log.errorf(
                "four-eyes: action=%s is flagged four_eyes_required with authz.four-eyes.enforce=true, " +
                    "but no ApprovalStore bean is wired — proceeding WITHOUT the second-approver gate. " +
                    "Wire an ApprovalStore for this service or set authz.four-eyes.enforce=false until it is.",
                annotation.action,
            )
            return ctx.proceed()
        }
        val store = approvalStore.get()
        val maker = query.principal.id
        val resourceId = query.resource?.id

        val approvalId = resolveApprovalIdHeader()
        if (approvalId != null) {
            val approval = runBlocking { store.find(approvalId) }
            if (approval.satisfies(annotation.action, resourceId, maker)) {
                runBlocking { store.markExecuted(approvalId) }
                meters?.authzFourEyes(annotation.action, "approval_satisfied")
                return ctx.proceed()
            }
            log.warnf(
                "four-eyes: approval id=%s not valid for action=%s maker=%s " +
                    "(missing, mismatched, not approved, or already consumed) — re-issuing a pending approval",
                approvalId,
                annotation.action,
                maker,
            )
        }

        val pending = runBlocking { store.create(annotation.action, resourceId, maker) }
        meters?.authzFourEyes(annotation.action, "pending_approval")
        log.infof(
            "four-eyes: action=%s resource=%s maker=%s requires a second approver — approvalId=%s",
            annotation.action,
            resourceId,
            maker,
            pending.id,
        )
        throw WebApplicationException(
            Response.status(PENDING_APPROVAL_STATUS)
                .entity(mapOf("status" to "PENDING_APPROVAL", "approvalId" to pending.id))
                .type(MediaType.APPLICATION_JSON)
                .build(),
        )
    }

    private fun resolveApprovalIdHeader(): String? {
        if (!httpHeaders.isResolvable) return null
        return httpHeaders.get().getRequestHeader(APPROVAL_ID_HEADER)?.firstOrNull()
    }

    /** A supplied approval only unlocks THIS exact action, resource, and original maker. */
    private fun PendingApproval?.satisfies(action: String, resourceId: String?, maker: String): Boolean =
        this != null &&
            status == ApprovalStatus.APPROVED &&
            this.action == action &&
            this.resourceId == resourceId &&
            makerId == maker

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
        // "#param" -> whole parameter; "#param.field" -> one property of that parameter
        // (ADR-0206), resolved via the parameter's own primary-constructor properties —
        // one level deep only, no nested paths.
        val (paramName, fieldName) = expr.substring(1).split('.', limit = 2)
            .let { it[0] to it.getOrNull(1) }
        val kFunction = ctx.method.kotlinFunction ?: return null
        // First parameter on instance functions is the receiver; skip it.
        val paramIndex = kFunction.parameters
            .drop(1)
            .indexOfFirst { it.name == paramName }
        if (paramIndex < 0 || paramIndex >= ctx.parameters.size) return null
        val argValue = ctx.parameters[paramIndex] ?: return null
        val type = annotation.action.substringBefore('.')
        val resolvedValue = if (fieldName == null) {
            argValue
        } else {
            resolveResourceField(argValue, fieldName, log) ?: return null
        }
        return ResourceRef(type = type, id = resolvedValue.toString())
    }

    private fun principalType(sc: SecurityContext): String {
        // Convention: agents present `sub` prefixed `agent:` (ADR-0031); any
        // other authenticated principal is HUMAN. SERVICE-to-service uses a
        // separate mTLS path and never hits this interceptor.
        val name = sc.userPrincipal?.name ?: return "ANONYMOUS"
        return if (name.startsWith("agent:")) "AI_AGENT" else "HUMAN"
    }

    private companion object {
        const val APPROVAL_ID_HEADER = "X-Approval-Id"
        const val PENDING_APPROVAL_STATUS = 202
    }
}

/**
 * Resolves one property off [target] by name (ADR-0206 dotted-path resource extraction).
 * Wrapped in a broad catch — a Java-only or proxied parameter type can make
 * `memberProperties`/`getter.call` throw (e.g. `KotlinReflectionInternalError`) rather than
 * simply not find the property, and this must fail closed the same way an unrecognized field
 * name does, not abort the whole authorization decision with an unrelated reflection exception.
 */
@Suppress("TooGenericExceptionCaught")
private fun resolveResourceField(target: Any, fieldName: String, log: Logger): Any? = try {
    target::class.memberProperties
        .firstOrNull { it.name == fieldName }
        ?.getter?.call(target)
} catch (ex: Exception) {
    log.warnf(
        "resource field extraction failed for '%s': %s — falling back to no resource",
        fieldName,
        ex.message,
    )
    null
}
